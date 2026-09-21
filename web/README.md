# Landing & App Links — app.nyasarnyaman.my.id

Folder ini adalah sumber website statis Nyasar yang di-host **GitHub Pages**
(di domain `app.nyasarnyaman.my.id`) sekaligus tempat verifikasi
**Android App Links** agar link web bisa langsung membuka aplikasi.

Struktur:

```
web/
  CNAME                        → app.nyasarnyaman.my.id (domain custom Pages)
  index.html                   → landing utama
  browse/index.html            → jelajah rute publik (grid kartu, tanpa app)
  route/index.html             → landing ringkas per rute publik (?id=…)
  privacy-policy.html          → kebijakan privasi
  terms-of-service.html        → syarat layanan
  og-image.png                 → kartu share sosial (og:image; digenerate
                                 scripts/gen_og_image.py, 1200x630)
  robots.txt + sitemap.xml     → pengindeksan mesin pencari
  .well-known/assetlinks.json  → verifikasi App Links (isi SHA-256 cert)
```

## Alur link

`https://app.nyasarnyaman.my.id/route?id=<routeId>`

- HP **dengan** app Nyasar → Android membuka app langsung ke Route Detail
  (tanpa dialog pilih aplikasi) berkat `autoVerify` di AndroidManifest +
  assetlinks.json yang cocok.
- HP **tanpa** app → dibuka browser: landing rute lengkap (peta jalur
  Leaflet, statistik jarak/naik/turun/elevasi/waktu, deskripsi, badge
  kesulitan, tombol unduh GPX, ajakan install — bahasa ID/EN).

## Langkah setup (sekali jalan)

1. **DNS di registrar domainmu** (Cloudflare/dst):
   - Apex `nyasarnyaman.my.id`: 4 A-record GitHub Pages:
     `185.199.108.153`, `185.199.109.153`, `185.199.110.153`, `185.199.111.153`
   - Subdomain: CNAME `app` → `<username-github>.github.io`
2. **GitHub repo → Settings → Pages**:
   - Source: *GitHub Actions* (workflow `pages-deploy.yaml` yang mengerjakan
     deploy, tidak perlu pilih branch)
   - Custom domain: isi `app.nyasarnyaman.my.id`, centang **Enforce HTTPS**
     setelah sertifikat selesai diterbitkan.
3. **Isi SHA-256 certificate di `web/.well-known/assetlinks.json`:**
   - Debug cert: SUDAH TERISI otomatis — debug keystore di-commit di
     `keystore/debug.keystore` (alias `androiddebugkey`, pass `android`) dan
     semua debug build (lokal + CI) memakainya, jadi SHA-256-nya stabil.
     Regenerasi hanya kalau mau rotasi: `python3 scripts/gen_debug_keystore.py`.
   - Release cert: workflow "Release APK (GitHub Releases)"
     (`release-apk.yaml`) mencetaknya di log setiap rilis (step "Print
     release cert SHA-256") — copy dari sana. Workflow AAB lama sudah
     dihapus (distribusi tidak lewat Play Store).
   - Ganti placeholder release cert (debug sudah terisi dari keystore
     committed), commit, workflow Pages akan deploy ulang otomatis.
4. **Secrets repo** (Settings → Secrets and variables → Actions):
   - `SUPABASE_URL` — URL project Supabase (https://xxx.supabase.co)
   - `SUPABASE_ANON_KEY` — anon/publishable key (memang publik, aman
     di-suntik ke halaman)
   Tanpa ini halaman route tetap jalan tapi tanpa detail (fallback).
5. **Jalankan workflow** "Deploy web landing (GitHub Pages)" (Actions tab →
   Run workflow) atau cukup push yang menyentuh `web/`.

## Verifikasi

- `https://app.nyasarnyaman.my.id/` → landing utama.
- `https://app.nyasarnyaman.my.id/.well-known/assetlinks.json` → JSON
  tampil (ini yang dicek Android saat verifikasi autoVerify).
- Statement verifikasi per-link (opsional): di HP, Settings → Apps →
  Nyasar → Open by default → "Add link" / cek verified.
- Test: kirim link `/route?id=…` dari share rute ke HP lain yang terpasang
  app → harus langsung buka app di Route Detail.
