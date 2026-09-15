-- ============================================================
-- SKEMA NYASAR v1 — disesuaikan dengan struktur Room asli
-- (RouteEntity, ActivityEntity, ActivityPointEntity, WaypointEntity)
--
-- Prinsip: ini BUKAN cermin 1:1 dari database lokal. Ada DUA jenis
-- tabel cloud di sini, tujuannya beda:
--   1. TABEL PUBLIK (routes, waypoints, route_likes, route_comments,
--      reports, saved_routes) — isinya cuma yang dipublish user lewat
--      aksi "Publish" eksplisit, diringkas dari data lokal, dan bisa
--      dilihat orang lain.
--   2. TABEL BACKUP PRIBADI (activity_backups) — isinya SEMUA activity
--      user (bukan cuma yang dipublish), tapi PRIVAT (RLS: cuma
--      pemiliknya sendiri yang bisa baca), tujuannya restore data kalau
--      ganti HP — bukan buat dilihat orang lain.
-- activity_points (mentah, per-GPS-fix) TIDAK PERNAH dikirim mentah ke
-- salah satu dari dua jenis tabel di atas — selalu diringkas/di-encode
-- dulu di app sebelum dikirim (lihat catatan di bagian bawah file).
-- ============================================================

create table profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  username text unique not null,
  display_name text,
  created_at timestamptz default now()
);

-- ============================================================
-- MIGRASI (ditambahkan setelah bug ditemukan): profiles.username_is_set
--
-- Kenapa perlu: layar wajib "Pilih Username" yang disebut di komentar
-- trigger handle_new_user di bawah ini SEHARUSNYA non-skippable, tapi
-- kode Kotlin sebelumnya menentukan "perlu munculin gate apa nggak"
-- dari SessionSource (SignUp vs SignIn) — yang ternyata BOCOR kalau
-- project Supabase mewajibkan konfirmasi email: signUp() tidak
-- langsung dapat session, sesi pertama yang beneran aktif justru
-- lewat SignIn biasa (setelah user klik link di email lalu login
-- manual) — dan SignIn sengaja TIDAK di-gate (biar user lama gak
-- disuruh pilih ulang). Akibatnya user baru lolos gate, tetap pakai
-- username hasil trigger (jelek, bukan pilihan sendiri).
--
-- Fix: gate diputuskan dari DATA (kolom ini), bukan dari cara sesi itu
-- terbentuk — benar di kedua kondisi dashboard (confirm email ON/OFF).
-- Di-set true HANYA oleh AuthRepository.updateUsername() saat user
-- benar-benar submit pilihan sendiri lewat ChooseUsernameScreen.
-- ============================================================
alter table profiles add column username_is_set boolean not null default false;

-- ============================================================
-- FIX (gap konsep — belum ada implementasi sama sekali sebelumnya):
-- tanpa ini, tidak ada satu pun jalan bagi row `profiles` untuk pernah
-- terbentuk. Daftar akun (Supabase Auth) sukses membuat baris di
-- `auth.users`, tapi TIDAK otomatis mengisi `profiles` — dan RLS
-- `profiles` di bawah sengaja hanya mengizinkan select/update, bukan
-- insert dari client, jadi app juga tidak bisa bikin sendiri.
--
-- PENTING soal UX: trigger ini HANYA jaring pengaman teknis (memastikan
-- constraint `username not null` tidak pernah gagal), BUKAN alur yang
-- diinginkan untuk user sungguhan. Username hasil trigger ini
-- (email + potongan id acak) SENGAJA jelek dan tidak "final" — alur
-- yang benar: app HARUS menampilkan layar wajib "Pilih Username" tepat
-- setelah daftar berhasil (sebelum masuk ke home), sebelum user boleh
-- publish/like/comment apa pun. Layar itu cek dulu ke Supabase apakah
-- username pilihan user sudah dipakai orang lain, baru UPDATE baris
-- profiles yang sudah dibuat trigger ini. Jangan biarkan user jalan
-- terus dengan username hasil trigger — itu bukan fallback yang
-- ditawarkan ke user, itu hanya mencegah error database di belakang
-- layar selama sepersekian detik sebelum layar wajib itu muncul.
-- ============================================================
create function public.handle_new_user()
returns trigger
language plpgsql
security definer set search_path = public
as $$
begin
  insert into public.profiles (id, username, display_name)
  values (
    new.id,
    split_part(new.email, '@', 1) || '_' || substr(new.id::text, 1, 6),
    split_part(new.email, '@', 1)
  );
  return new;
