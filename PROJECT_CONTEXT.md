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
- ✅ **Login Google** (kode + konfigurasi manual, SELESAI TOTAL) — commit
  `af9551a` (kode). TERNYATA beda dari dugaan prompt: gotrue-kt 2.2.2
  TIDAK punya alur browser-redirect + deeplink di Android — Google
  dimodelkan sebagai `IDTokenProvider`. Jadi flow-nya: AndroidX
  Credential Manager (`GetGoogleIdOption`) minta Google ID token →
  ditukar session lewat `signInWith(IDToken) { idToken; provider =
  Google }`. Tidak perlu intent-filter/manifest change di line SDK ini.
  Client ID (WEB) dibaca dari `local.properties`/GitHub Secrets →
  `BuildConfig.GOOGLE_OAUTH_WEB_CLIENT_ID`; kosong = tombol Google
  disembunyikan (graceful, tanpa hardcode). User baru dari Google tetap
  masuk gate "Pilih Username" yang sudah ada
  (`profiles.username_is_set`) karena sessionStatus-nya sama.
  - ✅ Konfigurasi manual selesai: OAuth client **Web application**
    dibuat di Google Cloud Console (dipakai di
    `GOOGLE_OAUTH_WEB_CLIENT_ID`), OAuth client **Android** terpisah
    juga dibuat (package `com.nyasar.app` + SHA-1, dipakai Google buat
    validasi identitas APK — ID-nya sendiri tidak dipakai di kode
    manapun). Provider Google diaktifkan di Supabase Dashboard → Auth →
    Providers → Google dengan Client ID (Web) + Client Secret terisi.
    `GOOGLE_OAUTH_WEB_CLIENT_ID` sudah jadi GitHub Secret dan dibaca
    workflow `build.yaml`/`release.yaml`. Login end-to-end sudah
    ditest berhasil di device (akun Google berhasil dipilih & masuk).
  - ✅ **Fix: SHA-1 APK debug dari CI sekarang permanen.** Awalnya
    `build.yaml` menandatangani APK debug pakai `debug.keystore`
    auto-generate Gradle — runner `ubuntu-latest` itu VM sekali pakai
    tanpa state persisten, jadi keystore itu dibuat ULANG tiap run dan
    SHA-1-nya berubah tiap build, bikin Google Sign-In selalu
    `CANCELED`. Solusi: `build.yaml` sekarang decode
    `KEYSTORE_BASE64` (keystore **release**, sama seperti
    `release.yaml`) lalu **zipalign + apksigner re-sign** APK debug
    dengan keystore itu setelah `assembleDebug`. Hasilnya APK yang
    diupload tetap APK debug biasa (langsung install-able), tapi
    SHA-1-nya sekarang tetap/permanen selamanya (sama dengan yang
    dipakai rilis Play Store nanti) — didaftarkan sekali ke OAuth
    client Android, tidak perlu diulang tiap CI run.
  - ⚠️ **Jebakan ditemukan:** field `GOOGLE_OAUTH_WEB_CLIENT_ID`
    (baik di GitHub Secret maupun kolom "Client IDs" Supabase) sempat
    ke-isi dengan prefix `https://` di depan Client ID (Client ID
    Google BUKAN URL, format aslinya `xxxx.apps.googleusercontent.com`
    polos). Kemungkinan besar tertular dari placeholder salah di
    `local.properties.example` (`GOOGLE_OAUTH_WEB_CLIENT_ID=https://
    your-project-ref.supabase.co` — itu placeholder utk field lain yg
    ketuker). Ini sebab utama Sign-In gagal total sebelum SHA-1 pun
    dicek. **⏳ TODO kecil: perbaiki placeholder di
    `local.properties.example` biar tidak jadi jebakan yang sama di
    masa depan / buat siapa saja yang clone project.**
  - Edge case OAuth: trigger `handle_new_user` live memakai
    `split_part(new.email,...)` tanpa guard — user tanpa email (sah di
    OAuth) bikin signup gagal. Guard opsional NULL-safe ada di
    `supabase/README.md`, disarankan dijalankan bareng migrasi 0002.

**Status: SEMUA 4 item paket Fase 1 (Hapus Akun, Ganti Password, Ganti
Email, Login Google — termasuk konfigurasi manualnya) sudah SELESAI
TOTAL.** Siap lanjut ke Fase 2.



