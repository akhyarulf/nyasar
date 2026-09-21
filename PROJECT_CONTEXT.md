# Konteks Project: Nyasar

Aplikasi Android (Kotlin + Jetpack Compose) navigasi hiking offline-first
untuk pendaki Indonesia: rekam GPS, peta offline (MapLibre), import/export
GPX, waypoint, akun Supabase, publish & jelajah rute publik, backup otomatis.
Distribusi lewat GitHub Releases (tanpa Play Store). Web pendamping live di
`app.nyasarnyaman.my.id` (GitHub Pages, sumber folder `web/`).

Dokumen ini versi RINGKAS (ditulis ulang 2026-09-21 atas permintaan user —
versi panjang sebelumnya terlalu panjang untuk dibaca). Riwayat detail
ada di git log + commit messages.

---

## Status: SEMUA FASE INTI SELESAI

| Area | Status |
|---|---|
| Fase 0 — inti offline (rekam, GPX, peta offline, waypoint) | ✅ |
| Fase 1 — akun (email/Google, hapus akun, ganti password/email, gate username) | ✅ |
| Fase 2 — publish (save = publish + draft, antrian offline, browse + filter, detail, unduh GPX) | ✅ |
| Fase 3 — backup otomatis (delta-encode, restore saat login, network-trigger flush) | ✅ |
| Fase 4 — sosial (like, komentar + hapus milik sendiri, save/bookmark, report) | ✅ |
| Visibility/privacy (Semua orang / Hanya saya + bucket GPX privat) | ✅ |
| App Links + web pendamping | ✅ LIVE |
| Distribusi APK + update in-app | ✅ (menunggu rilis pertama untuk verifikasi lapangan) |
| Migrasi Supabase 0002–0006 | ✅ SEMUA SUDAH DIJALANKAN (diverifikasi live 2026-09-21: RPC `delete_own_account` ada, bucket `route-gpx-private` ada) |

Firebase hanya Analytics + Crashlytics (`app/google-services.json` di-commit —
isinya bukan rahasia untuk app Android).

---

## Keputusan Arsitektur yang TETAP MENGIKAT

1. **Local-first.** Titik GPS mentah tidak pernah diunggah. Publish = aksi
   eksplisit (kini digabung "save = publish" dengan opsi Draft). Yang ke
   cloud hanya ringkasan: `track_polyline` + GPX asli di-gzip ke Storage.
2. **Full open ala Wikiloc, bukan Strava:** rute publik HARUS bisa
   diunduh jadi GPX asli oleh siapa pun (bucket publik `route-gpx`,
   path `{uid}/{routeId}.gpx.gz`, URL di `routes.gpx_file_url`).
3. **Visibility 2-level** (Semua orang / Hanya saya) — bukan 3-level
   Strava. Rute privat → bucket privat `route-gpx-private`,
   `gpx_file_url` berisi marker `private:<path>` (bukan URL).
   Toggle visibilitas memindahkan file antar bucket dengan urutan
   anti-bocor (privat: file dulu baru row; publik: row dulu baru file).
4. **Tanpa foto** — dihapus total (Room v8 drop tabelnya), sengaja, demi
   kuota backup gratis Supabase. Jangan dibuka ulang tanpa diskusi.
5. **`username_is_set`** (kolom profiles) adalah dasar gate "Pilih
   Username" — BUKAN cara sesi terbentuk (`SessionSource` salah).
6. **Backup ≠ publish.** `activity_backups` privat total (RLS owner-only),
   delta-encode + gzip (~9 KB/activity). Track dikirim sebagai **bytea
   HEX `\x…`, bukan base64** (base64 lolos parse sebagai escape-ASCII →
   korupsi diam-diam; sudah ditangani `BackupRepository`).
7. **Solo dev, modal nol:** semua gratis tanpa kartu kredit. Jangan
   meniru seluruh fitur Wikiloc/Strava — ini "Wikiloc ringan Indonesia".