end;
$$;

create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function public.handle_new_user();

-- ============================================================
-- ROUTES: hasil publish dari sebuah ActivityEntity (atau RouteEntity
-- GPX import) yang user pilih untuk dibagikan publik.
-- Nama kolom & satuan SENGAJA disamakan persis dengan ActivityEntity
-- Kotlin, supaya kode mapping tinggal "copy field", tanpa konversi.
-- ============================================================
create table routes (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references profiles(id) on delete cascade not null,

  -- asal-usul, buat ditelusuri balik ke data lokal kalau perlu
  source_activity_id text,          -- ActivityEntity.id (UUID lokal) yang di-publish, kalau ada
  source_route_id text,             -- RouteEntity.id (UUID lokal), kalau publish dari GPX import

  name text not null,
  mountain_name text,               -- diisi manual oleh user saat publish (form terpisah, bukan dari Entity)
  region text,                      -- sama, diisi manual saat publish

  -- persis sama seperti ActivityEntity/RouteEntity, biar konsisten
  distance_meters double precision not null,
  elevation_gain_m double precision,
  elevation_loss_m double precision,
  max_elevation_m double precision,
  min_elevation_m double precision,
  moving_time_ms bigint,
  elapsed_time_ms bigint,
  avg_speed_kmh double precision,
  max_speed_kmh double precision,
  -- Daftar & urutan disalin PERSIS dari enum SportType.kt (verified,
  -- bukan tebakan) — lihat catatan di bawah file soal UNSPECIFIED.
  sport_type text not null default 'UNSPECIFIED'
    check (sport_type in (
      'UNSPECIFIED',
      'RUN','TRAIL_RUN','WALK','HIKE','WHEELCHAIR',
      'RIDE'
    )),
  difficulty text check (difficulty in ('easy','moderate','difficult','very_difficult')),
  difficulty_description text,      -- opsional, teks bebas: jenis medan, tanjakan curam, dst (ala Wikiloc)
  trail_type text check (trail_type in ('loop','out_and_back','point_to_point')),

  track_polyline text not null,     -- hasil encode ActivityPointEntity/RouteEntity track (lihat catatan bawah)
  start_lat double precision,
  start_lng double precision,

  -- Full-fidelity GPX (lat/lon/elevasi/waktu LENGKAP, gzip-compressed)
  -- disimpan di Supabase Storage, bukan sebagai kolom database — kolom
  -- ini cuma nyimpen path/URL-nya. Tujuannya: user lain bisa DOWNLOAD
  -- GPX asli (lossless, bukan hasil decode polyline yang udah kehilangan
  -- elevasi & timestamp). Nullable karena rute lama (sebelum fitur ini
  -- dibuat) belum tentu punya file ini.
  gpx_file_url text,

  description text,

  likes_count integer default 0,
  comments_count integer default 0,

  is_public boolean default true,
  is_draft boolean default false,   -- belum selesai diisi (nama/deskripsi/dst), beda dari is_public=false
  created_at timestamptz default now(),
  updated_at timestamptz default now()
);

create index idx_routes_mountain on routes(mountain_name);
create index idx_routes_region on routes(region);
create index idx_routes_created on routes(created_at desc);
create index idx_routes_likes on routes(likes_count desc);
create unique index idx_routes_source_activity on routes(source_activity_id) where source_activity_id is not null;
-- FIX: dulu cuma ada unique index buat source_activity_id (rute hasil
-- recording). Rute hasil upload/import GPX (source_route_id) belum
-- dicegah dobel — user bisa publish file GPX yang sama berkali-kali jadi
-- row duplikat. Pola index ini disalin dari activity_backups yang sudah
-- lebih dulu benar (idx_backups_source_route di bawah).
create unique index idx_routes_source_route on routes(source_route_id) where source_route_id is not null;

