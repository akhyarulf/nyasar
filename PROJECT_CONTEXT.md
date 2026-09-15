# Konteks Project: Nyasar — Fitur Akun, Share Rute Publik & Backup Pribadi

## Tentang App

Nyasar adalah aplikasi Android (Kotlin, Jetpack Compose) untuk pendakian
gunung/hiking di Indonesia. Fitur yang sudah ada: rekam GPS (recording),
import/export GPX, peta offline (MapLibre + beberapa basemap: MapTiler,
OpenFreeMap, OpenStreetMap, dll), dan waypoint (titik penting: puncak,
sumber air, shelter, dll).

Database lokal pakai Room, dengan entity utama:
- `RouteEntity` — GPX yang diimport user, murni lokal
- `ActivityEntity` — histori rekaman pendakian (status, jarak, durasi, dst)
- `ActivityPointEntity` — titik GPS mentah, 1 row per fix, ditulis
  real-time selama recording (demi keamanan data kalau crash/baterai habis)
- `WaypointEntity` — titik penting, bisa nempel ke Route atau Activity

Firebase dipakai HANYA untuk Analytics + Crashlytics (gratis, tanpa
kartu kredit). TIDAK dipakai untuk auth/database — itu keputusan
sadar karena Firebase Cloud Storage sejak Feb 2026 wajib upgrade ke
plan Blaze (butuh kartu kredit) bahkan untuk pemakaian kecil.

## Keputusan yang Sudah Diambil

1. **Fitur foto dihapus total dari app** — selesai & terverifikasi di
   kode (migration Room `AppDatabase.kt` v7→v8, `DROP TABLE
   activity_photos`, data lain tidak tersentuh). Alasan: foto adalah
   satu-satunya jenis data yang berat secara storage, menghapusnya
   membuka ruang supaya **Backup Pribadi** (lihat di bawah) tetap muat
   gratis di kuota Supabase.

2. **Backend pilihan: Supabase** (bukan Firebase Firestore/Storage),
   karena free tier-nya (500MB DB, 1GB storage, unlimited API request,
   50rb MAU) tidak mewajibkan kartu kredit, tidak seperti Firebase
   Storage yang mulai memaksa upgrade ke plan Blaze.

3. **Arsitektur data: LOCAL-FIRST, publish itu aksi eksplisit.**
   `ActivityPointEntity` (GPS mentah) TETAP 100% lokal di Room, tidak
   pernah dikirim ke cloud apa adanya. Yang dikirim ke Supabase hanya
   RINGKASAN: encoded polyline, dibuat SEKALI saat user menekan tombol
   "Publish" — bukan disinkron terus-menerus. Meniru pola Strava.

4. **Duplikasi rute dibiarkan** — kalau banyak user upload "Gunung X
   via Jalur Y" versi masing-masing, TIDAK digabung otomatis (butuh
   geospatial matching kompleks, di luar scope solo dev). Search + sort
   by likes yang bantu user menemukan versi terbaik, pola Wikiloc.

5. **Fitur yang SENGAJA ditunda** (tabel sudah disiapkan, UI belum):
   - Like/Clap, Comment — butuh massa user dulu biar tidak terasa sepi
   - Segments ala Strava (deteksi overlap jalur, leaderboard) — SENGAJA
     TIDAK diambil sama sekali, di luar scope solo-dev-modal-nol
   - Surface type analysis ala Strava — butuh integrasi OpenStreetMap
     terpisah, di luar scope saat ini

