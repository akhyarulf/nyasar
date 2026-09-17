-- ============================================================
-- 0005 — Like & comment counters untuk UI sosial Browse (Fase 2+).
--
-- route_likes / route_comments sudah ada sejak schema_v1 (RLS lengkap),
-- dan routes sudah punya kolom likes_count / comments_count default 0 —
-- tapi TIDAK ADA yang menjaganya. Tanpa trigger ini, counter itu
-- mati di 0 selamanya: UI like/komentar tidak akan pernah akurat.
--
-- Pola counter-maintaining trigger (bukan hitung on-read):
--  - routes.likes_count dipakai ORDER BY di browse (idx_routes_likes),
--    jadi harus berupa kolom tersimpan, bukan perhitungan live.
--  - Trigger AFTER INSERT/DELETE menjaga counter atomik di sisi DB,
--    aman terhadap race dua user like bersamaan.
--
-- Idempotent: drop function dulu sebelum create, jadi migration ini
-- boleh dijalankan ulang tanpa error.
-- ============================================================

-- ---- likes_count ----

create or replace function public.handle_route_like_change()
returns trigger
language plpgsql
security definer set search_path = public
as $$
begin
  if (tg_op = 'INSERT') then
    update routes set likes_count = coalesce(likes_count, 0) + 1 where id = new.route_id;
    return new;
  elsif (tg_op = 'DELETE') then
    update routes set likes_count = greatest(coalesce(likes_count, 0) - 1, 0)
    where id = old.route_id;
    return old;
  end if;
  return null;
end;
$$;

drop trigger if exists on_route_like_change on route_likes;
create trigger on_route_like_change
  after insert or delete on route_likes
  for each row execute function public.handle_route_like_change();

-- ---- comments_count ----

create or replace function public.handle_route_comment_change()
returns trigger
language plpgsql
security definer set search_path = public
as $$
begin
  if (tg_op = 'INSERT') then
    update routes set comments_count = coalesce(comments_count, 0) + 1 where id = new.route_id;
    return new;
  elsif (tg_op = 'DELETE') then
    update routes set comments_count = greatest(coalesce(comments_count, 0) - 1, 0)
    where id = old.route_id;
    return old;
  end if;
  return null;
end;
$$;

drop trigger if exists on_route_comment_change on route_comments;
create trigger on_route_comment_change
  after insert or delete on route_comments
  for each row execute function public.handle_route_comment_change();

-- ---- Backfill: row yang dibuat SEBELUM trigger ini ada ----
-- Set counter = jumlah aktual like/komentar. Idempotent (selalu benar
-- kapan pun dijalankan); trigger menutup semua perubahan SETELAH ini.

update routes r set
  likes_count = (select count(*) from route_likes l where l.route_id = r.id),
  comments_count = (select count(*) from route_comments c where c.route_id = r.id);