-- ============================================================
-- WAYPOINTS: field disamakan dengan WaypointEntity Kotlin, TAPI TIDAK
-- 1:1 persis — WaypointEntity lokal punya DUA kolom penaut
-- (linkedRouteId DAN linkedActivityId, waypoint bisa nempel ke Route
-- ATAU Activity lokal), sedangkan tabel cloud ini cuma punya SATU
-- (linked_route_id). Ini bukan bug: apa pun asal lokalnya (activity
-- atau route), begitu dipublish dia selalu nempel ke routes.id (cloud)
-- yang baru dibikin — jadi satu kolom ini tetap cukup secara fungsi.
-- Dicatat eksplisit di sini biar tidak membingungkan saat menulis kode
-- publish nanti.
-- linked_route_id di sini merujuk ke routes.id (cloud), BUKAN ke
-- RouteEntity.id lokal — pemetaan id lokal->cloud dilakukan di app
-- saat proses publish.
-- ============================================================
create table waypoints (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references profiles(id) on delete cascade not null,

  name text not null,
  category text not null default 'CUSTOM'
    check (category in ('SUMMIT','WATER','SHELTER','CAMPSITE','DANGER','PARKING','POI','CUSTOM')),
  lat double precision not null,
  lon double precision not null,
  elevation_m double precision,
  note text,

  linked_route_id uuid references routes(id) on delete cascade,

  source text not null default 'USER' check (source in ('USER','GPX')),

  created_at timestamptz default now()
);

create index idx_waypoints_route on waypoints(linked_route_id);

-- ============================================================
-- LIKES & COMMENTS — ditunda dari sisi fitur UI (belum urgent),
-- tapi tabelnya disiapkan sekalian biar gak perlu migrasi lagi nanti.
-- ============================================================
create table route_likes (
  user_id uuid references profiles(id) on delete cascade,
  route_id uuid references routes(id) on delete cascade,
  created_at timestamptz default now(),
  primary key (user_id, route_id)
);

create table route_comments (
  id uuid primary key default gen_random_uuid(),
  route_id uuid references routes(id) on delete cascade not null,
  user_id uuid references profiles(id) on delete cascade not null,
  content text not null,
  created_at timestamptz default now()
);

create index idx_comments_route on route_comments(route_id, created_at desc);

-- ============================================================
-- REPORTS: laporan konten bermasalah (rute salah/berbahaya, komentar
-- spam/toxic, dst). Bisa nunjuk ke route ATAU comment, gak dua-duanya.
-- Ini yang bikin fitur publik "aman ditinggal" tanpa moderasi manual
-- 24 jam — user sendiri yang lapor, kamu tinggal cek laporan masuk.
-- ============================================================
create table reports (
  id uuid primary key default gen_random_uuid(),
  reporter_id uuid references profiles(id) on delete cascade not null,

  route_id uuid references routes(id) on delete cascade,
  comment_id uuid references route_comments(id) on delete cascade,

  reason text not null check (reason in ('spam','misleading','offensive','danger','other')),
  note text,

  status text not null default 'open' check (status in ('open','reviewed','dismissed')),

  created_at timestamptz default now(),

  constraint report_target_check check (
    (route_id is not null and comment_id is null) or
    (route_id is null and comment_id is not null)
  )
);

create index idx_reports_status on reports(status) where status = 'open';

-- ============================================================
-- SAVED_ROUTES: "Save to a List" ala Wikiloc — beda dari Like/Clap.
-- Like = apresiasi publik ("bagus nih"), Save = to-do list pribadi
-- ("mau aku daki nanti"). Makanya tabel terpisah, bukan digabung
-- ke route_likes.
-- ============================================================
create table saved_routes (
  user_id uuid references profiles(id) on delete cascade,
  route_id uuid references routes(id) on delete cascade,
  created_at timestamptz default now(),
  primary key (user_id, route_id)
);

