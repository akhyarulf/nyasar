-- ============================================================================
-- Nyasar — Supabase FULL CLEANUP (nuke semua)
--
-- WHAT THIS DOES (IRREVERSIBLE — read before running)
--   Wipes EVERYTHING in the cloud project:
--     1. All GPX files in both Storage buckets (route-gpx, route-gpx-private)
--     2. All app data: routes (cascades likes, comments, saved_routes,
--        reports, linked waypoints), waypoints, activity_backups
--     3. ALL ACCOUNTS (auth.users) — everyone must register again
--
--   Data on users' PHONES is NOT touched (local-first architecture: Room DB
--   lives on-device). activity_backups from a still-signed-in device will
--   quietly re-upload on the next network-trigger flush — that is by design
--   (backup is idempotent). To keep the cloud truly empty, users must also
--   sign out / clear app data.
--
-- HOW TO RUN
--   Supabase Dashboard -> SQL Editor -> paste this whole file -> Run.
--   This cannot be undone. There is no auth.users backup unless you made one.
--
-- PRE-FLIGHT (optional sanity check — run these first, note the numbers):
--   select count(*) from auth.users;
--   select count(*) from profiles;
--   select count(*) from routes;
--   select count(*) from waypoints;
--   select count(*) from activity_backups;
--   select bucket_id, count(*) from storage.objects
--     where bucket_id in ('route-gpx','route-gpx-private') group by bucket_id;
--
-- POST-FLIGHT VERIFY (all should return 0 / empty):
--   select count(*) from auth.users;
--   select count(*) from profiles;
--   select count(*) from routes;
--   select bucket_id, count(*) from storage.objects
--     where bucket_id in ('route-gpx','route-gpx-private') group by bucket_id;
--
-- WHY THIS ORDER
--   Storage objects are deleted FIRST, explicitly, because no FK/trigger
--   connects storage.objects to auth.users or routes — deleting rows alone
--   would orphan every .gpx.gz file and keep eating free-tier quota. Then
--   auth.users: every public table cascades from it (schema_v1.sql +
--   migration 0002), so one delete clears profiles, routes, waypoints,
--   route_likes, route_comments, saved_routes, reports, activity_backups.
-- ============================================================================

begin;

-- 1) Storage: GPX files (public + private buckets) — cascade does NOT reach these
delete from storage.objects
  where bucket_id in ('route-gpx', 'route-gpx-private');

-- 2) Accounts: cascades to profiles, routes, waypoints, route_likes,
--    route_comments, saved_routes, reports, activity_backups
delete from auth.users;

commit;

-- App-visible side effects after this script:
--   * Nobody can sign in anymore (accounts gone).
--   * app.nyasarnyaman.my.id/browse shows the empty state immediately.
--   * The in-app "Hapus akun" RPC (delete_own_account) still works for any
--     account created afterwards — nothing in this script touches it.
