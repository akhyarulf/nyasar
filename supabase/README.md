# Supabase Migrations — Nyasar

Skema lengkap (v1: 8 tabel + trigger) live di Supabase project dan **tidak
di-commit ke repo ini** — hanya migration TAMBAHAN yang dikelola di sini,
dijalankan manual lewat Supabase Dashboard → SQL Editor (pola yang sama
dengan migration `username_is_set` sebelumnya).

| File | Fungsi | Status |
|---|---|---|
| `migrations/0002_delete_own_account.sql` | RPC `delete_own_account()` untuk fitur Hapus Akun dari dalam app (app sengaja TIDAK punya service-role key) | ⏳ **Perlu dijalankan manual oleh user** |

## Cara menjalankan

1. Buka Supabase Dashboard → SQL Editor.
2. Copy seluruh isi file migration yang belum dijalankan.
3. Paste → Run. Tidak perlu redeploy app untuk SQL ini; sisi Kotlin sudah
   menangani kondisi "RPC belum ada" dengan pesan khusus
   (`account_delete_rpc_missing`).
4. Verifikasi cepat: `select proname from pg_proc where proname = 'delete_own_account';`
   harus mengembalikan 1 baris.

## Catatan audit trigger `handle_new_user` (Login Google)

Ditambahkan sebelum menghubungkan Login Google: jika trigger yang sekarang
live mengambil username dari `split_part(new.email, '@', 1)`, user Google
**yang tanpa email** (jarang, tapi sah pada OAuth) akan membuat trigger
gagal dan login Google gagal total. Guard satu baris yang direkomendasikan
(boleh digabung saat menjalankan 0002):

```sql
create or replace function public.handle_new_user()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  insert into public.profiles (id, username)
  values (
    new.id,
    coalesce(
      nullif(regexp_replace(lower(split_part(new.email, '@', 1)), '[^a-z0-9_]', '', 'g'), ''),
      'user'
    ) || '_' || substr(md5(random()::text), 1, 6)
  )
  on conflict (id) do nothing;
  return new;
end;
$$;
```

(Cocokkan isi SELECT/kolomnya dengan definisi trigger live-mu — yang penting
email di-guard `coalesce(..., 'user')` agar NULL-safe untuk provider mana pun.)
