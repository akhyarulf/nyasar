-- ============================================================
-- 0007 — Realtime replication for auto-refresh (2026-09)
--
-- Requirement: "semua real time di app, gaada drama harus restart
-- app" — dan tanpa pull-to-refresh. Client (CloudSyncSignals)
-- subscribe ke event INSERT/UPDATE/DELETE via Supabase Realtime;
-- Supabase MATIKAN replication untuk tabel baru by default, jadi
-- tanpa file ini channel-nya connect tapi tidak pernah menerima
-- event apa pun (silent no-refresh — bug paling membingungkan).
--
-- Empat tabel publik yang dirender Browse/Saved/Route Detail:
--   routes         — list browse + likes_count/comments_count (trigger 0005)
--   route_likes    — heart state
--   saved_routes   — bookmark state
--   route_comments — thread komentar
--
-- Semuanya sudah public-read via RLS (schema_v1), jadi publication
-- ini tidak membuka data baru — hanya mengalirkan apa yang memang
-- boleh dibaca subscriber tersebut.
--
-- Jalankan di Supabase Dashboard → SQL Editor (sama seperti 0002–0006).
-- ============================================================

begin;

-- Publication khusus app (idempoten untuk re-run manual).
do $$
begin
  if not exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
    create publication supabase_realtime;
  end if;
end $$;

alter publication supabase_realtime add table routes;
alter publication supabase_realtime add table route_likes;
alter publication supabase_realtime add table saved_routes;
alter publication supabase_realtime add table route_comments;

-- previous record untuk UPDATE/DELETE (PostgresAction.oldRecord) —
-- client saat ini tidak memakai oldRecord, tapi FULL bikin event
-- lebih informatif kalau nanti perlu diff granular.
alter table routes         replica identity full;
alter table route_likes    replica identity full;
alter table saved_routes   replica identity full;
alter table route_comments replica identity full;

commit;