-- ============================================================
-- ACTIVITY_BACKUPS — BACKUP PRIBADI, BUKAN fitur sosial/publik.
-- Beda tujuan dari "routes": ini nampung SEMUA activity user (dipublish
-- ATAU TIDAK), supaya kalau ganti HP / install ulang, histori pendakian
-- pribadi bisa direstore — bukan buat dilihat user lain (lihat RLS di
-- bawah: cuma pemiliknya sendiri yang bisa akses).
--
-- track_data: full-fidelity (lat/lon/elevasi/waktu, TIDAK lossy seperti
-- track_polyline) tapi tetap hemat, dengan cara delta-encode (simpan
-- SELISIH antar titik berurutan, bukan koordinat penuh tiap titik) lalu
-- di-gzip. Hasilnya lebih kecil dari track_polyline sekalipun datanya
-- lebih lengkap (~9 KB vs ~14 KB untuk track 4 jam/2880 titik) — karena
-- selisih antar titik GPS berdekatan itu angka kecil, jauh lebih hemat
-- daripada nyimpen koordinat penuh berulang-ulang.
-- ============================================================
create table activity_backups (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references profiles(id) on delete cascade not null,

  -- salah satu dari dua ini wajib diisi, tapi gak keduanya sekaligus —
  -- lihat constraint di bawah
  source_activity_id text,          -- ActivityEntity.id lokal, kalau backup dari recording
  source_route_id text,             -- RouteEntity.id lokal, kalau backup dari GPX import

  -- disalin apa adanya dari ActivityEntity/RouteEntity, TANPA field
  -- tambahan (mountain_name/difficulty/dst tidak relevan di sini —
  -- ini backup mentah, bukan hasil publish)
  name text not null,
  started_at_epoch_ms bigint,
  ended_at_epoch_ms bigint,
  -- FIX: sebelumnya tanpa check constraint (bebas isi apa saja).
  -- Disamakan dengan 4 nilai asli ActivityStatus.kt. 'recording'/'paused'
  -- SENGAJA tetap diizinkan tersimpan di sini (beda dari `routes` yang
  -- hanya untuk hasil publish activity yang sudah selesai) — alasannya:
  -- kalau app crash/HP mati mendadak di tengah recording, backup activity
  -- yang belum selesai ini tetap berguna untuk dipulihkan saat restore,
  -- bukan cuma yang sudah 'completed'. Keputusan produk terkait
  -- 'discarded' (activity yang sengaja dibuang user) BELUM diambil apakah
  -- perlu tetap dibackup atau tidak — tandai TODO, jangan diasumsikan.
  status text check (status is null or status in ('recording','paused','completed','discarded')),
  distance_meters double precision,
  elevation_gain_m double precision,
  elevation_loss_m double precision,
  moving_time_ms bigint,
  elapsed_time_ms bigint,
  avg_speed_kmh double precision,
  max_speed_kmh double precision,
  sport_type text
    check (sport_type is null or sport_type in (
      'UNSPECIFIED',
      'RUN','TRAIL_RUN','WALK','HIKE','WHEELCHAIR',
      'RIDE'
    )),
  -- FIX (gap ditemukan): ActivityEntity.routeId (link ke RouteEntity
  -- LOKAL, "activity ini sedang mengikuti rute yang mana") sebelumnya
  -- tidak ikut ter-backup. Tanpa ini, restore ke HP baru akan kehilangan
  -- keterkaitan activity ke route-nya meski keduanya sama-sama di-backup
  -- terpisah. Nullable karena ActivityEntity.routeId sendiri nullable
  -- (activity boleh berdiri sendiri tanpa route).
  local_route_id text,

  -- lihat catatan di atas soal delta-encode + gzip
  track_data bytea not null,

  -- waypoint yang nempel ke activity/route ini, disalin apa adanya
  -- (array of object: name, category, lat, lon, elevation_m, note, dst)
  -- — tidak perlu tabel terpisah karena ini backup privat, bukan data
  -- yang perlu di-query/join lintas user seperti waypoints publik
  waypoints_json jsonb,

  created_at timestamptz default now(),
  updated_at timestamptz default now(),

  constraint backup_source_check check (
    (source_activity_id is not null and source_route_id is null) or
    (source_activity_id is null and source_route_id is not null)
  )
);