6. **Prinsip "full open" — rute publik HARUS bisa didownload jadi GPX
   asli oleh user lain**, bukan cuma dilihat di dalam app. Beda dari
   Strava (cuma kasih `summary_polyline` ke publik) — Nyasar lebih ke
   arah Wikiloc: siapa saja bebas ambil & pakai jalur yang dipublish,
   di app manapun.
   - `track_polyline` (preview cepat, lat/lon saja, lossy) tetap
     dikirim seperti biasa, DITAMBAH file GPX asli (lengkap, presisi
     penuh) yang di-**gzip** lalu diupload ke **Supabase Storage**
     (kuota terpisah, 1GB gratis) — kolom `routes.gpx_file_url`. Gzip
     efektif karena GPX (XML) sangat repetitif: ~277 KB mentah jadi
     ~27 KB ter-gzip (~10x), cuma ~2x lebih besar dari `track_polyline`.
   - Alur publish: encode `track_polyline` DAN paralel generate GPX
     lengkap → gzip → upload Storage → simpan URL di `gpx_file_url`.
   - Alur download (belum ada UI): fetch `gpx_file_url` → decompress
     (`GZIPInputStream`, bawaan Kotlin) → file `.gpx` lossless.
   - `gpx_file_url` nullable (rute lama sebelum fitur ini belum tentu
     punya file-nya).
   - **Catatan:** navigasi rute publik di dalam app tetap jalan normal
     cuma pakai `track_polyline` (elevasi live dari GPS user sendiri).
     Yang hilang: `ElevationProfile` (grafik elevasi SEBELUM mulai
     jalan) butuh elevasi per titik yang cuma ada di `gpx_file_url`.

7. **`SportType.kt` sudah punya `UNSPECIFIED` sebagai default baru** —
   selesai & terverifikasi di kode (7 entry, default di 4 titik:
   RecordingService, ActivityEntity, RecordingScreen,
   SportType.fromString()). Tidak ada migration Room (activity lama
   tetap TRAIL_RUN, cuma activity baru yang defaultnya berubah).
   `schema_v1.sql` sudah sinkron dengan ini.

## Konsep Backup Pribadi (masih konsep, belum diimplementasi)

Fitur TERPISAH dari "Publish" (Keputusan poin 6) — jangan disamakan:

| | Publish | Backup Pribadi |
|---|---|---|
| Isi | Cuma activity yang user pilih share | SEMUA activity user, dipublish atau tidak |
| Sifat | Publik, orang lain bisa lihat & download | Privat, cuma pemiliknya sendiri |
| Tabel | `routes` | `activity_backups` |

**Kenapa perlu:** app 100% local-first — ganti HP/uninstall = SEMUA
histori (activity, waypoint, recording) hilang permanen kecuali pernah
dipublish. Orang biasanya ngasumsiin akun otomatis = backup, padahal
dua hal ini gak nyambung sama sekali di rencana awal.

**Kenapa bisa gratis:** setelah foto dihapus total, sisa data per
activity ringan banget dengan encoding yang tepat:

| Metode encode track (1 activity ~4 jam, 2.880 titik) | Ukuran | Lengkap? |
|---|---|---|
| File GPX mentah (XML) | ~277 KB | Ya |
| GPX di-gzip | ~27 KB | Ya |
| `track_polyline` (lat/lon doang, lossy) | ~14 KB | Tidak |
| **Delta-encode (selisih antar titik) + gzip** | **~9 KB** | **Ya, lossless** |

Delta-encode lebih kecil dari `track_polyline` yang lossy sekalipun
datanya lengkap — selisih antar titik GPS berdekatan itu angka kecil,
dan tidak ada overhead tag XML. Estimasi kuota: ~200 activity/user
seumur hidup × 9 KB ≈ 1.8 MB/user — kuota gratis Supabase muat ratusan
hingga ribuan user.

**Desain tabel (`activity_backups`, sudah ada di `schema_v1.sql`):**
- **Privat sepenuhnya** — RLS tanpa pengecualian publik sama sekali.
- Reuse akun Supabase Auth yang sama dengan Publish.
- `track_data bytea` — delta-encode + gzip (lihat tabel di atas).
- `waypoints_json jsonb` — waypoint terkait, disalin apa adanya.
- `local_route_id` — link activity ke RouteEntity lokal (kalau ada).
- Constraint: harus terkait `source_activity_id` ATAU `source_route_id`,
  tidak boleh dua-duanya kosong/isi.

