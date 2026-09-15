-- ============================================================================
-- Nyasar migration 0002 — self-service account deletion (Phase 1, Hapus Akun)
--
-- WHY THIS EXISTS
--   The app intentionally has NO service-role key (security decision — a
--   client that embeds it would own the whole database). Therefore the app
--   can not call auth.admin.deleteUser(). The standard Supabase pattern for
--   "delete my own account" is a SECURITY DEFINER RPC that the signed-in
--   user calls with their publishable (anon) key; the function deletes ONLY
--   the auth.users row of auth.uid() — the caller's own account.
--
-- WHAT GETS DELETED
--   Only `auth.users`. Every public table that references users
--   (profiles, routes, waypoints, route_likes, saved_routes,
--   route_comments, reports, activity_backups) uses
--   ON DELETE CASCADE from auth.users in the base schema, so they are
--   removed automatically by the cascade. Nothing outside the caller's own
--   rows is touched.
--
-- SAFETY NOTES
--   * auth.uid() inside a SECURITY DEFINER function still evaluates to the
--     CALLING user's id (the JWT is evaluated by the auth extension, not by
--     the function owner), so the "own row only" guarantee does not depend
--     on the invoker.
--   * Not marked `strict`: it takes no arguments.
--   * No error if the row is already gone (a second call after a completed
--     deletion simply deletes 0 rows) — makes retries idempotent.
--
-- HOW TO APPLY (manual, same pattern as the username_is_set migration)
--   Supabase Dashboard -> SQL Editor -> paste this whole file -> Run.
--   No app redeploy is needed for the SQL itself; the Kotlin side already
--   treats an RPC error as a normal failure path.
-- ============================================================================

create or replace function public.delete_own_account()
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
  -- Delete the calling user's own auth.users row. All profile/route/backup
  -- rows die with it through the schema's ON DELETE CASCADE constraints.
  delete from auth.users where id = auth.uid();
end;
$$;

-- Lock the function down: revoking from anon/public and granting to
-- authenticated means ONLY signed-in users can ever execute it, and only
-- against their own account (the WHERE auth.uid() clause above).
revoke all on function public.delete_own_account() from public;
revoke all on function public.delete_own_account() from anon;
grant execute on function public.delete_own_account() to authenticated;