8. **Bahasa:** app + web bilingual ID (default) / EN.

---

## Jebakan Teknis — PERNAH TERJADI, JANGAN DIULANG

- **Embed `profiles` di query routes WAJIB FK hint**
  `profiles!routes_user_id_fkey(...)` — `routes` punya 3 relasi ke
  `profiles`, tanpa hint PostgREST menolak (PGRST201). Sudah 3x
  menyengat: BrowseRepository (list & detail), web route page, web
  browse page. Semua tempat BARU yang join profiles wajib hint.
- **`supabase-kt 2.2.2` menelan error PostgREST** jadi
  `UnknownRestException`. Reproduce request-nya langsung ke PostgREST
  (curl + apikey publishable) untuk melihat kode PGRST* asli.
- **CI Android SDK:** runner `ubuntu-latest` sudah punya SDK penuh di
  `$ANDROID_SDK_ROOT` — JANGAN pakai `android-actions/setup-android`
  (install paket `tools` yang sudah dihapus Google → job mati).
- **Polyline test vector** resmi Google: `_p~iF~ps|U_ulLnnqC_mqNvxq`@`.
- **Shallow clone bikin `git rev-list --count HEAD` = 1** — checkout di
  workflow rilis wajib `fetch-depth: 0` (versionCode dari jumlah commit).
- **ModalBottomSheet (material3 1.2.1 / BOM 2024.06):** default buka
  setengah (butuh `rememberModalBottomSheetState(skipPartiallyExpanded =
  true)`) dan TIDAK menghormati navigation bar / keyboard — konten sheet
  wajib diberi `.navigationBarsPadding().imePadding()` manual. Semua 11
  sheet app sudah dibegitu (2026-09-21); sheet BARU wajib ikut pola ini.
- **Member Compose yang dibaca composable top-level di file yang sama**
  tidak boleh `private` (CI pernah gagal: `Cannot access 'pendingUpdate'`).
- **MapSnapshotter fit kamera:** `mercY(maxLat) − mercY(minLat)` selalu
  negatif (mercY north-anchored) — wajib `abs()`. Posisi overlay trace
  dibaca langsung dari `MapSnapshot.pixelForLatLng`, jangan hitung ulang.
- **Placeholder `local.properties.example`** untuk
  `GOOGLE_OAUTH_WEB_CLIENT_ID` pernah salah arah (format URL) — Client ID
  Google bukan URL. TODO kecil: rapikan file itu.

---

## Operational Runbook

- **Rilis:** `git tag v0.9.0-beta && git push origin v0.9.0-beta`
  (atau Actions → "Release APK (GitHub Releases)" → Run workflow; tanpa
  tag, workflow bikin tag tanggal sendiri). Hasil: APK signed di GitHub
  Release sebagai `Nyasar.apk`; link permanen
  `github.com/akhyarulf/nyasar/releases/latest/download/Nyasar.apk`.
  versionName = tag tanpa `v`; versionCode = jumlah commit (env
  `VERSION_NAME`/`VERSION_CODE`, ada fallback di build.gradle.kts).
- **Secrets repo (sekali set):** KEYSTORE_BASE64, KEYSTORE_PASSWORD,
  KEY_ALIAS, KEY_PASSWORD, SUPABASE_URL, SUPABASE_PUBLISHABLE_KEY,
  MAPTILER_API_KEY, GOOGLE_OAUTH_WEB_CLIENT_ID.
- **Deploy web:** otomatis saat push menyentuh `web/` (workflow
  `pages-deploy.yaml`); `config.js` digenerate saat deploy dari secrets.
- **Update in-app** (`update/UpdateChecker.kt`): sekali per proses saat
  app dibuka, GET `releases/latest`, bandingkan tag vs versionName
  (semantik, prerelease kalah, downgrade bukan update). SEMUA gagal =
  tanpa dialog — iner sebelum rilis pertama.
- **App Links:** `web/.well-known/assetlinks.json` berisi 2 SHA-256 —
  debug (dari keystore committed `keystore/debug.keystore`) dan release
  (copy dari log step "Print release cert SHA-256" di release-apk.yaml).
- **VERIFIKASI YANG MENUNGGU RILIS PERTAMA:** (1) dialog update —
  install beta → tag v0.9.1 → dialog harus muncul; (2) App Links dengan
  APK release — klik link `/route?id=…` harus langsung buka app.
- **Backup keystore release di luar GitHub Secrets** (password
  manager / 2 tempat) — kalau hilang, user yang terinstall tidak bisa
  upgrade dan App Links mati. Belum ada konfirmasi user sudah melakukan.
- **Pengingat:** domain .my.id perpanjang tahunan; Supabase free tier
  auto-pause setelah ~1 minggu idle (buka dashboard sesekali).

---

## Roadmap

### Langsung setelah rilis pertama (v0.9.0-beta)
1. Distribusikan ke 3–5 teman (grup WA), pantau Crashlytics.
2. Verifikasi lapangan dua butir di Runbook di atas.
3. Catat feedback ke GitHub Issues sebelum menambah fitur apa pun.

### Web — BARU SELESAI (2026-09-21, permintaan user)
- **Search + filter kesulitan** di halaman browse (`web/browse/`) —
  client-side (data max 60 sudah penuh di memori), debounce 250ms,
  chips ala FilterChips app, counter "X dari Y rute", state
  "tidak ada yang cocok" terpisah dari "belum ada rute".
- **Dark mode web** — `@media (prefers-color-scheme: dark)` penuh di
  `style.css` (ikuti sistem, paritas dengan app). Variabel teks/latar
  dipisah dari warna brand: gradien (btn-primary, band, brand-mark)
  tetap gelap di kedua tema dengan teks putih; yang bertukar hanya
  --ink/--accent/--surface dst. Token dark = dark theme app
  (#1A1C19/#A5C0AA). Jangan re-hardcode warna terang di rule baru.
- **Tombol share per rute** (`web/route/`) — Web Share API (sheet
  WA/TG di HP), fallback copy-to-clipboard + toast; i18n 2 bahasa.
- (sebelumnya di slice yang sama) og-image digenerate
  `scripts/gen_og_image.py` + meta og/twitter dinamis di route page +
  robots.txt + sitemap.xml.

### Web — kandidat berikutnya (belum dikerjakan)
- QR code "scan untuk unduh" di hero landing (CDN lib kecil).
- Privacy policy: tambah 1 kalimat soal update-check ke GitHub.
- Screenshot app asli di hero landing (saat ini murni teks).

### Fitur app — kandidat (menunggu feedback user, jangan gas duluan)
- **Backtrack** — navigasi mengikuti jejak rekaman sendiri (input =
  activity points, reuse OffRouteDetector + NavigationScreen).
- **Sunrise/sunset + estimasi tiba** — murni matematis lokal, warning
  "perkiraan selesai lewat matahari terbenam".
- **Alarm dekat waypoint** saat navigasi (getar/notifikasi).

### Sengaja ditunda (butuh massa user / di luar scope)
- Trail Buddies (butuh tabel baru `route_buddies`), segments ala
  Strava, sistem follow, notifikasi push.
- Retry-queue backup per-waypoint (backup utama idempoten + auto-jalan,
  cukup untuk sekarang).
- Moderasi komentar lewat Supabase Dashboard (satu-satunya jalur
  moderasi selain baca tabel `reports`).

---

## Prinsip Kerja

- Cek dulu struktur yang sudah ada (Room entities / kolom Supabase)
  sebelum menambah skema — jangan mengarang dari nol.
- Perubahan di luar chat ini belum "selesai" sampai ada bukti balik
  (commit masuk / CI hijau / laporan hasil).
- Skema berkembang iteratif — jangan kejar "sempurna dari awal".
- Pisahkan tegas fitur PUBLIK (routes, dkk) vs PRIVAT (activity_backups)
  — RLS tidak boleh tercampur.