### Fase 2 — Publish / Share Rute Publik
🟢 Slice 1 (pipeline publish) + Slice 2 (browse/detail/Download GPX,
sekalian rework IA bottom bar) + sisa terakhir (publish-dari-file)
SELESAI. Publish-dari-file dieksekusi 2026-09-17: `PublishLibraryRouteSheet`
di Route Preview (Library) — tombol CloudUpload di top bar, sheet form
identik dengan publish activity (komponen bersama `PublishFormSheet`);
`PublishRepository.publishRoute()` upload file GPX ASLI rute verbatim
(bukan regenerasi) + row `routes` dengan `source_route_id` (unique index
`idx_routes_source_route` sudah disiapkan schema sejak awal);
`RouteInsertRow` kolom waktu/speed jadi nullable (GPX import sering
tanpa timestamp — schema memang nullable, Browse DTO sudah nullable).
Cache publish per rute tidak dibuat: unique index + rollback upload
gagal sudah mencegah duplikat; user boleh publish ulang setelah hapus
route publiknya.

**Rework IA 2026 — sudah dieksekusi (commit `84a0ec7`, perbaikan CI
sampai `feec4ee`, CI hijau): bottom bar 5 tab — Browse | Map | Record |
Library | Profile.** Browse = Fase 2 poin 3, sekarang START DESTINATION
app. Map = HomeScreen lama (route string `"home"` SENGAJA tidak berubah
supaya semua call site `navigate("home")` tetap valid). Record & Library
tidak berubah. History + Saved digabung di tab Profile (`"profile"`
di bottom bar + nested `"profile?tab="` dengan header back-arrow;
route `"history"`/`"settings"` tetap terdaftar sebagai sub-destinasi).
Rev3 (Wikiloc-style): di ATAS sub-tab (di atas TabRow) ada kartu
identitas — avatar lingkaran inisial username + nama + email. Tap →
route `account` (LAYAR AKUN BARU, ui/profile/AccountScreen.kt):
hero profil + "bergabung sejak" (profiles.created_at, via
ProfileStatus.createdAt), edit username (live availability check,
updateProfileUsername — bukan confirmUsername-gate), logout, dan
sheet kelola akun (password/email/hapus — AccountManageSheet dipindah
dari Settings, kini internal di ui/settings). Kalau belum login,
kartu jadi ajakan masuk (tap → auth/login). Settings (gear pojok
kanan-atas) TIDAK lagi punya section Akun — onOpenAccount dihapus
dari SettingsScreen/SettingsContent. SignedIn memberSince: String?
ditambah ke session state (dari getProfileStatus).
Gate auth pasca daftar/login mendarat ke Browse, bukan Home.

1. ✅ `publish/PolylineEncoder.kt` — encode standar Google/Strava +
   decode, round-trip ter-unit-test (`PolylineEncoderTest`, dijalankan
   CI via step baru `testDebugUnitTest`). Input GpxParser terpakai di
   publish-dari-file (lihat catatan 🟢 di atas).
2. ✅ Form "Publish Route" — `PublishRouteSheet` dari menu Activity
   Detail: mountain_name/region/difficulty(+catatan)/trail_type/
   deskripsi, FilterChips, notice transparansi (ringkasan jalur + GPX
   terkompresi dari N titik; titik mentah TIDAK pernah dikirim),
   strings lengkap ID/EN.
3. ✅ Layar search/browse rute publik — `BrowseScreen`/`BrowseViewModel`/
   `BrowseRepository`: search by NAMA rute (debounce 350ms, server-side
   ilike), filter difficulty & trail_type (wire value = CHECK constraint
   schema, label REUSE string form publish — tidak ada vocab ganda),
   sort terbaru/terbanyak-disukai/jarak, kartu rute dengan mini-preview
   polyline (Canvas murni, tanpa tile map). Sengaja TIDAK menyentuh
   `mountain_name`/`region` — app tetap jalan baik SEBELUM maupun
   SESUDAH migration 0004 dijalankan (lihat catatan keputusan di bawah).
4. ✅ Pipeline generate GPX lengkap + gzip + upload Storage saat
   publish — `PublishRepository.publish()` (insert `routes` RETURNING
   id → GPX via `GpxExporter` → gzip → Storage `route-gpx` → backfill
   `gpx_file_url`; rollback row kalau upload gagal). **⏳ Migration
   bucket: `supabase/migrations/0003_route_gpx_bucket.sql` WAJIB
   dijalankan manual di Supabase SQL Editor** (buat bucket publik
   `route-gpx` + kebijakan insert/select user; sama seperti pola
   migration 0002 sebelumnya).
5. ✅ Tombol "Download GPX" di layar detail rute publik
   (`PublicRouteDetailScreen`) — fetch `gpx_file_url`, decompress
   (`GZIPInputStream`), tulis ke cache exports, share-intent FileProvider
   (jalur sama dengan export GPX P3G). Detail juga menampilkan publisher
   (join `profiles(username)`), stats, dan preview track dari
   `track_polyline` (lossy — elevasi per titik tetap hanya ada di GPX).

