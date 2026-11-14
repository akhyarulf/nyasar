-- ============================================================================
-- Nyasar migration 0010 — index untuk "Dekat saya" / area peta di Jelajah
--
-- MASALAH
--   Jelajah memfilter rute berdasarkan kotak geografis (bbox) di sekitar
--   posisi pengguna, memakai kolom yang SUDAH ADA: routes.start_lat dan
--   routes.start_lng (diisi PublishRepository saat publish). Tanpa index,
--   setiap query "dekat saya" menjadi sequential scan atas seluruh tabel
--   routes — makin banyak rute, makin lambat, dan makin mahal di free tier.
--
-- INDEX
--   Partial index: hanya baris yang memang terlihat di Jelajah (publik dan
--   bukan draft). Baris draft/privat tidak pernah muncul di query, jadi tidak
--   perlu ikut di index.
--
-- CARA APPLY (manual, sama seperti 0002-0009)
--   Supabase Dashboard -> SQL Editor -> paste seluruh file -> Run.
--
-- VERIFIKASI SETELAH APPLY
--   1) Index terpakai:
--        explain analyze
--        select id from routes
--        where is_public and not is_draft
--          and start_lat between -7.10 and -6.90
--          and start_lng between 107.50 and 107.70
--        limit 50;
--      -> harus muncul "Index Scan using idx_routes_geo_start".
--   2) Rute tanpa titik awal (tidak akan pernah muncul di "Dekat saya"):
--        select count(*) from routes
--        where start_lat is null or start_lng is null;
--      Harapan: 0. Kedua jalur publish menulis start_lat/lng, jadi kalau
--      hasilnya > 0, baris itu berasal dari publish lama; isi lewat backfill
--      dari titik pertama track_polyline (decode di klien/script, bukan SQL —
--      polyline ter-encode sehingga tidak bisa dibaca Postgres langsung).
-- ============================================================================

create index if not exists idx_routes_geo_start
  on routes (start_lat, start_lng)
  where is_public and not is_draft;
