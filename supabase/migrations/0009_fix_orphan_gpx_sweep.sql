-- ============================================================================
-- Nyasar migration 0009 — FIX: the orphan sweep deleted EVERY GPX file
--
-- THE BUG (0008)
--   list_orphan_gpx_objects() derived the route id from
--       (storage.foldername(name))[2]
--   but storage.foldername() returns ONLY the folder segments — it drops the
--   last path element (the file name). For the app's object name
--       {userId}/{routeId}.gpx.gz
--   foldername() is {userId}: element [2] is NULL, so
--       regexp_replace(NULL, '\.gpx\.gz$', '')  ->  NULL
--       r.id::text = NULL                       ->  NULL (never true)
--   and `not exists (...)` therefore returned TRUE for EVERY object. The
--   daily storage-sweep then deleted every file in route-gpx and
--   route-gpx-private — including files whose routes row is alive. Result:
--   published routes kept their row (map, stats, likes) but lost the GPX, so
--   the elevation profile and "Export GPX" reported "the GPX file could not
--   be read". Verified semantics against the Supabase docs:
--   https://supabase.com/docs/guides/storage/schema/helper-functions
--
-- THE FIX
--   Parse owner/route ids from the OBJECT NAME itself and only accept the
--   exact pattern the upload writes ({uuid}/{uuid}.gpx.gz). Anything else is
--   never treated as an orphan (an unknown shape must not be deleted by a
--   quota sweep).
--
-- AFTER RUNNING
--   * The sweep stops deleting live files.
--   * Files already deleted are GONE from Storage; they are not restorable
--     from the row (track_polyline has no elevation). The app self-heals
--     them: Route Detail re-exports the GPX from the owner's local data
--     (library file or recorded activity) and re-uploads it on the next
--     open, so the elevation chart and Export start working again.
--
-- HOW TO APPLY (manual, same as 0002-0008)
--   Supabase Dashboard -> SQL Editor -> paste this whole file -> Run.
-- ============================================================================

create or replace function public.list_orphan_gpx_objects()
returns table (bucket_id text, name text)
language sql
security definer
set search_path = public
as $$
  with objects as (
    select
      o.bucket_id,
      o.name,
      split_part(o.name, '/', 1) as owner_id,
      split_part(o.name, '/', 2) as file_name
    from storage.objects o
    where o.bucket_id in ('route-gpx', 'route-gpx-private')
      -- Exactly the shape the upload writes: {uuid}/{uuid}.gpx.gz.
      -- Guard first, so a malformed name can never be classified as orphan.
      and o.name ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.gpx\.gz$'
  )
  select obj.bucket_id, obj.name
  from objects obj
  where not exists (
    select 1
    from routes r
    where r.user_id::text = obj.owner_id
      and r.id::text = regexp_replace(obj.file_name, '\.gpx\.gz$', '')
  );
$$;