**⏳ Keputusan baru (belum dieksekusi) — hapus `mountain_name` DAN
`region` dari tabel `routes`, disengaja, bukan cuma rename:**
- `mountain_name`: user merasa field ini duplikasi — activity yang
  direkam sudah punya nama sendiri (diisi user saat record), jadi
  menanyakan "nama gunung" terpisah lagi saat publish terasa
  dipaksakan, apalagi untuk activity yang bukan pendakian gunung
  (trail run, sepeda, dll). Alternatif rename ke `location_name` juga
  sempat dibahas tapi TIDAK dipilih — user pilih hapus total karena
  nama activity yang sudah ada dianggap sudah cukup.
- `region`: user merasa field ini tidak penting/tidak akan dipakai.
- **Konsekuensi kalau dieksekusi (perlu ditangani semua sekaligus,
  bukan cuma migration SQL-nya):**
  - Migration baru (mis. `0004_drop_mountain_name_and_region.sql`) —
    `alter table routes drop column mountain_name;` +
    `alter table routes drop column region;` + drop index
    `idx_routes_mountain` dan `idx_routes_region` yang menempel ke
    dua kolom itu. **DROP COLUMN, bukan rename** — data yang sudah
    terlanjur terisi di dua kolom itu (termasuk row hasil publish
    yang sudah ditest berhasil sebelum keputusan ini) akan hilang
    permanen begitu migration dijalankan; row activity/GPX-nya
    sendiri TIDAK ikut hilang, cuma dua kolom itu.
  - `PublishRouteSheet.kt` + `PublishViewModel.kt` — hapus field
    input mountain_name & region dari form (dan FilterChips/strings
    ID-EN terkait kalau ada). — ✅ SUDAH (commit `5ff4b6b`, CI hijau
    run 35061871060/35061871052): form publish kini cuma
    difficulty(+catatan)/trail_type/deskripsi; strings
    `publish_mountain_label`/`publish_region_label` dihapus ID/EN.
  - `PublishRepository.kt` — hapus dua kolom itu dari payload insert
    ke `routes`. — ✅ SUDAH (commit yang sama).
  - File migration final: `supabase/migrations/0004_drop_mountain_name_and_region.sql`
    (drop 2 index dulu, lalu drop 2 kolom, semua `if exists`).
  - Kalau nanti browse/search screen (poin 3 di atas) dikerjakan
    SETELAH migration ini, JANGAN pakai `mountain_name`/`region`
    sebagai filter (lihat draft rencana filter di bawah). — SUDAH
    DIPATUHI: browse/detail/search tidak mereferensikan dua kolom itu
    sama sekali (slice browse dikerjakan SEBELUM migration jalan,
    sesuai urutan wajib "kode dulu" di atas; migration 0004-nya sendiri
    masih ⏳ menunggu dijalankan manual user — lihat tabel di
    `supabase/README.md`).
  - **Urutan eksekusi wajib: kode diubah dulu, migration SQL
    dijalankan belakangan** — kalau migration jalan duluan sebelum
    kode diupdate, app akan crash saat publish (insert ke kolom yang
    sudah tidak ada).

**Rencana filter browse/search (poin 3) — SUDAH DIIMPLEMENTASI
(2026-09-17, Filters sheet ala Wikiloc dari screenshot user):**
`BrowseRepository.BrowseFilters` (semua constraint SERVER-SIDE:
`isIn` difficulty & sport_type multi-select, `gte/lte` range jarak &
gain, `trail_type='loop'` untuk loop-only) + `BrowseFilterSheet`
(draft-until-Apply: edit di sheet tidak network sebelum tombol
Terapkan; swipe-dismiss dengan edit pending ikut di-commit — intent
user; back/X menyimpan draft). Slider ceiling (+200 km / +2.000 m) =
bound TERBUKA (tidak ada lte dikirim), persis konvensi "+200 km"
Wikiloc. Elevation gain STRICT: rute tanpa data gain (NULL) tidak
ikut muncul saat filter gain aktif — gain tak-dikenal tidak boleh
diam-diam memenuhi range (semantik SQL null, diverifikasi live ke
PostgREST). Badge tombol Filter = jumlah APPLIED (StateFlow), bukan
draft, supaya jujur saat sheet terbuka. PREMIUM rows Wikiloc
("authors you follow", "Recorded") TIDAK direproduksi — sesuai
keputusan di bawah. Sort tetap chip instan di luar sheet.