create unique index idx_backups_source_activity on activity_backups(user_id, source_activity_id) where source_activity_id is not null;
create unique index idx_backups_source_route on activity_backups(user_id, source_route_id) where source_route_id is not null;

-- ============================================================
-- ROW LEVEL SECURITY
-- ============================================================

alter table profiles enable row level security;
alter table routes enable row level security;
alter table waypoints enable row level security;
alter table route_likes enable row level security;
alter table route_comments enable row level security;
alter table reports enable row level security;
alter table saved_routes enable row level security;
alter table activity_backups enable row level security;

create policy "Profiles are viewable by everyone" on profiles for select using (true);
create policy "Users can update own profile" on profiles for update using (auth.uid() = id);

create policy "Public routes are viewable by everyone" on routes for select using ((is_public = true and is_draft = false) or auth.uid() = user_id);
create policy "Users can insert own routes" on routes for insert with check (auth.uid() = user_id);
create policy "Users can update own routes" on routes for update using (auth.uid() = user_id);
create policy "Users can delete own routes" on routes for delete using (auth.uid() = user_id);

create policy "Waypoints follow parent route visibility" on waypoints for select using (
  auth.uid() = user_id
  or exists (select 1 from routes r where r.id = linked_route_id and r.is_public = true and r.is_draft = false)
);
create policy "Users can insert own waypoints" on waypoints for insert with check (auth.uid() = user_id);
create policy "Users can update own waypoints" on waypoints for update using (auth.uid() = user_id);
create policy "Users can delete own waypoints" on waypoints for delete using (auth.uid() = user_id);

create policy "Likes are viewable by everyone" on route_likes for select using (true);
create policy "Users can like as themselves" on route_likes for insert with check (auth.uid() = user_id);
create policy "Users can unlike own like" on route_likes for delete using (auth.uid() = user_id);

create policy "Comments are viewable by everyone" on route_comments for select using (true);
create policy "Users can comment as themselves" on route_comments for insert with check (auth.uid() = user_id);
create policy "Users can delete own comments" on route_comments for delete using (auth.uid() = user_id);

-- Reports: user cuma bisa lihat laporan yang dia buat sendiri (bukan
-- laporan orang lain — biar gak jadi ajang saling intip). Kamu sebagai
-- admin baca semua laporan langsung dari Supabase Dashboard (Table
-- Editor), bukan lewat app, jadi gak perlu policy admin khusus dulu.
create policy "Users see own reports" on reports for select using (auth.uid() = reporter_id);
create policy "Users can create reports" on reports for insert with check (auth.uid() = reporter_id);

-- Saved routes: privat, cuma pemilik yang bisa lihat daftar simpanannya sendiri
create policy "Users see own saved routes" on saved_routes for select using (auth.uid() = user_id);
create policy "Users can save routes" on saved_routes for insert with check (auth.uid() = user_id);
create policy "Users can unsave own saved route" on saved_routes for delete using (auth.uid() = user_id);

-- Activity backups: 100% privat. Beda dari semua policy di atas, tidak
-- ada pengecualian "viewable by everyone" sama sekali — orang lain,
-- termasuk sesama user terautentikasi, TIDAK BISA lihat backup siapapun
-- selain miliknya sendiri.
create policy "Users see own backups" on activity_backups for select using (auth.uid() = user_id);
create policy "Users can insert own backups" on activity_backups for insert with check (auth.uid() = user_id);
create policy "Users can update own backups" on activity_backups for update using (auth.uid() = user_id);
create policy "Users can delete own backups" on activity_backups for delete using (auth.uid() = user_id);