**Alur (belum diimplementasi):**
1. Activity selesai direkam (atau tombol manual) → compress →
   upload/upsert ke `activity_backups`.
2. Ganti HP → login akun sama → fetch semua backup → decompress →
   masukin ke Room lokal.

## Skema Database (Supabase Postgres)

File acuan: **`schema_v1.sql`** (live, dipakai project Supabase yang
sebenarnya). 8 tabel: profiles, routes, waypoints, route_likes,
saved_routes, route_comments, reports, activity_backups. Semua pakai
Row Level Security (RLS). `activity_backups` beda dari tabel lain:
RLS-nya privat total, tanpa pengecualian publik.

Poin desain penting:
- `routes.track_polyline` — hasil encode dari
  `ActivityDao.getLatLonOnly()`, pakai Polyline Encoding standar
  (format Google Maps/Strava), bukan JSON array titik mentah.
- `routes.mountain_name`, `region`, `difficulty`, `difficulty_description`,
  `trail_type` — field BARU, tidak ada di ActivityEntity lokal, semua
  NULLABLE (opsional — Nyasar tidak khusus gunung saja, ada
  `sport_type` generik juga).
- `waypoints.category` dibatasi CHECK constraint sesuai
  `WaypointCategory.kt` (SUMMIT, WATER, SHELTER, CAMPSITE, DANGER,
  PARKING, POI, CUSTOM).
- `saved_routes` terpisah dari `route_likes` — Save = bookmark
  personal, Like/Clap = apresiasi publik.
- `routes.gpx_file_url` & `activity_backups.track_data` — dua cara
  encode BEDA (gzip-atas-XML vs delta-encode+gzip), lihat Keputusan
  poin 6 dan Konsep Backup Pribadi di atas untuk alasannya.
- Trigger `handle_new_user` (jalan di Supabase, bukan di app) —
  auto-bikin row `profiles` saat user daftar, username sementara =
  `split_part(email,'@',1) || '_' || substr(id::text,1,6)`. Ini
  SENGAJA jelek/sementara — app WAJIB nampilin layar "Pilih Username"
  yang non-skippable sebelum user boleh publish/like/comment apa pun
  (lihat status bug terkait ini di bagian Status & Roadmap). Catatan
  dari audit Login Google: `split_part(new.email,...)` tanpa guard
  berarti user OAuth tanpa email bikin insert gagal — guard opsional
  NULL-safe tersedia di `supabase/README.md`.
- `profiles.username_is_set` (boolean) — penanda apakah user sudah
  benar-benar pilih username sendiri lewat `ChooseUsernameScreen`,
  dipakai sebagai dasar gate (bukan cara sesi terbentuk). Ditambahkan
  belakangan lewat migrasi `ALTER TABLE` — **sudah dijalankan user di
  Supabase SQL Editor**, sudah live.

## Catatan Teknis Penting (untuk nulis kode Publish nanti)

Sudah dicek langsung ke source code (`ActivityEntity.kt`,
`RouteEntity.kt`, `WaypointEntity.kt`, `ActivityDao.kt`,
`ActivityDetailViewModel.kt`), bukan dikarang dari nol:

1. **`track_polyline` punya DUA jalur sumber, bukan satu.** Publish
   dari `ActivityEntity` → titik GPS dari
   `ActivityDao.getLatLonOnly()`. Publish dari `RouteEntity`/GPX
   import → `RouteEntity` tidak menyimpan titik GPS sama sekali (cuma
   `localGpxFilePath`), harus parse ulang lewat `GpxParser.kt`.
   `PolylineEncoder.kt` (roadmap) wajib menerima DUA jalur input ini.

2. **`max_elevation_m`/`min_elevation_m` di `ActivityEntity` dihitung
   on-the-fly** (lihat `elevationSummary` di
   `ActivityDetailViewModel.kt`, dari `ActivityPointEntity`) — bukan
   kolom tersimpan. Reuse logic ini saat publish, jangan tulis ulang.
   `RouteEntity` (GPX import) sudah punya field ini langsung
   (`highestElevationM`/`lowestElevationM`), tinggal disalin.

