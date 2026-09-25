<!--
  Template email Supabase — Nyasar (bilingual dalam SATU email).

  KENAPA GINI: Supabase cuma menyimpan SATU template per event (bukan satu
  per bahasa). Solusi: body berisi versi ID dulu, lalu separator + versi EN.
  Supabase juga punya variable {{ .ConfirmationURL }} dsb — biarkan utuh,
  Supabase yang mengganti saat kirim. Link otomatis mengarah ke Site URL
  (URL Configuration), jadi PASTIKAN Site URL = https://app.nyasarnyaman.my.id

  CARA PAKAI: Authentication → Emails → Templates → pilih event →
  paste sisi <body> template terkait → Save. Style inline disengaja
  (klien email seperti Gmail men-strip <style> global).
-->

<!-- ══════════════════════════════════════════════════════════════
     1) CONFIRM SIGNUP  (Authentication → Templates → Confirm signup)
     ══════════════════════════════════════════════════════════════ -->
<!-- Subject ID:  Konfirmasi email Nyasar-mu -->
<!-- Subject EN:  Confirm your Nyasar email -->
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#F2F5F0;padding:24px 12px;font-family:-apple-system,Segoe UI,Roboto,sans-serif;">
<tr><td align="center">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width:480px;background:#FFFFFF;border-radius:14px;padding:28px;">
  <tr><td style="font-size:22px;font-weight:700;color:#1A3522;padding-bottom:6px;">Selamat datang di Nyasar ⛰️</td></tr>
  <tr><td style="font-size:15px;color:#414941;line-height:1.6;padding-bottom:18px;">
    Satu langkah lagi — konfirmasi email-mu supaya akun pendakianmu aktif.
  </td></tr>
  <tr><td align="center" style="padding-bottom:22px;">
    <a href="{{ .ConfirmationURL }}" style="display:inline-block;background:#2E5339;color:#FFFFFF;text-decoration:none;font-weight:700;font-size:15px;padding:12px 28px;border-radius:10px;">Konfirmasi Email Saya</a>
  </td></tr>
  <tr><td style="font-size:14px;color:#414941;line-height:1.6;border-top:1px solid #E3EAE0;padding-top:16px;">
    <strong>One more step (English)</strong> — confirm your email to activate your Nyasar hiking account:
  </td></tr>
  <tr><td align="center" style="padding-bottom:8px;">
    <a href="{{ .ConfirmationURL }}" style="display:inline-block;background:#2E5339;color:#FFFFFF;text-decoration:none;font-weight:700;font-size:15px;padding:12px 28px;border-radius:10px;">Confirm My Email</a>
  </td></tr>
  <tr><td style="font-size:12px;color:#6F7971;line-height:1.6;padding-top:10px;">
    Kalau kamu tidak merasa mendaftar, abaikan email ini. / If you didn't sign up, just ignore this email.
  </td></tr>
</table>
</td></tr></table>

<!-- ══════════════════════════════════════════════════════════════
     2) RESET PASSWORD  (Templates → Reset password)
     PENTING: di Supabase Dashboard, set Redirect URL template ini ke
     https://app.nyasarnyaman.my.id/auth/reset/  (halaman form reset kami)
     ══════════════════════════════════════════════════════════════ -->
<!-- Subject ID:  Atur ulang kata sandi Nyasar -->
<!-- Subject EN:  Reset your Nyasar password -->
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#F2F5F0;padding:24px 12px;font-family:-apple-system,Segoe UI,Roboto,sans-serif;">
<tr><td align="center">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width:480px;background:#FFFFFF;border-radius:14px;padding:28px;">
  <tr><td style="font-size:22px;font-weight:700;color:#1A3522;padding-bottom:6px;">Lupa kata sandi? Biasa 😄</td></tr>
  <tr><td style="font-size:15px;color:#414941;line-height:1.6;padding-bottom:18px;">
    Klik tombol di bawah untuk membuat kata sandi baru. Tautan berlaku 1 jam.
  </td></tr>
  <tr><td align="center" style="padding-bottom:22px;">
    <a href="{{ .ConfirmationURL }}" style="display:inline-block;background:#2E5339;color:#FFFFFF;text-decoration:none;font-weight:700;font-size:15px;padding:12px 28px;border-radius:10px;">Atur Ulang Kata Sandi</a>
  </td></tr>
  <tr><td style="font-size:14px;color:#414941;line-height:1.6;border-top:1px solid #E3EAE0;padding-top:16px;">
    <strong>English</strong> — tap the button below to set a new password. The link expires in 1 hour:
  </td></tr>
  <tr><td align="center" style="padding-bottom:8px;">
    <a href="{{ .ConfirmationURL }}" style="display:inline-block;background:#2E5339;color:#FFFFFF;text-decoration:none;font-weight:700;font-size:15px;padding:12px 28px;border-radius:10px;">Reset My Password</a>
  </td></tr>
  <tr><td style="font-size:12px;color:#6F7971;line-height:1.6;padding-top:10px;">
    Kalau bukan kamu yang meminta, abaikan email ini — kata sandimu tetap aman. / If you didn't request this, ignore this email — your password stays unchanged.
  </td></tr>
</table>
</td></tr></table>

<!-- ══════════════════════════════════════════════════════════════
     3) CHANGE EMAIL ADDRESS  (Templates → Change email address)
     ══════════════════════════════════════════════════════════════ -->
<!-- Subject ID:  Konfirmasi email baru Nyasar-mu -->
<!-- Subject EN:  Confirm your new Nyasar email -->
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#F2F5F0;padding:24px 12px;font-family:-apple-system,Segoe UI,Roboto,sans-serif;">
<tr><td align="center">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width:480px;background:#FFFFFF;border-radius:14px;padding:28px;">
  <tr><td style="font-size:22px;font-weight:700;color:#1A3522;padding-bottom:6px;">Ganti Email Nyasar</td></tr>
  <tr><td style="font-size:15px;color:#414941;line-height:1.6;padding-bottom:18px;">
    Konfirmasi email ini supaya alamat email akunmu diganti ke sini.
  </td></tr>
  <tr><td align="center" style="padding-bottom:22px;">
    <a href="{{ .ConfirmationURL }}" style="display:inline-block;background:#2E5339;color:#FFFFFF;text-decoration:none;font-weight:700;font-size:15px;padding:12px 28px;border-radius:10px;">Konfirmasi Email Baru</a>
  </td></tr>
  <tr><td style="font-size:14px;color:#414941;line-height:1.6;border-top:1px solid #E3EAE0;padding-top:16px;">
    <strong>English</strong> — confirm this email to finish changing your Nyasar account email:
  </td></tr>
  <tr><td align="center" style="padding-bottom:8px;">
    <a href="{{ .ConfirmationURL }}" style="display:inline-block;background:#2E5339;color:#FFFFFF;text-decoration:none;font-weight:700;font-size:15px;padding:12px 28px;border-radius:10px;">Confirm New Email</a>
  </td></tr>
  <tr><td style="font-size:12px;color:#6F7971;line-height:1.6;padding-top:10px;">
    Bukan kamu? Abaikan email ini dan akunmu tetap pakai email lama. / Not you? Ignore this email and your account keeps the old address.
  </td></tr>
</table>
</td></tr></table>
