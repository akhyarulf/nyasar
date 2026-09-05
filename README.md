# Nyasar (Android) — P0 + Fitur Tambahan

Aplikasi navigasi outdoor GPS buat ekosistem Nyasar Nyaman. Fokus konsisten: **GPX → Peta → GPS → Navigasi → Deteksi menyimpang**, plus rekaman aktivitas, cara offline, dan pengaturan peta.

## Keputusan teknis

| Kebutuhan | Pilihan | Alasan singkat |
|---|---|---|
| Bahasa / UI | Kotlin + Jetpack Compose | standar Android modern, lifecycle-aware, cocok buat state navigasi real-time |
| Map engine | **MapLibre GL Native (Android SDK)** | open-source, vector + raster tiles, `OfflineManager` bawaan, nggak vendor lock |
| Tile / style provider | **MapTiler** (default), **OpenFreeMap** (alternatif tanpa key), dan **9 basemap World nyasar** yang sumbernya bukan GPX Studio | abstraksi di `map/TileProvider.kt`, bisa ganti di satu tempat |
| GPX parser | `XmlPullParser` bawaan Android, nol library eksternal | parsing nggak butuh internet, nol dependency = nol risiko licensing/maintenance |
| Local storage | Room (routes + aktivitas) + file GPX disalin ke `filesDir` | spec eksplisit: nggak ada cloud DB di MVP |
| GPS | FusedLocationProviderClient, `PRIORITY_HIGH_ACCURACY` | tetap resolve dari GPS device saat offline |
| Offline map | `MapLibre OfflineManager` (tile pyramid region) | provider-agnostic — jalan walau provider diganti |
| Rekaman (recording) | Foreground service + `RecordingEngine` murni | satu sumber GPS buat rekaman + navigasi sekaligus |

## Arsitektur modular tile provider

```
map/
  TileProvider.kt            <- interface: styleUrl(), isConfigured(), id
  providers/
    MapTilerProvider.kt      <- satu-satunya file yang tahu tentang MapTiler
    OpenFreeMapProvider.kt   <- implementasi kedua, TANPA API key
    TileProviderFactory.kt   <- satu titik registrasi provider
```

Aturan keras yang dijaga di seluruh codebase:
- `navigation/`, `gpx/`, `data/` **tidak pernah** mengimpor apa pun dari `map.providers.*`.
- `NyasarMapView` dan `OfflineMapManager` hanya menerima `TileProvider` sebagai parameter — tidak pernah membuat instance provider sendiri.
- Ganti provider default → ubah satu baris di `TileProviderFactory.default()`. Tambah provider baru (mis. self-hosted tileserver) → satu file baru + satu baris registrasi. Navigation engine, deteksi menyimpang, GPX parser tidak tersentuh.

Katalog basemap (`map/BasemapCatalog.kt`) punya 9 basemap World yang mengikuti daftar GPX Studio hanya sebagai referensi tampilan. Nyasar **tidak** me-proxy apa pun lewat `styles.gpx.studio`; setiap basemap pakai sumber upstream aslinya. Dari 9 itu, 5 vector (Liberty Topo, Liberty Satellite, OpenMapTiles OSM, OpenMapTiles OSM Topo, UtagawaMTB) dan 4 raster (OpenStreetMap, OpenTopoMap, OpenHikingMap, CyclOSM). Beberapa style IGN France (plan/topo/satellite) tetap di-bundle sebagai asset APK dan di-load inline lewat `RasterStyleJson`, bukan lewat URL eksternal.

Navigasi (`NavigationEngine`, `TrackMatcher`) dan rekaman (`RecordingEngine`) adalah Kotlin murni tanpa dependency Android/network sama sekali — itu sebabnya bisa di-unit-test tanpa emulator (`app/src/test/`).

## Offline-first

- GPX yang diimpor **disalin** ke local storage saat import (`RouteRepository.importFromUri`), bukan dibaca langsung dari URI asal — supaya navigasi tidak pernah bergantung pada file manager / Google Drive / SAF URI yang mungkin tidak valid nanti.
- GPS position selalu dari device (`LocationRepository`), tidak pernah lewat server.
- Peta offline: user men-download area sebelum berangkat via `OfflineMapManager.downloadRegion()`, disimpan di database offline milik MapLibre sendiri. Saat tidak ada internet, MapLibre otomatis pakai tile lokal — kode navigasi tidak perlu tahu online/offline sama sekali.
- Layar download area (`OfflineDownloadScreen`) sudah terhubung penuh ke `OfflineMapManager` — bounding box otomatis dari track (padding ±1.5km), progress %, dan status selesai/gagal.