3. **`moving_time_ms`, `elapsed_time_ms`, `avg_speed_kmh`,
   `max_speed_kmh` hanya ada di `ActivityEntity`, tidak ada di
   `RouteEntity`.** Publish dari GPX import akan selalu NULL di 4
   kolom ini — perilaku benar (asimetri wajar), bukan bug.

## Status & Roadmap

App-nya dibagi jadi beberapa fase kerja. Fase 1 (Akun) masih ada sisa
item, BUKAN berarti harus semua beres dulu sebelum lanjut Fase 2 —
lihat prioritas di bawah tiap fase.

### Fase 0 — App Inti (lokal, offline-first)
✅ Selesai. Rekam GPS, import/export GPX, peta offline, waypoint,
fitur foto sudah dihapus total, `SportType.UNSPECIFIED`.

### Fase 1 — Akun (Auth)
Supabase Kotlin SDK terpasang (`supabase-kt` BOM 2.2.2, Kotlin tetap
1.9.24), kredensial dari `local.properties` via `BuildConfig`
(`SUPABASE_URL`, `SUPABASE_PUBLISHABLE_KEY` — bukan `SUPABASE_ANON_KEY`
seperti draft lama). Daftar/Login/Logout jalan.

- ✅ Skema (`schema_v1.sql`) + RLS — siap, live
- ✅ **Bug "halaman Pilih Username keskip" — SUDAH DIKONFIRMASI selesai**
  (dicek langsung ke source `nyasar-main__62_.zip`: `AuthRepository.kt`
  punya `getProfileStatus()`, `getUsername()` sudah tidak ada lagi;
  `AuthViewModel.applyAuthenticated` gate dari `profiles.username_is_set`,
  bukan `SessionSource`). Kronologi & alasan fix tetap dicatat di bawah
  untuk konteks, tapi statusnya sudah selesai, bukan lagi ⏳.
  Kronologi: gate lama nentuin "perlu munculin Pilih Username apa
  nggak" dari `SessionSource` (SignUp vs SignIn). Kalau project
  Supabase mewajibkan konfirmasi email, `signUp()` tidak langsung dapat
  session — sesi pertama yang beneran aktif justru dari SignIn biasa
  (setelah user klik link email lalu login manual), yang sengaja TIDAK
  di-gate (biar user lama gak disuruh pilih ulang). Akibatnya user baru
  ikut lolos gate, tetap pakai username auto-generated dari trigger.
  Sempat dites ulang user dan MASIH kejadian di build lama.
  - Fix: gate diputuskan dari `profiles.username_is_set` (data), bukan
    `SessionSource` (cara sesi terbentuk) — benar di kedua kondisi
    dashboard. `AuthRepository.getProfileStatus()` (gantiin
    `getUsername()`) + `AuthViewModel.applyAuthenticated` dirombak
    sesuai ini.
  - ✅ Migrasi SQL kolom `username_is_set` — sudah dijalankan user.
  - ✅ Perubahan kode (`AuthRepository.kt`, `AuthViewModel.kt`) — sudah
    terverifikasi masuk, lihat konfirmasi di atas.
- ✅ **Hapus Akun** — kode selesai (commit `e65761c`). Karena app sengaja
  TIDAK punya service-role key, deleternya jalan lewat RPC Postgres
  `security definer` `delete_own_account()` yang cuma menghapus
  `auth.users` milik `auth.uid()` sendiri (semua tabel lain cascade dari
  auth.users — terverifikasi ke schema_v1.sql). SQL migration-nya ada di
  `supabase/migrations/0002_delete_own_account.sql` — **⏳ WAJIB dijalankan
  manual di Supabase SQL Editor** (instruksi + verifikasi di
  `supabase/README.md`); sebelum dijalankan, app menampilkan pesan khusus
  "migration belum dijalankan" saat tombolnya dipakai. UI: row "Kelola
  Akun" di Settings (signed-in) → bottom sheet; hapus akun minta ketik
  kata konfirmasi (HAPUS/DELETE sesuai locale) dan jujur menyatakan cuma
  data CLOUD yang hilang — data Room lokal di HP TIDAK disentuh (keputusan
  UX terpisah, disengaja).