-- ============================================================
-- CATATAN PENTING:
--
-- 1. TIDAK ADA tabel "activities" ataupun "activity_points" mentah di
--    sini. ActivityEntity + ActivityPointEntity TETAP 100% lokal (Room)
--    sebagai sumber data utama. "routes" adalah HASIL PUBLISH (ringkasan
--    + track terenkode, publik). "activity_backups" adalah SALINAN
--    privat buat restore (lengkap tapi tetap terkompresi, bukan publik).
--
-- 2. track_polyline (tabel routes): di-generate di app dari
--    ActivityDao.getLatLonOnly(activityId) UNTUK publish-dari-activity,
--    ATAU dari GpxParser UNTUK publish-dari-RouteEntity/GPX import (dua
--    jalur berbeda, lihat cross-check di PROJECT_CONTEXT.md). Di-encode
--    pakai algoritma Polyline Encoding standar (Google/Strava format),
--    SEKALI saja saat user menekan "Publish".
--
-- 3. mountain_name & region (tabel routes): TIDAK ADA di
--    ActivityEntity/RouteEntity Kotlin. Field baru yang user isi manual
--    lewat form publish (perlu dibuat: PublishRouteScreen atau serupa),
--    karena app saat ini tidak menyimpan info gunung/wilayah terstruktur.
--
-- 4. gpx_file_url (tabel routes): BUKAN hasil decode balik dari
--    track_polyline (yang lossy, kehilangan elevasi & timestamp per
--    titik). Ini file GPX ASLI (lengkap, presisi penuh) yang di-GZIP
--    lalu diupload ke Supabase Storage (bukan kolom database) saat user
--    menekan "Publish". Gzip dipilih karena file GPX XML sangat repetitif
--    (tag <trkpt> berulang ribuan kali), rasio kompresinya tinggi (~10x)
--    — hasilnya cuma sekitar 2x lebih besar dari track_polyline, JAUH
--    lebih hemat dibanding menyimpan GPX mentah tanpa kompresi.
--
-- 5. track_data (tabel activity_backups): BEDA CARA ENCODE dari
--    gpx_file_url di atas. Ini bukan gzip atas file XML, tapi DELTA-
--    ENCODE (selisih antar titik berurutan, dikemas jadi angka kecil)
--    baru di-gzip — lebih kompak lagi (~9 KB vs ~27 KB untuk gpx_file_url
--    pada track yang sama), karena tidak ada overhead tag XML sama
--    sekali. Cocok buat backup pribadi yang volumenya jauh lebih banyak
--    (SEMUA activity, bukan cuma yang dipublish) sehingga tiap KB
--    penting untuk tetap muat di kuota gratis.
--
-- 6. sport_type — SUDAH SELESAI DIKERJAKAN di sisi Kotlin, terverifikasi
--    di kode (bukan lagi rencana). `UNSPECIFIED` sudah ditambahkan ke
--    enum `SportType.kt`, dan sudah jadi default baru di keempat titik
--    yang tercatat sebelumnya (RecordingService.kt RecordingUiState,
--    ActivityEntity.kt kolom sportType, RecordingScreen.kt parameter
--    selectedSportType, SportType.fromString() fallback). Desain yang
--    diambil untuk UNSPECIFIED: icon Icons.Default.HelpOutline (netral,
--    bukan icon olahraga spesifik), category baru SportCategory.OTHER
--    (tidak dipaksa masuk FOOT/CYCLE), primaryMetric ShareMetric.PACE
--    (metric paling netral yang tersedia — ELEVATION dianggap terlalu
--    terrain-spesifik untuk sport yang belum ditentukan). Label lewat
--    string resource sport_unspecified, sudah ada di values/ dan
--    values-en/ (i18n lengkap, bukan hardcode).
--
--    Migration Room: TIDAK dibuat (Opsi A dari dua opsi yang dulu
--    dicatat). Activity lama yang sudah kepersist dengan TRAIL_RUN
--    TETAP TRAIL_RUN — data historis tidak disentuh. Hanya activity BARU
--    ke depan yang defaultnya UNSPECIFIED, karena perubahan terjadi di
--    level default value Kotlin, bukan constraint/migration database.
--    AppDatabase masih version 8, tidak ada MIGRATION_8_9.
-- ============================================================
