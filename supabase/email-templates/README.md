# Template email Supabase (salin-tempel)

Isi folder ini adalah HTML lengkap untuk editor email Supabase. Tujuannya
satu: email yang dikirim app **selalu memuat 6 digit kode** (supaya layar
"Masuk dengan kode" bisa diisi) **dan** tautan yang membuka app.

| File | Dashboard | Subject yang dipakai |
| --- | --- | --- |
| `magic-link.html` | Authentication → Emails → **Magic Link** | `Kode masuk Nyasar kamu` |
| `confirm-signup.html` | Authentication → Emails → **Confirm signup** | `Konfirmasi email Nyasar kamu` |

Dua-duanya wajib diubah. Template yang dipakai bukan dipilih app, tapi
ditentukan server: alamat yang **sudah terdaftar** dapat *Magic Link*,
alamat **baru** dapat *Confirm signup* (karena *Confirm email* aktif).

## Cara memasang

1. Buka satu template di dashboard, pilih tab **Source**.
2. Hapus semua isinya, tempel seluruh isi file di sini (mulai dari
   komentar `<!--` sampai `</table>` terakhir).
3. Ganti **Subject** sesuai tabel di atas.
4. **Save**, lalu ulangi untuk template kedua.

## Setelan pendamping (sekali saja)

- Authentication → **URL Configuration → Redirect URLs**: tambahkan
  `https://app.nyasarnyaman.my.id/auth/callback`. Tanpa ini, tautan di
  email tidak bisa membuka app (Supabase diam-diam memakai Site URL).
- Authentication → Emails → **SMTP Settings**: pasang Resend/Brevo. SMTP
  bawaan hanya menerima alamat tim proyek dan dibatasi ~2 email/jam.
- Authentication → Providers → Email → **Email OTP Length = 6**. App
  hanya menerima 6 digit.
- Authentication → Providers → Email → **Email OTP Expiration = 3600**
  (kecocokan dengan teks "berlaku 1 jam" di template).

## Variabel yang dipakai

| Variabel | Isi |
| --- | --- |
| `{{ .Token }}` | 6 digit kode (angka saja, tanpa spasi) |
| `{{ .ConfirmationURL }}` | tautan verifikasi sekali pakai |
| `{{ .Email }}` | alamat penerima |

Semua CSS ditulis inline dan layout-nya memakai `<table>`, karena Gmail
membuang `<style>` dan sebagian klien email lama tidak mengenal flexbox.
Warna mengikuti tema app: hijau sage `#5A7562`, hijau hutan `#2E5339`,
latar kertas `#F4F1EA`.

## Kalau mau versi Inggris

Ganti tiga teks: judul `<h1>`, paragraf pertama, dan teks tombol. Sisanya
tidak berbahasa, jadi tidak perlu file terpisah — Supabase hanya punya satu
template per jenis email, tidak bisa memilih bahasa per pengguna.

## Menguji

1. Kirim kode dari app dengan email yang sudah terdaftar → cek email
   memuat 6 digit **dan** tombol.
2. Ketik kodenya → harus masuk.
3. Minta kode lagi, kali ini **ketuk** tombolnya dari HP → app terbuka dan
   langsung masuk (tanpa mengetik).
4. Ulangi dengan alamat email baru → harus dapat *Confirm signup* dan
   keduanya juga jalan.

Kode yang sama tidak bisa dipakai dua kali; kalau sudah dipakai atau lewat
1 jam, minta kode baru.
