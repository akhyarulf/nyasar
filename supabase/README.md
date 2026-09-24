# Supabase Migrations — Nyasar

Skema lengkap (v1: 8 tabel + trigger) live di Supabase project dan **tidak
di-commit ke repo ini** — hanya migration TAMBAHAN yang dikelola di sini,
dijalankan manual lewat Supabase Dashboard → SQL Editor (pola yang sama
dengan migration `username_is_set` sebelumnya).

| File | Fungsi | Status |
|---|---|---|
| `migrations/0002_delete_own_account.sql` | RPC `delete_own_account()` untuk fitur Hapus Akun dari dalam app (app sengaja TIDAK punya service-role key) | ✅ LIVE (diverifikasi 2026-09-21) |
| `migrations/0003_route_gpx_bucket.sql` | Bucket publik `route-gpx` + kebijakan upload/select untuk GPX hasil publish (Fase 2 "full open") | ✅ LIVE |
| `migrations/0004_drop_mountain_name_and_region.sql` | Hapus kolom `routes.mountain_name` + `routes.region` beserta 2 index-nya | ✅ LIVE |
| `migrations/0005_like_comment_counters.sql` | Trigger counter `likes_count`/`comments_count` di tabel routes (Fase 4) | ✅ LIVE |
| `migrations/0006_private_gpx_bucket.sql` | Bucket privat `route-gpx-private` untuk rute visibilitas "Hanya saya" | ✅ LIVE |
| `migrations/0007_realtime_replication.sql` | Masukkan `routes`, `route_likes`, `saved_routes`, `route_comments` ke publication `supabase_realtime` + replica identity FULL — WAJIB untuk auto-refresh realtime (tanpa ini channel connect tapi diam) | ⏳ **Perlu dijalankan manual oleh user** |

## Cara menjalankan

1. Buka Supabase Dashboard → SQL Editor.
2. Copy seluruh isi file migration yang belum dijalankan.
3. Paste → Run. Tidak perlu redeploy app untuk SQL ini; sisi Kotlin sudah
   menangani kondisi "RPC belum ada" dengan pesan khusus
   (`account_delete_rpc_missing`).
4. Verifikasi cepat: `select proname from pg_proc where proname = 'delete_own_account';`
   harus mengembalikan 1 baris.

### Verifikasi khusus 0007 (realtime)

```sql
-- Harus mengembalikan 4 baris: routes, route_likes, saved_routes, route_comments
select tablename from pg_publication_tables
where pubname = 'supabase_realtime' and schemaname = 'public';
```

File 0007 idempoten — aman dijalankan ulang (ADD TABLE di-skip untuk tabel
yang sudah jadi anggota publication). Setelah dijalankan, tanpa perlu update
app: client subscribe `nyasar-cloud-sync` langsung mulai menerima event dan
Browse/Saved/Route Detail refresh sendiri secara realtime.

## Catatan audit trigger `handle_new_user` (Login Google)

DIVERIFIKASI ke `schema_v1.sql` yang live: trigger memakai
`split_part(new.email, '@', 1)` untuk username DAN display_name. User
OAuth **tanpa email** (sah secara protokol, walau Google praktis selalu
mengirim email) membuat `split_part` → NULL → insert `profiles` gagal
(NOT NULL) → **signup gagal total**. Guard yang exact-match trigger live
(opsional, disarankan dijalankan bareng migrasi 0002):

```sql
create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer set search_path = public
as $$
begin
  insert into public.profiles (id, username, display_name)
  values (
    new.id,
    coalesce(nullif(split_part(new.email, '@', 1), ''), 'user') || '_' || substr(new.id::text, 1, 6),
    coalesce(nullif(split_part(new.email, '@', 1), ''), 'Pendaki')
  );
  return new;
end;
$$;
```

Format username sengaja TIDAK diubah (`emailprefix_id6`, sesuai desain
live) — cuma email-nya yang di-guard `coalesce` supaya NULL-safe untuk
provider mana pun.