## Yang sudah jalan

1. Import GPX (file picker + Open With / Share intent) → parse → disimpan lokal → muncul di Home.
2. **Open With / Share GPX dari app lain** → langsung parse, simpan lokal, dan buka Route Preview otomatis (`MainActivity` tangkap intent, `HomeScreen` konsumsi sekali lalu navigasi).
3. Route Preview (jarak, elevation gain/loss, waypoint count, peta, tombol mulai aktivitas, download offline, share/export GPX).
4. Mulai Navigasi → live map, posisi GPS, jarak tempuh/sisa, elevation saat ini, elevation gain, kecepatan, moving time, GPS accuracy ±X m, sisa elevation gain.
5. Rekaman aktivitas (RecordingService foreground + `RecordingEngine`) dengan: timer, jarak, elevation gain/loss, kecepatan, rata-rata, split pace, auto-pause, indikator GPS lemah/hilang, peringatan storage penuh, konfirmasi stop, summary pasca-rekaman, pilih foto dari galeri / kamera, simpan atau buang.
6. **Pilih Jalur** dari layar recording (IDLE) → preview garis GPX di map sebelum mulai rekam, dengan lapisan `Track & Peta` yang terpisah dari Library.
7. **Gambar rute sendiri** (draw-route) → titik-tahan di map → hasilkan file GPX + `RouteEntity` lokal yang identik dengan rute hasil import, jadi preview / mulai aktivitas / offline download semua jalan tanpa kode khusus.
8. **Settings screen** — pilih map provider (MapTiler / OpenFreeMap), pilih basemap dari katalog GPX Studio (world + country section, thumbnail procedural), atur threshold? (tergantung versi kode), pilih tema (system / terang / gelap), bahasa (system / id / en), speed unit (kmh / mph), keep screen on, auto-pause on/off — tersimpan di DataStore, dipakai ulang saat Route Preview dan Navigation dibuka.
9. **Offline map download** — dari Route Preview atau layar khusus, area di sekitar track (dipadding ±1.5km) bisa diunduh lewat `OfflineMapManager` sebelum berangkat, progress ditampilkan, dan hasilnya otomatis dipakai MapLibre saat offline tanpa perubahan kode navigasi. Ada layar `OfflineMapsScreen` buat lihat / hapus area yang sudah diunduh.
10. **Waypoint**:
    - GPX waypoint (baca dari file, tampil di peta, tap → lihat detail nama/koordinat/elevation/deskripsi).
    - User waypoint (buat sendiri: long-press di map → nama, kategori, catatan, elevasi opsional; icon berbedaan per kategori; tap → detail + jarak dari lokasi; edit / hapus). Bisa dibuat dari Home, Navigation, maupun Recording.
11. **Kompas / heading**: `GpsFix` menyimpan `bearingDeg`, `LocationRepository` membaca `Location.bearing()`, dipakai buat panah heading di marker user, tombol kompas reset ke north, dan recenter 3-state (bebas / ikut posisi utara / ikut posisi + arah hadap).
12. **Layar History** — list aktivitas rekaman, share GPX aktivitas, buka detail.
13. **Layar Activity Detail** — rename, hapus, rencana vs aktual, elevation profile, waypoint rekaman, export/share.
14. **Share card** — generator kartu aktivitas dari data Room.
15. Unit test untuk off-route detector, track matcher, elevation stats, dan recording engine.

## Patch / perbaikan yang sudah masuk

