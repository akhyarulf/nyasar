-- ============================================================================
-- Nyasar migration 0004 — drop routes.mountain_name and routes.region
--
-- DECISION (PROJECT_CONTEXT.md, "Keputusan baru — hapus mountain_name DAN
-- region dari tabel routes"): both columns are REMOVED — drop, not rename.
-- The activity/GPX name the user already typed carries the place identity,
-- so the publish form's separate "mountain name" question felt forced
-- (and wrong for non-mountain sports); region was judged unnecessary.
--
-- WHAT GETS LOST
--   Data already filled in these two columns (including the tested publish
--   rows created before this decision) is PERMANENTLY deleted by the drop.
--   Activity/GPX rows themselves are NOT touched — only these two columns.
--
-- ORDER OF EXECUTION (mandatory, see PROJECT_CONTEXT.md)
--   KODE DULU, SQL BELAKANGAN. The app no longer reads or writes these
--   columns (PublishRepository, PublishRouteSheet, BrowseRepository all
--   cleaned BEFORE this migration). Running this SQL on an OLD app build
--   would make publish fail (insert into dropped columns) — so update the
--   app first, then run this.
--
-- HOW TO APPLY (manual, same as 0002/0003)
--   Supabase Dashboard -> SQL Editor -> paste this whole file -> Run.
--
-- VERIFICATION
--   select column_name from information_schema.columns
--    where table_name = 'routes' and column_name in ('mountain_name','region');
--   must return 0 rows.
-- ============================================================================

-- Indexes must go first: they depend on the columns.
drop index if exists idx_routes_mountain;
drop index if exists idx_routes_region;

alter table routes drop column if exists mountain_name;
alter table routes drop column if exists region;
