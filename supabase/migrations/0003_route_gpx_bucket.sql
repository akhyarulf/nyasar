-- ============================================================================
-- Nyasar migration 0003 — Supabase Storage bucket for published GPX files
-- (Fase 2, keputusan "full open": rute publik HARUS bisa didownload jadi
--  GPX asli oleh user lain — lihat PROJECT_CONTEXT.md Keputusan poin 6)
--
-- WHAT
--   Public bucket `route-gpx`. Files are uploaded by the app at
--     {auth.uid()}/{routeId}.gpx.gz
--   (gzip'ed GPX 1.1, ~10x compression — see the size table in
--   PROJECT_CONTEXT.md). The public URL is stored in routes.gpx_file_url.
--
-- WHY PUBLIC
--   The bucket must be public so anyone (even without the app) can download
--   the original GPX from routes.gpx_file_url — that is the explicit product
--   decision. WRITE access is NOT public: uploads are authenticated and
--   locked to the user's own first-level folder via the policy below.
--
-- HOW TO APPLY (manual, same as 0002)
--   Supabase Dashboard -> SQL Editor -> paste this whole file -> Run.
-- ============================================================================

insert into storage.buckets (id, name, public)
values ('route-gpx', 'route-gpx', true)
on conflict (id) do nothing;

-- Upload/delete only inside the caller's own top-level folder
-- (first path segment must equal the uploader's user id).
create policy "Users upload own gpx files"
on storage.objects for insert to authenticated
with check (bucket_id = 'route-gpx' and (storage.foldername(name))[1] = auth.uid()::text);

create policy "Users manage own gpx files"
on storage.objects for update to authenticated
using (bucket_id = 'route-gpx' and (storage.foldername(name))[1] = auth.uid()::text);

create policy "Users delete own gpx files"
on storage.objects for delete to authenticated
using (bucket_id = 'route-gpx' and (storage.foldername(name))[1] = auth.uid()::text);

-- No SELECT policy needed: public buckets are world-readable by design.