1. User location marker muncul di peta — `NyasarMapView` sekarang punya `SOURCE_USER` dengan halo + dot biru + panah heading, plus accuracy circle geografis.
2. Heading/compass ada — bukan cuma UI, `GpsFix` dan `LocationRepository` sekarang menyimpan/membaca bearing.
3. Tap waypoint menampilkan detail lengkap (nama, koordinat, elevation, deskripsi) — `NyasarMapView` punya click listener lewat `addOnMapClickListener` + `queryRenderedFeatures`, dipakai di Route Preview dan Navigation.
4. GPS accuracy (±X m) muncul di status bar navigasi.
5. Status rekaman lebih eksplisit: SIAP / ● RECORDING / ❚❚ DIJEDA / ❚❚ DIJEDA OTOMATIS / SELESAI, plus GPS HILANG / GPS LEMAH.
6. Recovery dialog — kalau proses mati saat rekaman berjalan, layar nge-check dan tunjukkin sisa session buat dilanjutkan / dihentikan / dibuang.
7. Stop rekaman butuh konfirmasi, dan kalau user belum gerak (<5m selama >5 detik) muncul prompt "Belum bergerak?" alih-alih langsung cut.
8. Auto-start dari Route Preview / Start Activity / routeId sekarang lebih aman — ada guard status dan retry terbatas biar nggak mulai session sendiri tanpa tindakan user.
9. Pembaruan kamera diikuti throttle (minimal 300ms antar animasi) supaya heading-up / follow tidak jitter.
10. Actual track (jejak rekaman) di-redraw lewat `Dispatchers.Default` biar nggak makin berat di main thread seiring panjang rekaman.
11. Bottom bar dan navigasi tab pakai pola `popUpTo(start) + saveState/restoreState` yang konsisten, termasuk pemulihan tab terakhir sebelum masuk Recording.

## Yang sengaja BELUM dikerjakan (P2+)

- Background navigation penuh (kerangka `RecordingService`/`NavigationService` ada, tapi collection GPS milik ViewModel saat app di-minimize belum diganti sepenuhnya sesuai spec P1 item 18).
- Tap waypoint di layar Navigation (baru ada di Route Preview — sengaja, supaya bottom sheet tidak mengganggu sesi navigasi aktif).
- Format selain GPX (KML / GeoJSON / dst) — sesuai instruksi, MVP fokus GPX saja.
- Ubah threshold off-route di tengah sesi navigasi yang sedang berjalan (saat ini dibaca sekali di awal `NavigationScreen`).
- List/hapus offline region yang sudah diunduh (method `listRegions`/`deleteRegion` sudah ada di `OfflineMapManager`, belum ada UI-nya di versi ini).
- Hubungan ke Nyasar Nyaman / GitHub — belum ada koneksi apa pun di P0, sesuai spec, itu hanya sumber file GPX opsional, bukan dependency runtime.

## Struktur

```
app/src/main/java/com/nyasar/app/
  MainActivity.kt            <- entry, intent filter .gpx, request lokasi + POST_NOTIFICATIONS
  NyasarApp.kt               <- Application, inisialisasi MapLibre sekali

  gpx/
    GpxParser.kt             <- streaming parser GPX 1.1 (XmlPullParser)
    GpxExporter.kt           <- export GPX aktivitas
    model/GpxModels.kt       <- GpxDocument, GpxTrack, GpxWaypoint, TrackPoint

  navigation/
    NavigationEngine.kt      <- jarak tempuh/sisa, elevation gain, kecepatan, moving time; pure Kotlin
    TrackMatcher.kt          <- match GPS ke garis track + jarak sepanjang track
    OffRouteDetector.kt      <- deteksi menyimpang (kalau masih dipakai)
    ElevationStats.kt        <- ringkasan elevasi dari list titik
    GeoMath.kt               <- geometri dasar (distance, bounds)

  recording/
    RecordingEngine.kt       <- logika statistik rekaman murni
    RecordingService.kt      <- foreground service, pengumpulan GPS, auto-pause, GPS health watchdog
    RecordingServiceConnection.kt
    SportType.kt

  location/
    LocationRepository.kt    <- FusedLocationProvider
    HeadingProvider.kt       <- pembaca bearing device
    NavigationService.kt     <- kerangka layanan navigasi

  map/
    TileProvider.kt          <- interface abstraksi provider
    BasemapCatalog.kt        <- enum katalog basemap GPX Studio
    OfflineMapManager.kt     <- download region MapLibre offline
    providers/
      MapTilerProvider.kt
      OpenFreeMapProvider.kt
      TileProviderFactory.kt
      RasterStyleJson.kt    <- style inline untuk asset-backed + raster

  data/
    db/  (Room)
      RouteEntity, RouteDao, ActivityEntity, ActivityPointEntity, ActivityDao,
      ActivityPhotoEntity, ActivityPhotoDao, WaypointEntity, WaypointDao, WaypointCategory,
      AppDatabase
    repository/
      RouteRepository.kt     <- import GPX → local copy → persist
      ActivityPhotoRepository.kt
      WaypointRepository.kt
    settings/
      SettingsRepository.kt  <- DataStore: provider, tema, bahasa, speed unit, keep screen on, auto-pause

  ui/
    components/              <- NyasarMapView, NyasarBottomBar, CompassButton, ZoomControls,
                                 CameraFollowMode, BasemapPickerSheet, ElevationProfile, SplitsTable
    home/HomeScreen + HomeViewModel
    preview/RoutePreviewScreen + RoutePreviewViewModel + OfflineDownloadScreen + OfflineDownloadViewModel
    navigation/NavigationScreen + NavigationViewModel
    recording/RecordingScreen + RecordingViewModel + PostRecordingForm + SportFilterSheet
    history/ActivityHistoryScreen + ActivityHistoryViewModel + ActivityDetailScreen + ActivityDetailViewModel + ActivityPhotosSection
    trackmaps/TrackAndMapsScreen + TrackAndMapsViewModel
    routepicker/RoutePickerScreen
    offline/OfflineMapsScreen + OfflineMapsViewModel
    settings/SettingsScreen + SettingsViewModel
    drawroute/DrawRouteScreen + DrawRouteViewModel
    startActivity/StartActivityScreen
    waypoint/WaypointViewModel + WaypointSheets + WaypointCrosshairScreen
    share/ShareCardScreen + ShareCardGenerator
    theme/Theme + Type

  util/SpeedUtils.kt
  media/PhotoStorageManager.kt
```

