# Nyasar (Android) — P0 skeleton

Outdoor GPS navigation companion app for the Nyasar Nyaman ecosystem.
`GPX → Map → GPS → Navigation → Off-route detection` — nothing else, per spec.

## Keputusan teknis (ringkas)

| Kebutuhan | Pilihan | Alasan |
|---|---|---|
| Bahasa/UI | Kotlin + Jetpack Compose | standar modern Android, lifecycle-aware, cocok untuk state navigasi real-time |
| Map engine | **MapLibre GL Native (Android SDK)** | open-source, vector tiles, offline region download built-in (`OfflineManager`), tidak vendor-locked |
| Tile/style provider | **MapTiler** (default), **OpenFreeMap** (alternatif siap pakai) | lihat `map/TileProvider.kt` — abstraksi modular, lihat di bawah |
| GPX parser | `XmlPullParser` bawaan Android, tanpa library eksternal | parsing tidak boleh butuh internet; nol dependency = nol risiko licensing/maintenance |
| Local storage | Room (routes) + file GPX disalin ke `filesDir` | spec eksplisit: tidak ada cloud DB di MVP |
| GPS | FusedLocationProviderClient, `PRIORITY_HIGH_ACCURACY` | tetap resolve dari GPS on-device saat offline |
| Offline map | `MapLibre OfflineManager` (tile pyramid region) | provider-agnostic — bekerja dengan style URL apa pun, termasuk saat provider diganti |

## Arsitektur modular tile provider (sesuai permintaan)

```
map/
  TileProvider.kt            <- interface: styleUrl(), isConfigured(), id
  providers/
    MapTilerProvider.kt      <- satu-satunya file yang tahu tentang MapTiler
    OpenFreeMapProvider.kt   <- implementasi kedua, TANPA API key, buktikan abstraksi ini nyata
    TileProviderFactory.kt   <- satu titik registrasi provider
```

Aturan keras yang dijaga di seluruh codebase:
- `navigation/`, `gpx/`, `data/` **tidak pernah** mengimpor apa pun dari `map.providers.*`.
- `NyasarMapView` dan `OfflineMapManager` hanya menerima `TileProvider` sebagai parameter — tidak pernah membuat instance provider sendiri.
- Ganti provider default → ubah satu baris di `TileProviderFactory.default()`. Tambah provider baru (mis. self-hosted tileserver) → satu file baru + satu baris registrasi. Navigation engine, off-route detection, GPX parser tidak tersentuh.

Navigasi (`NavigationEngine`, `TrackMatcher`, `OffRouteDetector`) adalah Kotlin murni tanpa dependency Android/network sama sekali — itu sebabnya bisa di-unit-test tanpa emulator (lihat `app/src/test/`).

## Offline-first (spec §9–10)

- GPX yang diimpor **disalin** ke local storage saat import (`RouteRepository.importFromUri`), bukan dibaca langsung dari URI asal — supaya navigasi tidak pernah bergantung pada file manager/Google Drive/dsb masih bisa diakses nanti.
- GPS position selalu dari device (`LocationRepository`), tidak pernah lewat server.
- Peta offline: user men-download area sebelum berangkat via `OfflineMapManager.downloadRegion()`, disimpan di database offline milik MapLibre sendiri. Saat tidak ada internet, MapLibre otomatis pakai tile lokal — kode navigasi tidak perlu tahu online/offline sama sekali.
- Layar download area (`OfflineDownloadScreen`) sudah terhubung penuh ke `OfflineMapManager` sejak awal — bounding box otomatis dari track (padding ±1.5km), progress %, dan status selesai/gagal. (Catatan lama di README ini sempat bilang "belum disambungkan" padahal sudah — sudah diperbaiki.)

## Yang sudah jalan di skeleton ini (P0 + sebagian P1)