Riwayat keputusan (draft asli, hasil diskusi user membandingkan
Wikiloc/Trailforks, sengaja TIDAK meniru semua fiturnya):
- Dipakai: sport type (dari `SportType.kt` yang sudah ada — BUKAN
  puluhan kategori ala Trailforks), difficulty (sudah ada di schema),
  distance range (sudah ada di data), loop-trails-only (dari
  `trail_type`, bisa menyusul).
- SENGAJA tidak dipakai: "select activities" bergaya Trailforks
  (puluhan kategori granular seperti Alpine Climbing/Plogging/
  Bikepacking — kejauhan dari `SportType.kt` yang ringkas), "only
  authors you follow" (butuh sistem follow yang belum ada sama
  sekali, juga fitur sosial Fase 4 yang sengaja ditunda), filter
  "Recorded: last 30 days/3 months/dst" (fitur Premium di app
  pembanding, tidak relevan untuk Nyasar).
- Region TIDAK masuk rencana filter (menyusul keputusan hapus kolom
  di atas).

Catatan Slice 1 (bugfix saat CI: `f9fd0aa`): langkah CI
`testDebugUnitTest` yang baru ternyata pertama kali benar-benar
menjalankan suite unit test repo — 3 bug laten ketahuan dan sudah
diperbaiki: (1) `PolylineEncoder.encodeDelta` tidak menambahkan offset
ASCII +63 (output tak terbaca decoder eksternal); (2)
`OffRouteDetector` menelan status WARNING selama counter consecutive
belum penuh — sekarang satu reading jauh tetap WARNING, OFF_ROUTE
butuh N consecutive seperti spek; (3) `RecordingEngine` mengukur delta
elevasi dari fix mentah sebelumnya sehingga tick kecil di bawah noise
floor menggeser baseline — sekarang pakai baseline-hysteresis yang
sama dengan `ElevationStats.summarize` (gain/loss activity konsisten
dengan angka GPX). Test vector canonical polyline juga dikoreksi ke
`_mqNvxq`@` sesuai tabel resmi Google (sebelumnya salah tulis `_mN`).

Catatan Slice 2 (bugfix detail rute publik, 2026-09-16): layar detail
rute publik selalu "Something went wrong — try again" sejak commit
`84a0ec7` (belum pernah jalan sama sekali — BUKAN regression migration
0003/0004, keduanya bersih, diverifikasi langsung via curl). Root
cause: embed `profiles(username)` AMBIGU — `routes` punya TIGA relasi
ke `profiles` (FK langsung `routes_user_id_fkey` + many-to-many via
`route_likes` dan `saved_routes`), PostgREST menolak menebak dengan
PGRST201 "Could not embed because more than one relationship was
found" (HTTP 300), dan supabase-kt 2.2.2 menelannya jadi
`UnknownRestException: Unknown error` sehingga logcat lama tidak
pernah menunjukkan body error aslinya. Fix: select detail memakai FK
hint eksplisit `profiles!routes_user_id_fkey(username)` — diverifikasi
langsung ke REST project: HTTP 200 + username publisher ter-embed.
Komentar lama di `BrowseRepository.detail()` yang menyalahkan
PGRST100/whitespace dikoreksi — klaim itu tidak pernah benar.
Pelajaran audit: kalau `RestException`-nya cuma "Unknown", reproduce
request-nya langsung ke PostgREST (curl dengan apikey publishable)
untuk melihat kode PGRST* asli sebelum menebak-nebak penyebab.

Upgrade visual kartu Browse (2026-09-16, hasil diskusi screenshot
Wikiloc vs Strava): keputusan arah — **struktur data ala Wikiloc,
bahasa visual ala peta Strava, foto TETAP dihapus** (Keputusan poin 1
tidak dibuka ulang). Kartu `PublicRouteCard` sekarang: ikon sport +
difficulty chip warna pastel tetap per level (ala Wikiloc) + trail-type
chip netral, nama, stats row (distance/elevation+/durasi/likes), lalu
hero preview peta statis (`StaticMapPreview`): 2-5 tile raster topo
(MapTiler `topo` kalau MAPTILER_API_KEY ada, else OpenTopoMap keyless —
sumber sama dengan katalog basemap app) + trace rute dengan casing
gelap + titik start/end, username publisher sebagai pill di atas peta
(embed `profiles!routes_user_id_fkey(username)` juga di LIST query —
terverifikasi live: hint FK wajib juga di list, ambigu tanpa itu).
Fallback: tile gagal / tanpa sinyal / loading → canvas polyline polos
(look lama) — kartu tidak pernah error/blocking, fetch dibatalkan saat
kartu keluar viewport, LruCache 12MB (by byteCount) mencegah refetch
saat fling. List `PublicRoute` sekarang nested `profiles` object +
computed `username` (nullable — publisher terhapus tetap tampil tanpa
pill). Tidak ada string baru: semua label reuse (publish_difficulty_*,
publish_trail_*, sport_*, dll).

### Fase 3 — Backup Pribadi
🟡 SLICE 1 SELESAI (2026-09-17): pipeline encode→upsert→restore +
auto-backup + UI Settings. Yaitu:
1. ✅ `backup/DeltaEncoder.kt` — delta-encoded + gzip codec untuk
   `List<ActivityPointEntity>` → `activity_backups.track_data`:
   magic 'NY' + version byte, timestamp base absolut di header, varint
   zigzag per-field (dt/lat 1e-6°/lon/elevasi cm delta-ke-nonnull-
   terakhir/speed cm-s), mask bit 2-bit/titik untuk null elevation &
   speed, akurasi ushort 0.1 m. Decode menolak input rusak dengan
   `FormatException` (bukan IOOBE). Unit test CI (`DeltaEncoderTest`):
   roundtrip kosong/1 titik/1000 titik realistis/batas grup 8, dan
   assert kompresi < 8KB untuk track 1000 titik. Edge KUNCI yang sudah
   dites: base time dobel-hitung (delta titik pertama harus relatif ke
   header, bukan absolut — kacatch saat self-review, ada komentar di
   encoder).
2. ✅ Alur backup — `backup/BackupManager` (orkestrasi) +
   `data/supabase/BackupRepository` (jaringan, pola Outcome): row id
   STABIL per sumber (probe `fetchRowIdFor` → UUID baru kalau belum
   ada) sehingga re-backup UPSERT baris yang sama — indeks unik di
   schema PARTIAL sehingga `on_conflict` kolom lain tidak bisa dipakai
   (terdokumentasi). **AUTO** setelah activity selesai: hook di
   `RecordingService.handleStop` (capture id sebelum persistSummary
   me-null-kan, scope milik BackupManager sendiri — BUKAN serviceScope
   yang dibatalkan onDestroy) + path recovery crash
   (`stopAndSaveRecovered`). **MANUAL**: "Backup sekarang" di Settings
   (section Backup & Pulihkan, hanya muncul saat SignedIn; Toast hasil
   `X berhasil, Y dilewati/gagal`; 1 sumber gagal tidak menggagalkan
   batch).
3. ✅ Alur restore — tombol "Pulihkan dari backup" di Settings:
   fetch semua row (RLS membatasi milik user) → decode → insert Room
   dalam transaksi, pass 1 routes dulu lalu pass 2 activities (agar
   `local_route_id` tidak dangle). ID ASLI dipertahankan (route/
   activity/waypoint) supaya keterkaitan tetap utuh. Merge-skip:
   sumber yang sudah ada lokal dilewati — TIDAK PERNAH menimpa data
   lokal. Route direkonstruksi PENUH: file GPX ditulis ulang ke
   files/routes/{id}.gpx + stats dihitung ulang (rumus sama dengan
   import) — jadi preview/navigasi/offline jalan normal. Waypoint
   backup sebagai jsonb array di `waypoints_json` (ikut row, tanpa
   query kedua).
4. ✅ **TEMUAN PENTING saat audit (diverifikasi live ke PostgREST):
   bytea HARUS dikirim sebagai HEX `\x…`, BUKAN base64.** Kontrol:
   `\xZZ` ditolak `400 invalid hexadecimal` SEBELUM RLS, sedangkan
   string base64 tanpa prefix lolos parse sebagai escape-format bytes
   (ASCII literal base64-nya) → korupsi diam-diam tanpa error.
   `BackupRepository.byteaHexEncode/Decode` menangani ini; decode
   toleran prefix hilang tapi tidak pernah menerima base64.

Sengaja TIDAK ada (menunggu keputusan antrian upload, lihat bagian
"Belum kepikiran" di bawah): retry-queue offline, UI "menunggu
sinyal", auto-backup berkala latar. Auto-backup yang gagal karena
sinyal cuma log info; tombol manual adalah jalur retry.

TODO schema terjawab: `discarded` tidak pernah ditulis ke DB
(discard = hapus langsung), jadi backup semua status adalah perilaku
yang benar.

### Fase 4 — Sosial
✅ **SELESAI (2026-09-18): Like + Comment + Save/bookmark + Report +
hapus komentar sendiri — semua fitur sosial skema_v1 kini ber-
ujung UI.** (Detail per slice di bawah.)
🟢 **Slice 1 (2026-09-17): Like + Comment di Browse & Route Detail.**
- Kartu Browse & detail rute dirombak urutan Strava: header publisher
  (avatar + username + waktu relatif) → nama → deskripsi → stats 3 kolom
  (label di atas, nilai tebal) → map full-bleed lebih tinggi (210/240dp) →
  baris aksi Like/Komentar/Share.
- Backend: `SocialRepository` baru — toggleLike (read-state-then-write,
  anti-race), fetchComments (embed profiles via FK hint
  `route_comments_user_id_fkey`), postComment (insert-with-select balik
  row lengkap). RLS schema_v1 sudah menutup semua — TIDAK ada policy baru.
- Migration `0005_like_comment_counters.sql`: trigger AFTER INSERT/DELETE
  menjaga `routes.likes_count`/`comments_count` (browse ORDER BY butuh
  kolom tersimpan), plus backfill. **MIGRATION INI HARUS DIJALANKAN DI
  SUPABASE sebelum fitur like/komen terasa benar** (counter di UI update
  lokal optimistik, tapi angka server tetap 0 tanpa trigger).
- Like bersifat sinyal sosial publik — bukan Save (Save = simpan ke
  Library lokal, sudah ada terpisah). Comment belum bisa dihapus dari UI
  (RLS delete own sudah siap, UI menyusul).
- Share: plain-text link `https://nyasar.app/route/{id}` via chooser
  sistem; kartu browse pakai helper internal, detail pakai callback
  ke MainActivity (`shareText`).
