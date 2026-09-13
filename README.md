# 🥾 Nyasar — Teman Hiking yang Kamu Butuhkan

**Nyasar** artinya "tersesat" dalam bahasa Indonesia. Ironisnya, aplikasi ini dibuat justru supaya kamu *nggak* nyasar.

Nyasar adalah aplikasi navigasi outdoor gratis untuk Android, dibuat khusus untuk para pendaki dan trail runner Indonesia. Bawa file rute GPX-mu, unduh petanya sebelum berangkat, dan jalurmu akan tetap tampil di layar meskipun sinyal hilang di tengah hutan — karena semua peta dan navigasi berjalan langsung dari HP-mu.

> Tanpa akun. Tanpa iklan. Tanpa data yang dikirim ke server. 100% gratis dan open source.

---

## ✨ Apa yang Bisa Nyasar Lakukan?

### 🗺️ Navigasi jalur yang tetap jalan tanpa internet
Impor file GPX rute pendakian (dari komunitas, teman, atau hasil export dari AllTrails/Gaia dan layanan sejenis), dan Nyasar akan menampilkannya di peta sambil melacak posisimu dengan GPS. Kamu langsung lihat:
- Sudah sejauh mana kamu menempuh jalur, dan berapa sisa jaraknya
- Naikan berapa, turunan berapa, dan sisa naikan ke puncak
- Kecepatan, waktu bergerak, dan akurasi GPS
- **Peringatan kalau kamu keluar dari jalur** — fitur penyelamat saat kabut turun

### 📴 Unduh peta sebelum berangkat
Dari halaman rute, unduh area peta di sekitar jalur (otomatis mengikuti bentuk track). Di gunung tanpa sinyal, peta tetap terbuka mulus seperti biasa. Bisa kelola dan hapus area unduhan dari menu khusus.

### ⏱️ Rekam setiap perjalananmu
Tekan rekam, dan Nyasar mencatat seluruh petualanganmu: jarak, waktu, naik/turun elevasi, kecepatan, sampai pace per kilometer. Ada auto-pause (nggak nyatat waktu kamu istirahat), peringatan GPS lemah, dan konfirmasi sebelum berhenti. Setelah selesai, kasih judul, tempel foto dari perjalanan, lalu simpan.

### 📍 Waypoint: catat titik penting
Spring water, persimpangan, spot foto bagus, pos pengamen — tandai semuanya di peta. Waypoint dari file GPX otomatis muncul juga, lengkap dengan namanya. Cukup tahan jari di peta untuk membuat titik baru dengan ikon kategori.

### 🎨 Pilih peta sesuai seleramu
Belasan pilihan basemap dunia: OpenStreetMap, OpenTopoMap, CyclOSM, peta hiking, citra satelit, dan lainnya — plus lapisan overlay jalur pendakian (Waymarked Trails) dan **"Jalur Saya"** yang menampilkan semua rute tersimpanmu di atas peta. Pilihanmu diingat, bahkan setelah aplikasi ditutup.

### 🖼️ Bagikan dengan bangga
Buat kartu aktivitas yang bisa langsung dibagikan ke story/grup — jarak, waktu, dan peta jalur yang kamu taklukkan. Rute juga bisa diekspor kembali ke GPX buat dibagikan ke rekan seperjalanan.

### 🧭 Lain-lain yang bikin nyaman
- Gambar rute sendiri langsung di peta (tanpa file GPX sekalipun)
- Kompas dengan arah hadap, tombol recenter pintar
- Riwayat aktivitas dengan detail lengkap dan profil elevasi
- Tema terang/gelap, bahasa Indonesia/Inggris, satuan km/h atau mph

---

## 🚀 Cara Mulai (Android 8.0+)