- ✅ **Ganti Password** (dari dalam app, beda dari "lupa password") —
  kode selesai (commit `ec6a633`). WAJIB re-auth dulu: password lama
  diverifikasi via `signInWith(Email)` fresh sebelum `modifyUser`
  (`gotrue-kt 2.2.2` tidak punya `updateUser` — nama API-nya
  `modifyUser`, diverifikasi dari source artifact, BUKAN dari contoh
  versi lain). Validasi password baru reuse aturan signUp yang ada
  (min 6 karakter), bukan aturan baru.
- ✅ **Ganti Email** — kode selesai (commit `ec6a633`), re-auth password
  wajib juga. Hasilnya disajikan JUJUR: kalau GoTrue meng-stage perubahan
  (`new_email`/`email_change_sent_at` terisi = project mewajibkan
  konfirmasi), UI bilang "konfirmasi dikirim ke email BARU", bukan
  "email berhasil diganti" — tidak ada klaim instan. Email hanya di
  `auth.users`; `profiles` tidak punya kolom email (terverifikasi ke
  schema_v1.sql) jadi tidak ada sinkronisasi lain.
- ✅ **Login Google** (sisi kode) — commit `af9551a`. TERNYATA beda dari
  dugaan prompt: gotrue-kt 2.2.2 TIDAK punya alur browser-redirect +
  deeplink di Android — Google dimodelkan sebagai `IDTokenProvider`.
  Jadi flow-nya: AndroidX Credential Manager (`GetGoogleIdOption`)
  minta Google ID token → ditukar session lewat
  `signInWith(IDToken) { idToken; provider = Google }`. Tidak perlu
  intent-filter/manifest change di line SDK ini. Client ID (WEB) dibaca
  dari `local.properties` → `BuildConfig.GOOGLE_OAUTH_WEB_CLIENT_ID`;
  kosong = tombol Google disembunyikan (graceful, tanpa hardcode).
  User baru dari Google tetap masuk gate "Pilih Username" yang sudah
  ada (`profiles.username_is_set`) karena sessionStatus-nya sama.
  - ⏳ Yang HARUS dikerjakan user sendiri (tidak mungkin diverifikasi
    tanpa ini): buat OAuth client (type **Web application**) di Google
    Cloud Console, aktifkan provider Google di Supabase Dashboard →
    Auth → Providers → Google dengan client id tsb, lalu isi
    `GOOGLE_OAUTH_WEB_CLIENT_ID` di local.properties + GitHub Secrets
    (workflow CI belum menulis secret ini — tambahkan baris echo-nya
    kalau mau ke-cover di CI).
  - Edge case OAuth: trigger `handle_new_user` live memakai
    `split_part(new.email,...)` tanpa guard — user tanpa email (sah di
    OAuth) bikin signup gagal. Guard opsional NULL-safe ada di
    `supabase/README.md`, disarankan dijalankan bareng migrasi 0002.

**Keputusan (update sesi ini): user memilih menyelesaikan SEMUA 4 item
di atas sekaligus sebagai satu paket, sebelum lanjut ke Fase 2** — bukan
lagi diprioritaskan/ditunda satu-satu seperti draft sebelumnya.

### Fase 2 — Publish / Share Rute Publik
❌ Belum mulai.
1. `PolylineEncoder.kt` — terima DUA jalur input (lihat Catatan
   Teknis poin 1): `ActivityDao.getLatLonOnly()` DAN hasil parse
   `GpxParser`.
2. Form "Publish Route" — ambil data ActivityEntity selesai, minta
   user isi mountain_name/region/difficulty/trail_type manual, kirim
   ke `routes` (+ waypoint terkait).
