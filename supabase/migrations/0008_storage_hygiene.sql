-- ============================================================================
-- Nyasar migration 0008 — storage hygiene signals (inactivity + orphans)
--
-- WHY
--   Free-tier Storage counts FILES, and two leak classes were never cleaned
--   server-side:
--     1. Orphans — {uid}/{routeId}.gpx.gz files whose routes row is gone
--        (deleted via SQL-era tooling, or before the in-app GpxStorageCleanup
--        fix of 2026-09).
--     2. Abandoned accounts — the owner simply STOPPED opening the app
--        without deleting anything. The user (solo dev) wants these purged
--        after 1 year of inactivity.
--
--   Both are swept by the storage-sweep Edge Function (supabase/functions/
--   storage-sweep) on a daily pg_cron schedule. This migration provides the
--   SIGNAL the sweep reads: profiles.last_seen_at.
--
-- INACTIVITY SIGNAL — deliberately app-version-proof
--   The user's worry: "what about users on old app versions?" So the signal
--   is NOT a heartbeat the app must send. auth.refresh_tokens.updated_at is
--   touched by Supabase Auth itself whenever a device refreshes its session
--   token — every signed-in install does this within hours, regardless of
--   app version, OS version, or app age. The trigger below maintains
--   profiles.last_seen_at from it. A year-silent account (no login, no
--   session refresh, nothing) is genuinely abandoned.
--
--   NOTE: refresh_tokens rows are DELETED on sign-out (per-device rows), so
--   the trigger fires on INSERT/UPDATE events; accounts with zero devices
--   simply stop updating — exactly the inactivity we want to detect.
--
-- SAFETY
--   * RLS note: profiles SELECT is public ("viewable by everyone" in
--     schema_v1.sql) — last_seen_at exposes a coarse activity timestamp.
--     Acceptable for this app (no email/phone in this table), and the sweep
--     runs as service role anyway.
--   * The sweep DELETES FILES ONLY. The account, its rows, and its backups
--     remain; a returning user just re-publishes. Deleting DB rows would
--     cascade user content (likes/comments on others' routes) — file-only
--     purge is the reversible, conservative choice.
-- ============================================================================

-- 1) The signal -----------------------------------------------------------
alter table profiles add column if not exists last_seen_at timestamptz default now();

-- Backfill: anyone with a live device session right now counts as seen today.
update profiles p
set last_seen_at = coalesce(
  (select max(t.updated_at) from auth.refresh_tokens t where t.user_id = p.id),
  now()
);

-- 2) The maintainer -------------------------------------------------------
create or replace function public.touch_profile_last_seen()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
  update profiles set last_seen_at = now() where id = new.user_id;
  return null;
end;
$$;

drop trigger if exists on_refresh_token_change on auth.refresh_tokens;
create trigger on_refresh_token_change
after insert or update on auth.refresh_tokens
for each row execute function public.touch_profile_last_seen();

-- 3) Sweep support: which files may be deleted ----------------------------
-- (The Edge Function uses service-role SQL over these helpers via RPC, so
-- the deletion logic itself lives in ONE place and is auditable.)

-- Orphan GPX files: present in a bucket but with no matching routes row.
create or replace function public.list_orphan_gpx_objects()
returns table (bucket_id text, name text)
language sql
security definer
set search_path = public
as $$
  select o.bucket_id, o.name
  from storage.objects o
  where o.bucket_id in ('route-gpx', 'route-gpx-private')
    and (storage.foldername(o.name))[1] ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    and not exists (
      select 1 from routes r
      where r.user_id::text = (storage.foldername(o.name))[1]
        and r.id::text = regexp_replace((storage.foldername(o.name))[2], '\.gpx\.gz$', '')
    );
$$;

-- Users inactive for at least the given days (default 365).
create or replace function public.list_inactive_user_ids(days int default 365)
returns table (id uuid)
language sql
security definer
set search_path = public
as $$
  select p.id from profiles p
  where p.last_seen_at < now() - make_interval(days => greatest(days, 30));
$$;
