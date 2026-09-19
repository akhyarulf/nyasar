-- ============================================================================
-- Nyasar migration 0006 — Private bucket for PRIVATE routes' GPX files
-- (Visibility/Privacy slice — Wikiloc 2-level Everyone/Only you)
--
-- PROBLEM (audit note in PROJECT_CONTEXT.md, now closed):
--   `route-gpx` is a PUBLIC bucket (migration 0003). A private route's GPX
--   stored there would still leak via its URL even though the routes row is
--   hidden by RLS — the file is the full track with timestamps; the polyline
--   row is only a summary. Visibility must gate BOTH.
--
-- WHAT
--   Private bucket `route-gpx-private` (public = false). Same object layout
--   as the public bucket: {auth.uid()}/{routeId}.gpx.gz. Owner-only read —
--   there is deliberately NO public SELECT policy: a private bucket is
--   unreadable without an authorizing policy, and the only policy below
--   requires the caller's uid to match the first path segment.
--
--   Download path for legitimate viewers (the OWNER, from Route Detail or
--   their Library flows): supabase-js/kt `downloadAuthenticated` / signed
--   URL — implemented in BrowseRepository.downloadGpx fallback.
--
--   Public routes KEEP uploading to the public `route-gpx` bucket exactly as
--   before ("full open" Keputusan poin 6 unchanged for public routes).
--
-- HOW TO APPLY (manual, same as 0002-0005)
--   Supabase Dashboard -> SQL Editor -> paste this whole file -> Run.
-- ============================================================================

insert into storage.buckets (id, name, public)
values ('route-gpx-private', 'route-gpx-private', false)
on conflict (id) do nothing;

-- Owner-only access, same folder rule as the public bucket's write policies:
-- first path segment must equal the caller's user id. This single policy
-- covers download (SELECT) for the owner; anonymous/public requests have no
-- policy matching them, so private files are simply unreachable.
create policy "Users download own private gpx files"
on storage.objects for select to authenticated
using (bucket_id = 'route-gpx-private' and (storage.foldername(name))[1] = auth.uid()::text);

create policy "Users upload own private gpx files"
on storage.objects for insert to authenticated
with check (bucket_id = 'route-gpx-private' and (storage.foldername(name))[1] = auth.uid()::text);

create policy "Users manage own private gpx files"
on storage.objects for update to authenticated
using (bucket_id = 'route-gpx-private' and (storage.foldername(name))[1] = auth.uid()::text);

create policy "Users delete own private gpx files"
on storage.objects for delete to authenticated
using (bucket_id = 'route-gpx-private' and (storage.foldername(name))[1] = auth.uid()::text);
