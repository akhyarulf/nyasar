package com.nyasar.app.update

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * In-app update check for the sideload distribution model (konsep "rilis
 * tanpa Play Store"): GitHub Releases is the update channel, so the app
 * asks `releases/latest` and compares the tag against its own versionName.
 *
 * Intentionally BORING by design — every failure mode resolves to "no
 * update": network off, rate-limited (403), no release published yet (404,
 * the steady state before the first v* tag), malformed payload, or a
 * downgrade guard (server tag older than the installed one, e.g. a build
 * from a newer branch). The caller just shows a banner when [check]
 * returns non-null; nothing here can crash, block, or nag.
 *
 * Rate limiting note: api.github.com allows 60 req/h per IP unauthenticated.
 * One call per cold process start is well inside that, and the result is
 * not retried within the same process (check is invoked once from
 * MainActivity.onCreate).
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val RELEASES_LATEST = "https://api.github.com/repos/akhyarulf/nyasar/releases/latest"

    /** Everything the update dialog needs. */
    data class UpdateInfo(
        val latestVersion: String,   // tag without the leading "v", e.g. "1.0.1"
        val downloadUrl: String,     // permanent asset link, Nyasar.apk
        val currentVersion: String   // BuildConfig.VERSION_NAME of the installed build
    )

    /**
     * Queries the latest GitHub release. Returns null when there is nothing
     * to offer (any error, no release yet, or not newer than this build).
     */
    suspend fun check(currentVersionName: String): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder()
                .url(RELEASES_LATEST)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Nyasar/$currentVersionName (update-check)")
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    // 404 = no release yet (normal pre-first-tag), 403 = rate limit.
                    Log.i(TAG, "no update info: HTTP ${resp.code}")
                    return@withContext null
                }
                val body = resp.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                // draft:true or asset-less releases must never be offered —
                // the permanent Nyasar.apk link would 404.
                if (json.optBoolean("draft", false) || json.optBoolean("prerelease", false)) {
                    return@withContext null
                }
                val tag = json.optString("tag_name", "")
                if (!tag.startsWith("v")) return@withContext null
                val latest = tag.removePrefix("v")
                if (!isNewer(latest, currentVersionName)) return@withContext null
                val apk = json.optJSONArray("assets") ?: return@withContext null
                var url: String? = null
                for (i in 0 until apk.length()) {
                    val a = apk.optJSONObject(i) ?: continue
                    if (a.optString("name") == "Nyasar.apk") { url = a.optString("browser_download_url"); break }
                }
                url ?: return@withContext null
                UpdateInfo(latestVersion = latest, downloadUrl = url, currentVersion = currentVersionName)
            }
        } catch (e: Exception) {
            Log.i(TAG, "update check skipped: ${e.message}")
            null
        }
    }

    /**
     * Semantic-ish comparison: "1.2.10" > "1.2.9", "0.9.0-beta" < "0.9.0",
     * prerelease suffix (anything after "-") loses against the same numeric
     * version, matching the tag convention of release-apk.yaml. Unparseable
     * versions never compare newer (fails safe: no dialog).
     */
    fun isNewer(candidate: String, current: String): Boolean {
        val c = parts(candidate) ?: return false
        val cur = parts(current) ?: return true // installed build unparseable → any real tag wins
        for (i in 0 until maxOf(c.size, cur.size)) {
            val a = c.getOrElse(i) { 0 }
            val b = cur.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        // Numerically equal: a prerelease suffix (e.g. -beta) is older than none.
        val cPre = candidate.substringAfter('-', "").isNotEmpty()
        val curPre = current.substringAfter('-', "").isNotEmpty()
        return !cPre && curPre
    }

    /** "v1.2.3-beta" → [1, 2, 3]; null when there is no numeric core. */
    private fun parts(v: String): List<Int>? {
        val core = v.substringBefore('-').removePrefix("v")
        if (core.isEmpty()) return null
        val nums = core.split('.').map { it.toIntOrNull() ?: return null }
        return if (nums.isEmpty()) null else nums
    }
}