- 🟢 **SISA FASE 4 SELESAI (2026-09-18): Save/bookmark + Report + hapus
  komentar sendiri.** Semua client-side — schema_v1 sudah menyiapkan
  tabel + RLS sejak awal, NOL migration baru:
  - `SocialRepository` nambah: `toggleSave` (saved_routes, read-state-
    then-write anti-race, PK (user_id, route_id)), `fetchSavedRouteIds`
    (sekali per ViewModel, bareng fetch likes), `deleteComment` (RLS
    "delete own"; trigger 0005 ikut menurunkan comments_count server-
    side, UI sync lokal optimistik + rollback), `submitReport` (insert
    reports, target route ATAU comment — schema CHECK exactly-one).
  - UI: tombol bookmark di action row kartu Browse & detail (Bookmark/
    BookmarkBorder, mirror pola like; anon → onRequireSignIn), flag
    Report rute di top bar Route Detail, per-komentar: MoreVert→hapus
    (punya sendiri, dengan AlertDialog konfirmasi) atau Flag→report
    (punya orang lain), ReportDialog radio 5 alasan schema
    (spam/misleading/offensive/danger/other) + note opsional.
  - Enum `SocialRepository.ReportReason` mirror wire CHECK schema —
    kalau schema berubah, enum ikut diubah manual (single source of
    truth tetap DB).
  - Notifikasi: sengaja TIDAK dibuat (tidak ada infra push; bukan
    bagian Fase 4 schema). ❌ Sisa satu-satunya: menghapus komentar
    via dashboard/SQL manual tetap satu-satunya moderasi selain baca
    tabel reports langsung di Supabase.
