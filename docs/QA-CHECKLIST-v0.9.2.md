# Checklist Uji Manual — v0.9.2

Perangkat uji minimal: 1 HP dengan Android 8+ , lokasi & internet aktif.
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

### 8. Pesan lokal (bilingual)
- [ ] Ganti bahasa ke EN → Settings → Data → hapus data lokal → dialog
      Konfirmasi → sukses: footer berbahasa yang dipilih (bukan campuran).
