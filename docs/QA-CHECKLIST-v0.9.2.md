# Checklist Uji Manual — v0.9.2

Perangkat uji minimal: 1 HP dengan Android 8+, lokasi & internet aktif.
Semua butir dikerjakan dengan app versi build dari branch/tag v0.9.2.

## Ringkasan rilis (apa yang berubah)
- Auto-resume setelah reboot: recording AKTIF dilanjutkan otomatis
  (~1–2 menit setelah boot, guard usia ≤ 24 jam). PAUSED tetap PAUSED.
- Konfirmasi ramah sebelum keluar dari layar rekam selagi recording jalan.
- Pesan error/sukses storage, notifikasi, dan status peta offline lebih
  manusiawi; parity pesan ID/EN (SettingsViewModel kini memakai resources).

## QA Steps

### 1. Reboot saat recording jalan
- [ ] Mulai recording (pastikan status ● RECORDING + jarak bertambah).
- [ ] Tunggu notifikasi "Recording aktif" muncul.
- [ ] Reboot HP.
- [ ] Tunggu ~1–2 menit → notifikasi "Memulihkan recording…" lalu "Recording aktif".
- [ ] Buka app → sesi sama dilanjutkan (jarak bertambah, bukan 0 km).

### 2. Force-Stop masih memblokir (expected behavior)
- [ ] Settings sistem → Force-Stop app → reboot HP.
- [ ] Expected: TIDAK auto-resume sampai app dibuka sekali lagi
      (perilaku Android, bukan bug — tercatat di PROJECT_CONTEXT.md).

### 3. Share GPX via share card
- [ ] Selesaikan recording → Post-Recording → Share card → share GPX.
- [ ] File GPX terbuka/tersimpan dengan benar dari sheet share (WA/TG/Drive).

### 4. Satuan & format waktu konsisten
- [ ] Layar rekam: km/h + mph tampil penuh (tidak terpotong).
- [ ] Jam/waktu aktivitas: format HH:MM (jam+menit) konsisten antara
      layar rekam, riwayat, dan share card; isi bahasa mengikuti
      pilihan LANGUAGE di Settings (ganti ID↔EN, cek 1 layar per bahasa).

### 5. GPS / lokasi dari awal
- [ ] Buka app baru (sudah keluar proses) → izin lokasi + notifikasi
      muncul bila belum diberikan.
- [ ] Layar rekam: banner "Mencari sinyal GPS…" tampil sampai lock;
      tidak ada layar sepi tanpa teks.

### 6. Keluar dari layar rekam saat recording jalan
- [ ] Tekan tombol back melayang / gesture back saat ● RECORDING →
      muncul "Rekaman masih berjalan" dengan opsi Keluar / Lanjut rekam.
- [ ] "Lanjut rekam" → tetap di layar, recording tidak terputus.
- [ ] "Keluar" → keluar, recording tetap jalan di background (cek notifikasi).

### 7. Update-check (kalau ada rilis baru setelah ini)
- [ ] Setelah versi selanjutnya rilis di GitHub, buka app → dialog
      "Update tersedia" muncul dengan tautan halaman rilis.

### 7b. Perilaku baru — rilis cepat v0.9.3 (tambahan)
- [ ] Tombol back saat ● RECORDING punya titik merah kecil.
- [ ] Dengan baterai ≤ 15% (tidak di-charge): banner "Baterai XX%" tampil
      di layar SIAP; hilang saat charging / baterai naik; tidak bertumpuk
      dengan banner storage/location lain.
- [ ] Dialog keluar saat jarak sudah > 50 m menyebut jarak sejauh ini —
      satuannya mengikuti pilihan Speed Unit (km/mi), bukan selalu km.
- [ ] Setelah pilih "Keluar" dari dialog: toast "rekaman tetap jalan di
      background" muncul, dan notifikasi recording masih aktif.
- [ ] Settings → Tentang → "Salin Info Debug": clipboard berisi versi
      Nyasar, Android + model HP, dan bahasa aktif, plus konfirmasi toast.
- [ ] Form review pasca-stop: angka kecepatan menyesuaikan satuan yang
      dipilih di Settings (ganti ke mph lalu cek lagi).

### 7c. Badge update (B21)
- [ ] Setelah app baca ada versi lebih baru (butuh rilis terpasang > versi
      app): baris "Pembaruan vX tersedia" tampil di Settings → Tentang dan
      TETAP ADA setelah dialog update ditutup "Nanti Saja".
- [ ] Ketuk baris = browser membuka tautan unduhan Nyasar.apk (GitHub
      Releases), bukan crash/halaman kosong.
- [ ] Di build dengan versi terbaru (tidak ada tag lebih baru): baris TIDAK
      tampil.

### 7d. Poin 1–6 (saran batch sebelumnya)
- [ ] Riwayat: filter Olahraga + chip bulan bisa dipilih bersamaan (AND),
      hasil terfilter benar; toggle chip → "Semua" mengembalikan tampilan
      penuh; kombinasi tanpa hasil menampilkan state "tidak ada yang
      cocok" + tombol "Hapus filter".