## Dapat APK tanpa install apa pun (GitHub Actions)

Repo ini sudah punya `.github/workflows/build.yml`. Caranya:

1. Push project ini ke repo GitHub baru (public atau private, keduanya gratis untuk Actions).
2. *(Opsional tapi disarankan)* Buka **Settings → Secrets and variables → Actions → New repository secret**, buat secret bernama `MAPTILER_API_KEY` isi dengan key dari cloud.maptiler.com. Kalau dilewati, APK tetap ke-build dan tetap jalan — cuma otomatis fallback pakai OpenFreeMap (tanpa key) sampai kamu isi key-nya.
3. Buka tab **Actions** di repo → workflow "Build Nyasar APK" akan otomatis jalan setiap push ke `main`/`master`, atau klik **Run workflow** untuk trigger manual.
4. Setelah selesai (~5–8 menit build pertama kali), buka run tersebut → bagian **Artifacts** → download `nyasar-debug-apk`.
5. Extract zip-nya, dapat `app-debug.apk` → transfer ke HP (via USB/Drive/dsb) → install (aktifkan "Install dari sumber tidak dikenal" kalau diminta).

Catatan: ini debug build (signing pakai debug key bawaan Android), pas untuk testing di HP sendiri. Untuk rilis ke Play Store nanti perlu signing config terpisah — belum termasuk di P0 ini.

## Setup lokal (alternatif — kalau sudah punya Android Studio)

1. Copy `local.properties.example` → `local.properties`, isi `sdk.dir` dan `MAPTILER_API_KEY` (gratis di cloud.maptiler.com).
2. Buka di Android Studio (Koala+), sync Gradle.
3. Run di device fisik untuk testing GPS/offline yang realistis — emulator tidak merepresentasikan kondisi GPS di gunung.
4. `./gradlew testDebugUnitTest` untuk unit test navigation/off-route/recording.

## Build / dependency

- Plugin Android Gradle `8.5.2`, Kotlin `1.9.24`, KSP `1.9.24-1.0.20` (lihat `build.gradle.kts`).
- ProGuard rules minimal: `-keep class org.maplibre.** { *; }` (lihat `app/proguard-rules.pro`).

## Catatan tentang README ini

Versi README sebelumnya ada beberapa kalimat yang sudah bertambah/berubah sejak pertama kali ditulis (misalnya bagian offline yang sempat tertulis "belum disambungkan" padahal sudah, dan fitur waypoint/user/recording yang sudah masuk di kode tapi belum tercermin di README). Versi sekarang dicoba update sejalan dengan isi file di `app/src/main/java/com/nyasar/app/` yang ada saat ini.