- 🟢 **LAYAR DAFTAR BOOKMARK (2026-09-19): tab Profile kini History | Saved;
  Settings pindah ke ikon gear di pojok kanan-atas Profile (route
  standalone "settings", back arrow).** Keluhan awalnya benar: data
  bookmark tersimpan tapi tidak ada tempat melihatnya.
  - `BrowseRepository.savedRoutes()`: 1 round-trip join saved_routes→routes
    (embed FK-hint `routes!saved_routes_route_id_fkey` + nested profiles,
    pola sama dengan browse()), order by bookmark terbaru. Embed yang
    ter-RLS-filter decode jadi null → `mapNotNull` (bookmark ke rute yang
    di-unpublish/hapus dilewati, bukan crash).
  - `SavedViewModel` + `SavedEmbedded` (ui/profile): daftar kartu pakai
    `PublicRouteCard` browse persis (dibuka dari private→internal) — like
    & toggle save sama; **unsave dari daftar menghapus kartu** (optimistic
    + rollback, anti double-tap). Klik kartu → `route/{id}` (Route Detail
    browse).
  - IA Profile rev2: `ProfileTab` HISTORY|SAVED (SETTINGS dihapus),
    gear (Icons.Default.Settings) di TopAppBar kedua mode hosting;
    semua call site `profile?tab=settings` diganti `"settings"`.
    `profile_tab_settings` string dihapus; nested destination
    `profile?tab={tab}` bertahan hanya untuk tab=history|saved.

#### Kartu Browse ala Strava (keputusan layout 2026-09-17)
- Urutan kartu: header profil → nama+chips → deskripsi (max 2 baris) →
  stats grid (Jarak/Elevasi naik/Waktu) → map full-bleed → aksi.
- Map kartu sengaja ditinggikan ke 210dp (kartu) / 240dp (detail) —
  meniru peta Strava yang dominan.