1. Import GPX (file picker) → parse → tersimpan lokal → muncul di Home
2. **Open With / Share GPX dari app lain** → langsung parse, simpan lokal, dan buka Route Preview otomatis (`MainActivity` menangkap intent, `HomeScreen` mengonsumsinya sekali lalu navigasi)
3. Route Preview (jarak, elevation gain, waypoint count, map)
4. Mulai Navigasi → live map, posisi GPS, jarak tempuh/sisa, elevation gain, kecepatan, moving time
5. Off-route detection dengan threshold on-route/warning/off-route yang bisa dikonfigurasi, tahan terhadap satu bacaan GPS yang menyimpang, dan melebar otomatis saat akurasi GPS buruk
6. **Settings screen** — pilih map provider (MapTiler/OpenFreeMap) secara langsung dari UI, dan atur threshold off-route dengan slider; tersimpan di DataStore, dipakai ulang saat Route Preview dan Navigation dibuka
7. **Offline map download** — dari Route Preview, area di sekitar track (dipadding ±1.5km) bisa diunduh lewat `OfflineMapManager` sebelum berangkat, progress ditampilkan, dan hasilnya otomatis dipakai MapLibre saat offline tanpa perubahan kode navigasi
8. Unit test untuk off-route detector, track matcher, dan elevation stats

## Patch terbaru (post-P0 QA)

Tiga gap ditemukan lewat crosscheck manual terhadap spec dan sudah diperbaiki:

1. **User location marker tidak muncul di map** — `NyasarMapView` menerima `userLocation` dan menggerakkan kamera, tapi tidak pernah menggambar apa pun di posisi itu (tidak ada source/layer). Ditambahkan `SOURCE_USER` dengan dua `CircleLayer` (halo + dot biru).
2. **Heading/compass sama sekali belum ada** — bukan cuma UI, `GpsFix` bahkan tidak menyimpan bearing dan `LocationRepository` tidak membaca `Location.bearing()`. Ditambahkan field `bearingDeg` di `GpsFix`, dibaca dari `loc.hasBearing()`/`loc.bearing`, dan digambar sebagai panah kecil (`SymbolLayer` dengan `iconRotate`) di atas dot user — hanya muncul kalau device benar-benar melaporkan bearing (biasanya saat bergerak).
3. **Tap waypoint tidak menampilkan detail** (spec §13 wajib: nama/koordinat/elevation/description) — `NyasarMapView` tidak punya click listener sama sekali. Ditambahkan `onWaypointClick` callback via `map.addOnMapClickListener` + `queryRenderedFeatures`, dipakai di `RoutePreviewScreen` untuk memunculkan `ModalBottomSheet` detail.

GPS accuracy (`±X m`) yang sebelumnya sudah dihitung di `NavigationEngine` tapi tidak pernah ditampilkan, sekarang muncul di status bar navigasi.

## Yang sengaja BELUM dikerjakan (P2+)

- Background navigation penuh (foreground service sudah ada kerangkanya di `NavigationService`, belum menggantikan collection GPS milik ViewModel saat app di-minimize — spec P1 item 18)
- Tap waypoint di layar Navigation (baru ada di Route Preview — sengaja, supaya bottom sheet tidak mengganggu sesi navigasi aktif yang sedang jalan)
- Format selain GPX (KML/GeoJSON/dst) — sesuai instruksi, MVP fokus GPX saja
- Mengubah threshold off-route di tengah sesi navigasi yang sedang berjalan (saat ini dibaca sekali di awal `NavigationScreen`)
- List/hapus offline region yang sudah diunduh (method `listRegions`/`deleteRegion` sudah ada di `OfflineMapManager`, belum ada UI-nya)

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
4. `./gradlew testDebugUnitTest` untuk unit test navigation/off-route.

## Struktur

```
app/src/main/java/com/nyasar/app/
  gpx/            parser + model (GpxTrack, GpxWaypoint, TrackPoint)
  navigation/     GeoMath, TrackMatcher, OffRouteDetector, ElevationStats, NavigationEngine — pure Kotlin
  map/            TileProvider abstraction, OfflineMapManager
  location/       LocationRepository (FusedLocationProvider), NavigationService (foreground service)
  data/           Room (RouteEntity/Dao/Database), RouteRepository (GPX → local storage orchestration)
  ui/             Compose screens: home, preview, navigation, theme, components (map wrapper)
```

Belum ada koneksi ke Nyasar Nyaman/GitHub sama sekali di P0 — sesuai spec, itu hanya sumber file GPX opsional, bukan dependency runtime.