- [ ] Riwayat: chip bulan hanya menampilkan bulan yang benar-benar ada
      data; label bulan format "Januari 2026" (EN: "January 2026").
- [ ] Login di perangkat baru (atau proses baru) dengan data cloud:
      dialog ringkasan "Data dipulihkan dari cloud" muncul SEKALI dengan
      jumlah rute/aktivitas/dilewati; tutup → tidak muncul lagi di proses
      yang sama; login tanpa data baru dipulihkan → tidak ada dialog.
- [ ] Layar rekam IDLE dengan rute terpasang yang titik awalnya di luar
      area offline lengkap: banner peringatan + tombol "Unduh Area"
      muncul; setelah area diunduh (atau rute tanpa rute) banner hilang;
      banner tidak menumpuk dengan banner storage/location/baterai.
- [ ] Simpan aktivitas yang titik awalnya < 50 m dari titik awal rute
      milik sendiri: dialog "Rute serupa terdeteksi" muncul sebelum
      save; "Batal" = tidak ada yang tersimpan, form tetap terbuka;
      "Tetap Simpan" = save+publish jalan normal.
- [ ] Aktivitas yang direkam mengikuti sebuah rute (routeId terpasang)
      TIDAK pernah memunculkan dialog rute serupa.
- [ ] EN: semua teks baru (filter, dialog restore, banner offline,
      dialog rute serupa) berbahasa Inggris tanpa campur Indonesia.

### 7e. Paket C — search, sort, & statistik riwayat
- [ ] Kolom "Cari aktivitas" menyaring daftar berdasarkan nama (ketik
      sebagian nama → hanya yang cocok tampil; hapus teks → daftar penuh).
- [ ] Pencarian tanpa hasil → state "tidak ada yang cocok"; tombol
      "Hapus filter" menghapus pencarian juga (daftar penuh kembali).
- [ ] Menu urut (ikon di kanan kolom search): Terbaru (default), Terlama,
      Terjauh — urutan kartu berubah sesuai; berlaku bersama filter
      olahraga/bulan (bukan menggantikan).
- [ ] Baris ringkasan di atas daftar menampilkan jumlah aktivitas + total
      km dari yang sedang tampil; saat filter bulan aktif menyebut bulannya
      (mis. "November 2026 · 3 aktivitas · 42,50 km").
- [ ] Riwayat kosong: kolom search TIDAK tampil; EmptyState "mulai rekam"
      tetap seperti biasa.
- [ ] EN: teks search/menu urut/ringkasan berbahasa Inggris; label bulan
      tampil "January 2026" (bukan "Januari 2026"); plural "1 activity"
      vs "2 activities" benar.

### 7f. Opsi rekaman (sheet gear) + peringatan menyimpang
- [ ] Ikon gear di kanan atas layar rekam (di bawah kompas) membuka sheet
      "Opsi rekaman" berisi 3 baris: Jeda otomatis, Layar tetap nyala,
      Peringatan menyimpang — masing-masing dengan switch.
- [ ] Ubah tiap switch di sheet → efeknya nyata DAN nilai di Settings
      ikut berubah (satu DataStore, bukan dua state terpisah):
      · Jeda otomatis OFF → rekam sambil diam tidak menjeda sesi;
      · Layar tetap nyala OFF → layar mati sendiri saat merekam;
      · Peringatan menyimpang OFF → tidak ada banner hint sama sekali.
- [ ] Default "Peringatan menyimpang" = OFF (install bersih / data app
      dihapus): rekam dengan rute lalu menjauh jauh → TIDAK ada banner.
      Nyalakan switch di sheet, ulangi → banner muncul.
- [ ] Saat switch ON dan menjauh > 60 m dari jalur → banner
      "Menyimpang XX m dari jalur" muncul (warna amber/tertiary, BUKAN
      merah); kembali ke jalur → banner hilang sendiri.
- [ ] Banner hint TIDAK muncul saat IDLE/PAUSED, saat tanpa rute
      terpasang, atau saat banner storage/GPS sedang tampil (satu lane,
      tidak menumpuk).
- [ ] Ketuk "Diamkan" di banner → banner hilang dan switch "Peringatan
      menyimpang" di sheet ikut OFF (persisten setelah app dibuka ulang).
- [ ] Switch "Peringatan menyimpang" juga ada di Settings → Rekaman
      dengan catatan kaki ambang 60 m; ubah dari Settings → nilai di
      sheet gear ikut berubah (dua pintu, satu pref).
- [ ] EN: judul sheet, ketiga baris + penjelasannya, banner hint, dan
      tombol "Mute" berbahasa Inggris tanpa campur Indonesia.

### 8. Pesan lokal (bilingual)
- [ ] Ganti bahasa ke EN → Settings → Data → hapus data lokal → dialog
      Konfirmasi → sukses: footer berbahasa yang dipilih (bukan campuran).