- **Fallback tile berantai** di StaticMapPreview: MapTiler topo (kalau
  ada key) → **OSM standard** (keyless) → OpenTopoMap (terakhir —
  sering throttle; tile placeholder kecil kini ditolak: decode result
  <512 byte dianggap gagal).
- **ENGINE UTAMA kartu (revisi `5dd2c23`): MapLibre MapSnapshotter +
  OpenFreeMap Liberty style** — keyless & unlimited, renderer yang SAMA
  dengan peta interaktif app (sudah bundling MapLibre 12.0.1, style
  terbukti jalan di device). Rantai raster di atas jadi fallback #2;
  polyline canvas tetap degradasi terakhir (offline). Detail penting:
  paket 12.x = `snapshotter` (bukan `snapshot`), fit zoom dihitung di
  world 512px GL + zoom fraksional (margin −0.45), trace digambar ulang
  di atas bitmap snapshot dengan pixelRatio — semua API diverifikasi
  dari source maplibre-native sebelum ditulis. Alasan revisi: fetch
  PNG per-tile masih gagal di device (tanpa key MapTiler + OpenTopoMap
  throttle + OSM bisa diblokir jaringan tertentu).
- **AUDIT FIX fit kamera (track terpotong atas-bawah):** mercY itu
  north-anchored (TURUN saat lat naik), jadi `mercY(maxLat) −
  mercY(minLat)` selalu NEGATIF → `takeIf{>0}` selalu fallback 1e-5 →
  fit zoom TIDAK PERNAH menghitung tinggi track → rute tinggi-sempit
  (Lawu point-to-point) kezoom ~2.6× kartu & kepotong. Fix: `abs()` di
  snapshotCameraFor + computeStaticMapLayout. Overlay trace sekarang
  tidak lagi recompute proyeksi sendiri — posisi pixel dibaca LANGSUNG
  dari snapshot via `MapSnapshot.pixelForLatLng` (ground truth engine;
  terverifikasi dari source native: return `point * pixelRatio` = px
  bitmap fisik, jadi gak perlu konversi lagi).

### Visibility/Privacy — 🟢 SELESAI (2026-09-19)
Keputusan desain dari diskusi user (referensi screenshot Strava &
Wikiloc), model **Wikiloc 2-level** (Everyone/Only you) — bukan Strava
3-level ("Followers" butuh sistem followers; feed-sosial tidak relevan).
Yang dikerjakan:
- **Form Publish**: segmented "Siapa yang bisa lihat" — Semua orang /
  Hanya saya (default Semua orang = perilaku lama). Kedua jalur publish
  (activity & library GPX) meneruskan `isPublic`.
- **Route Detail milik sendiri**: baris visibilitas (ikon + label +
  hint + Switch) — owner saja; viewer lain tidak pernah melihat baris
  ini karena RLS menyembunyikan rute private dari mereka.
- **🔒 Audit GPX-bocor DITUTUP (migration 0006)**: bucket baru
  **privat** `route-gpx-private` (owner-only policy, layout path sama
  `{uid}/{routeId}.gpx.gz`). Rute private upload ke sana;
  `gpx_file_url` menyimpan marker `private:<path>` (bukan URL).
  `downloadGpx` membaca marker → `downloadAuthenticated`. Toggle
  visibilitas memindahkan file antar bucket dengan urutan anti-bocor:
  → private: pindah file DULU baru flip row (gagal = tetap public
  seperti perilaku lama, tidak pernah "row private + file public");
  → public: flip row DULU baru pindah file (gagal move = row sudah
  public, browse jalan dari track_polyline).
- **`is_draft` tidak disentuh** — draft ≠ private (konten belum lengkap
  vs sengaja tidak publik).
- **ⓘ WAJIB dijalankan manual** (sama seperti 0002-0005): Supabase
  Dashboard → SQL Editor → paste `supabase/migrations/0006_private_gpx_bucket.sql` → Run.
  Sebelum migration dijalankan, publish dengan visibilitas "Hanya saya"
  akan GAGAL upload (bucket belum ada) dan di-rollback aman.

### Backup otomatis "tanpa tombol" + gerbang login (keputusan desain 2026-09-19,
✅ DIKERJAKAN 2026-09 — lihat catatan IMPLEMENTASI di bawah)
Prinsip tunggal: **wajib login = layar yang aksinya menciptakan data yang
harus ke-backup**; sisanya tetap anonymous-friendly. Backup & restore TANPA
tombol "Backup now"/"Restore now":
- **Backup = efek samping dari save.** Setiap operasi kolom kiri (save
  activity, import GPX, save drawn route, add/edit waypoint) yang sukses →
  sinkron diam-diam ke `activity_backups` (RLS owner-only, pas). Failed
  upload → data tetap lokal ditandai "pending backup", auto-flush saat
  online/login lagi.