3. Layar search/browse rute publik.
4. Alur generate GPX lengkap + gzip + upload Storage saat publish,
   simpan URL ke `gpx_file_url`. Butuh bikin bucket Storage di
   Supabase + fungsi gzip (`GZIPOutputStream`, bawaan Kotlin).
5. Tombol "Download GPX" di layar detail rute — fetch `gpx_file_url`,
   decompress (`GZIPInputStream`), simpan `.gpx` lokal atau share-intent.

### Fase 3 — Backup Pribadi
❌ Belum mulai, masih sebatas konsep (lihat bagian di atas).
1. `DeltaEncoder.kt` — encode `List<ActivityPointEntity>` jadi
   delta-encoded + gzip bytes, dan fungsi decode kebalikannya.
2. Alur backup (auto setelah activity selesai dan/atau tombol manual)
   → upsert ke `activity_backups`.
3. Alur restore (saat login pertama di HP baru dan/atau tombol manual)
   → fetch semua backup milik user → decode → insert ke Room lokal.

### Fase 4 — Sosial
❌ Tabel sudah siap (`route_likes`, `saved_routes`, `route_comments`,
`reports`), UI belum dibikin sama sekali: tombol Save, Like, Comment,
Report.

### Belum kepikiran desainnya sama sekali (bukan cuma belum dikerjakan)
- **Antrian upload Publish/Backup saat tidak ada sinyal** — konteks
  app ini adalah pendakian gunung, biasanya TIDAK ada koneksi internet
  pas activity baru selesai. Desain Publish/Backup saat ini asumsikan
  upload bisa langsung jalan — belum ada rencana retry-queue, kapan
  upload sebenarnya terjadi, atau UI "menunggu sinyal" buat user.

## Ide Masa Depan (BELUM masuk skema, sengaja ditunda)

Dicatat biar tidak lupa/ditemukan ulang dari nol, TIDAK dibuatkan
tabelnya sekarang — sama alasannya dengan like/comment: butuh massa
user dulu baru terasa berguna.

- **Trail Buddies** (ala fitur Wikiloc "Add trail buddies") — tag user
  lain yang ikut dalam satu aktivitas/rute yang sama. Beda dari
  like/saved_routes (interaksi terpisah setelah publish): ini melekat
  ke data rute sejak dibuat. Kalau dikerjakan, butuh TABEL BARU (bukan
  cuma kolom), misal `route_buddies` (route_id, user_id) — relasi
  many-to-many, bukan array/kolom di `routes`. Butuh juga UI
  pencarian/pilih user saat publish, idealnya notifikasi ke user ditag.

## Prinsip Kerja yang Disepakati

- Solo developer, modal NOL — semua solusi harus gratis tanpa kartu
  kredit (Supabase free tier, Cloudflare R2 kalau nanti perlu storage
  tambahan, dst).
- JANGAN mencoba menyamai semua fitur Wikiloc/Strava sekaligus — app
  ini "Wikiloc versi ringan khusus Indonesia, fokus gunung/hiking,
  tanpa foto, tanpa fitur sosial berat".
- Setiap kali menganalisis fitur app pembanding (Wikiloc/Strava/Avenza),
  SELALU cek dulu apakah field/datanya sudah ada di struktur lokal
  (Room entities) sebelum menambah ke skema cloud — jangan mengarang
  skema dari nol tanpa mengecek source code dulu (kesalahan yang
  pernah terjadi di sesi sebelumnya).
- Development itu iteratif — skema akan terus berkembang, ini normal.
  Jangan mengejar "skema sempurna dari awal".
- Bedakan jelas fitur PUBLIK (`routes` dkk, bisa dilihat user lain) vs
  PRIVAT (`activity_backups`, cuma pemiliknya sendiri). Jangan campur
  logika atau RLS antara keduanya.
- Kalau ada perubahan yang dieksekusi di LUAR chat ini (misal lewat AI
  coding tool terpisah), jangan anggap selesai sampai ada konfirmasi
  balik (commit masuk, CI hijau, atau laporan hasil) — chat ini tidak
  bisa melihat state repo/Supabase secara langsung.