1. **Unduh APK Nyasar** dari tab [Actions](https://github.com/akhyarulf/nyasar/actions) repo ini: buka run terbaru yang hijau (berhasil), masuk ke halaman run-nya, lalu unduh artifact `nyasar-debug-apk` di bagian bawah. Ekstrak zip-nya untuk mendapatkan file APK.
2. Buka file APK-nya di HP. Kalau muncul peringatan, izinkan *"Install dari sumber tidak dikenal"* — normal untuk aplikasi di luar Play Store.
3. Berikan izin **lokasi** saat diminta (wajib, karena inti aplikasi adalah GPS). Izin notifikasi opsional.
4. Impor GPX pertamamu dari tab **Library**, atau langsung buka file GPX dari aplikasi lain dengan *"Open with → Nyasar"*.
5. Selamat menjelajah. Dan semoga nggak jadi nyasar. 🧭

### Soal kunci MapTiler (opsional)
Beberapa pilihan gaya peta berjalan lewat layanan MapTiler yang butuh kunci gratis dari [cloud.maptiler.com](https://cloud.maptiler.com). **Tanpa kunci apa pun, aplikasi tetap berfungsi penuh** — otomatis memakai OpenFreeMap. Pasang kunci hanya jika kamu ingin membuka pilihan gaya peta dari MapTiler.

---

## ❓ Tanya Jawab

**Apakah butuh internet saat di gunung?**
Tidak — selama kamu sudah mengunduh area peta sebelumnya. GPS bekerja tanpa internet; internet hanya untuk mengunduh tile peta.

**Data saya dikirim ke mana?**
Tidak ke mana-mana. Rute, rekaman, foto, dan waypoint tersimpan hanya di penyimpanan HP-mu. Nyasar tidak punya server dan tidak butuh akun.

**Kenapa posisi GPS saya "melompat"?**
Di antara tebing atau kanopi lebat, GPS memang bisa kurang akurat. Nyasar menampilkan estimasi akurasi (±X m) supaya kamu bisa menilai sendiri seberapa percaya pada posisi tersebut.

**Apa bedanya biru dan hijau di peta?**
Jalur rute terencana digambar biru; jejak aktual yang terekam saat aktivitas digambar hijau — jadi kamu bisa membandingkan rencana vs kenyataan.

**Hape saya Android 7 bisa?**
Mohon maaf, Nyasar butuh Android 8.0 (Oreo) ke atas.

**Berapa banyak memori untuk peta offline?**
Tergantung area yang kamu unduh — satu gunung umumnya cuma puluhan MB. Bisa dilihat dan dihapus kapan saja dari menu peta offline.

---

## 🙏 Kredit

- Peta, tile, dan data geografis: © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright), [OpenTopoMap](https://opentopomap.org), [CyclOSM](https://cyclosm.org), [Waymarked Trails](https://waymarkedtrails.org), [OpenFreeMap](https://openfreemap.org), dan [MapTiler](https://www.maptiler.com) — masing-masing sesuai ketentuan lisensinya.
- Mesin peta: [MapLibre GL Native](https://maplibre.org) — open source.
- Nyasar mengidentifikasi diri pada server peta dengan User-Agent khusus, sesuai kebijakan penggunaan tile OpenStreetMap.

Nyasar adalah bagian dari keluarga besar **Nyasar Nyaman**. Dibuat dengan ❤️ oleh [akhyarulf](https://github.com/akhyarulf) dan kontributor.

---

## 🛠️ Untuk Pengembang

Kamu bisa membangun Nyasar dari kode sumber:

```bash
# butuh Android Studio (Koala+) atau Android SDK 34
git clone https://github.com/akhyarulf/nyasar.git
cd nyasar
# opsional: isi MAPTILER_API_KEY di local.properties
./gradlew assembleDebug        # APK debug di app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # jalankan unit test
```

Stack singkat: Kotlin + Jetpack Compose, MapLibre GL, Room, dan arsitektur offline-first. Setiap push ke `main` otomatis di-build oleh GitHub Actions (APK debug + AAB rilis).

Punya ide fitur atau menemukan bug? [Buka issue](https://github.com/akhyarulf/nyasar/issues) — kontribusi sangat diterima!