- **Restore = efek samping dari sign-in.** Begitu akun terverifikasi di
  perangkat (baru), app diam-diam menarik backup & mengisi History/Library.
  Tidak ada tombol karena tidak ada keputusan manual yang diminta user.
- **IMPLEMENTASI (2026-09):** backup activity otomatis sudah jalan sejak
  Fase 2/3 (scheduleActivityBackup — RecordingService + RecordingViewModel,
  termasuk crash-recovery). DITAMBAHKAN: (a) `scheduleRouteBackup` di 4
  pintu route — HomeViewModel & TrackAndMapsViewModel (import GPX),
  DrawRouteViewModel (rute gambar), PublicRouteDetailViewModel
  .saveToLibrary — fire-and-forget, silent, signed-in check di manager;
  (b) `scheduleInitialSync(userId)` dari LaunchedEffect session di
  MainActivity: tiap masuk SignedIn (login ATAU session restore app
  start) → restoreAll dulu lalu backupAll, sekali per user per proses
  (lastAutoSyncUserId), idempoten (skip-existing + stable-id upsert),
  tak pernah memblok UI; (c) Settings: tombol Backup/Pulihkan manual
  dihapus, section jadi status saja ("Backup otomatis aktif").
  Waypoint add/edit BELUM auto-backup sendiri (ikut source-nya: activity
  atau route di-backup ulang = waypointsJson ikut) — cukup untuk saat ini.
  Retry-queue offline BELUM (tetap open item di bawah).
- **Gerbang login (waktu save, bukan waktu mulai):** (1) Record/Start
  Activity di langkah SAVE — copy: "Masuk supaya rekaman ini tersimpan
  selamanya"; rekaman jangan dibuang — tetap lokal "pending backup",
  flush otomatis begitu login; (2) Import GPX (Map & Library); (3) Draw
  route (save); (4) Tambah/edit waypoint; (5) Publish (sudah begitu).
- **Tetap tanpa login (read-only/lokal):** Browse + Route Detail browse
  (like/komen/save sudah minta login per-aksi), Map tab, History +
  Activity Detail (data yang SUDAH ke-backup), Share card, Offline maps
  (device-specific, bukan kandidat backup), Settings.

### Belum kepikiran desainnya sama sekali (bukan cuma belum dikerjakan)
- **Antrian upload Publish/Backup saat tidak ada sinyal** — konteks
  app ini adalah pendakian gunung, biasanya TIDAK ada koneksi internet
  pas activity baru selesai. Desain Publish/Backup saat ini asumsikan
  upload bisa langsung jalan — belum ada rencana retry-queue, kapan
  upload sebenarnya terjadi, atau UI "menunggu sinyal" buat user.
  - **⏳ Ide arah solusi (belum diputuskan final, hasil diskusi user
    melihat referensi Wikiloc "Save Trail"):** ganti flow publish dari
    "tombol Publish manual belakangan dari Activity Detail" (yang
    sekarang) jadi **layar yang muncul otomatis begitu recording
    selesai** — mirip Wikiloc: form singkat (nama/distance/elevation/
    difficulty/description, privacy Public/Private) LANGSUNG muncul
    setelah stop recording, dengan toggle **"Save as Draft"**. Draft =
    activity tersimpan lokal dulu, upload publish-nya ditunda sampai
    user buka lagi nanti (saat ada sinyal). Ini bisa jadi solusi murah
    untuk masalah antrian upload di atas — user yang punya alasan
    Wikiloc-style ("masih di gunung sinyal jelek", "mau lengkapi
    deskripsi nanti", "trip multi-hari") tinggal pilih draft, bukan
    dipaksa publish/gagal upload saat itu juga.
  - Catatan dari referensi Wikiloc YANG SENGAJA TIDAK diikuti kalau
    arah ini dikerjakan: opsi **Photos** ("find photos automatically")
    — bertentangan dengan keputusan final "fitur foto dihapus total"
    (poin 1 di atas); opsi **Trail Buddies** di layar yang sama —
    tetap ikuti keputusan "Ide Masa Depan" (sengaja ditunda, lihat di
    bawah), jangan dimasukkan ke layar save-trail ini duluan.
  - Kalau arah ini dipilih, perlu dipikirkan juga: apakah ini
    MENGGANTIKAN `PublishRouteSheet` yang sudah ada (dipicu manual
    dari Activity Detail) atau jadi tambahan di samping itu — belum
    diputuskan, didiskusikan lagi nanti.

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
