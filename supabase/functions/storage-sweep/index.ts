// Nyasar Edge Function — storage-sweep
//
// Daily quota hygiene for the two GPX buckets. Deletes FILES ONLY, via the
// Storage API (the ONLY surface that removes the physical S3 object — SQL
// deletes on storage.objects leave the file behind, per Supabase docs):
//
//   1. Orphans   — {uid}/{routeId}.gpx.gz with no matching routes row
//                  (deletions from before the 2026-09 in-app cleanup fix).
//   2. Abandoned — every GPX owned by a user whose profiles.last_seen_at is
//                  older than INACTIVITY_DAYS (default 365; the signal is
//                  maintained by the migration 0008 trigger on
//                  auth.refresh_tokens, so it works for ANY app version).
//
// Files only: the account and all its rows stay. A returning user just
// re-publishes; nothing user-generated (routes, likes, comments, backups)
// is destroyed. Conservative by design.
//
// DEPLOY (free tier includes Edge Functions + pg_cron):
//   supabase functions deploy storage-sweep --project-ref <ref>
//   supabase secrets set SWEEP_SHARED_KEY=<random>   -- see verifyRequest
//   (schedule: see supabase/migrations/0008_storage_hygiene.sql notes /
//   README — pg_cron hits this function over HTTPS with the shared key)
//
// AUTH: an hourly public URL would be a deletion free-for-all, so the cron
// call must present the SWEEP_SHARED_KEY header. 401 otherwise.

import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const GPX_BUCKETS = ["route-gpx", "route-gpx-private"];
const INACTIVITY_DAYS = 365;
// Storage API caps per-request object lists; batches keep us under it.
const BATCH_SIZE = 100;

Deno.serve(async (req: Request) => {
  // ── auth: shared key, comparable in constant time-ish (enough here) ──
  const expected = Deno.env.get("SWEEP_SHARED_KEY");
  if (!expected) {
    return json({ error: "SWEEP_SHARED_KEY not configured" }, 500);
  }
  const provided =
    req.headers.get("x-sweep-key") ?? req.headers.get("authorization")?.replace(/^Bearer\s+/i, "") ?? "";
  if (provided !== expected) {
    return json({ error: "unauthorized" }, 401);
  }

  const supabase = createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
    { auth: { persistSession: false } }
  );

  const report = {
    orphansDeleted: 0,
    abandonedUsers: 0,
    abandonedFilesDeleted: 0,
    errors: [] as string[],
  };

  try {
    // ── 1) Orphan sweep ────────────────────────────────────────────────
    const { data: orphans, error: orphanErr } = await supabase.rpc(
      "list_orphan_gpx_objects"
    );
    if (orphanErr) throw new Error(`list_orphan_gpx_objects: ${orphanErr.message}`);
    report.orphansDeleted += await deleteFiles(
      supabase,
      groupByBucket(orphans ?? []),
      report.errors
    );

    // ── 2) Inactivity sweep ────────────────────────────────────────────
    const { data: inactive, error: inactiveErr } = await supabase.rpc(
      "list_inactive_user_ids",
      { days: INACTIVITY_DAYS }
    );
    if (inactiveErr) throw new Error(`list_inactive_user_ids: ${inactiveErr.message}`);
    for (const row of inactive ?? []) {
      report.abandonedUsers++;
      const perBucket: Record<string, string[]> = {};
      for (const bucket of GPX_BUCKETS) perBucket[bucket] = [`${row.id}/`];
      // list() is per-folder: the user's top-level folder IS the path prefix.
      for (const bucket of GPX_BUCKETS) {
        const { data: objs, error: listErr } = await supabase.storage
          .from(bucket)
          .list(row.id, { limit: 1000, search: "" });
        if (listErr) {
          report.errors.push(`list ${bucket}/${row.id}: ${listErr.message}`);
          continue;
        }
        perBucket[bucket] = (objs ?? [])
          .filter((o) => o.name.endsWith(".gpx.gz"))
          .map((o) => `${row.id}/${o.name}`);
      }
      report.abandonedFilesDeleted += await deleteFiles(
        supabase,
        perBucket,
        report.errors
      );
    }
  } catch (e) {
    report.errors.push(`fatal: ${e instanceof Error ? e.message : String(e)}`);
    return json(report, 500);
  }

  return json(report, 200);
});

function groupByBucket(
  rows: { bucket_id: string; name: string }[]
): Record<string, string[]> {
  const out: Record<string, string[]> = {};
  for (const r of rows) (out[r.bucket_id] ??= []).push(r.name);
  return out;
}

async function deleteFiles(
  supabase: ReturnType<typeof createClient>,
  perBucket: Record<string, string[]>,
  errors: string[]
): Promise<number> {
  let deleted = 0;
  for (const [bucket, names] of Object.entries(perBucket)) {
    for (let i = 0; i < names.length; i += BATCH_SIZE) {
      const batch = names.slice(i, i + BATCH_SIZE);
      const { error } = await supabase.storage.from(bucket).remove(batch);
      if (error) {
        errors.push(`remove ${bucket} batch ${i}: ${error.message}`);
        continue;
      }
      deleted += batch.length;
    }
  }
  return deleted;
}

function json(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
}
