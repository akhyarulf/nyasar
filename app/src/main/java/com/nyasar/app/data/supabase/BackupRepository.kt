package com.nyasar.app.data.supabase

import android.util.Log
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.postgrest
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.IOException

/**
 * Read/write side of Fase 3 (Backup Pribadi) — the `activity_backups` table
 * (schema_v1.sql): every activity/route of THIS user, PRIVAT (RLS: only the
 * owner reads it), for restoring on a new phone. NOT a publish path — rows
 * never appear in browse and other accounts cannot see them.
 *
 * `track_data` is the schema's bytea: this layer transparently converts the
 * [com.nyasar.app.backup.DeltaEncoder] payload to PostgreSQL's HEX wire
 * format (`\x6e79…`) — VERIFIED LIVE against this project's PostgREST:
 * `\xZZ` fails with 400 "invalid hexadecimal digit" BEFORE RLS is even
 * evaluated, while a bare base64 string parses WITHOUT error as
 * escape-format bytes (ASCII of the base64 text) — i.e. silent corruption.
 * SELECTs return the same `\x…` hex string (bytea_output=hex default), so
 * the decode side mirrors it. `waypoints_json` is a jsonb ARRAY — it is
 * kept as a plain list here so it round-trips without a second query.
 *
 * Upsert strategy: the schema's unique indexes are PARTIAL
 * (`where source_activity_id is not null`), which PostgREST's `on_conflict`
 * cannot infer — so callers resolve the row id themselves (fetch existing id
 * for the same source key, or mint a fresh UUID) and this repository upserts
 * with `on_conflict=id`, which always matches the PK. Verified approach
 * against supabase-kt 2.2.2's upsert signature (source-read, not assumed).
 *
 * Pattern mirrors [AuthRepository]/[PublishRepository]/[BrowseRepository]:
 * sealed Outcomes, zero SDK exceptions to callers, every failure Log.e'd and
 * classified.
 */
class BackupRepository {

    sealed class Outcome {
        data object Success : Outcome()
        data class Failure(val error: BackupError) : Outcome()
    }

    sealed class FetchOutcome {
        data class Success(val rows: List<BackupRow>) : FetchOutcome()
        data class Failure(val error: BackupError) : FetchOutcome()
    }

    enum class BackupError {
        /** No Supabase config in this build (graceful degrade). */
        NOT_CONFIGURED,

        /** No active session — auto-backup paths skip silently on this. */
        NOT_SIGNED_IN,
        NETWORK,
        UNKNOWN
    }

    /** One `activity_backups` row. Field-for-field the schema's columns;
     *  nullable = exactly what the schema allows null. `id` is REQUIRED by
     *  design (see class doc): callers fetch-or-mint it so the upsert never
     *  depends on serializer default-omission behavior. */
    @Serializable
    data class BackupRow(
        val id: String,
        @SerialName("user_id") val userId: String,
        @SerialName("source_activity_id") val sourceActivityId: String? = null,
        @SerialName("source_route_id") val sourceRouteId: String? = null,
        val name: String,
        @SerialName("started_at_epoch_ms") val startedAtEpochMs: Long? = null,
        @SerialName("ended_at_epoch_ms") val endedAtEpochMs: Long? = null,
        val status: String? = null,
        @SerialName("distance_meters") val distanceMeters: Double? = null,
        @SerialName("elevation_gain_m") val elevationGainM: Double? = null,
        @SerialName("elevation_loss_m") val elevationLossM: Double? = null,
        @SerialName("moving_time_ms") val movingTimeMs: Long? = null,
        @SerialName("elapsed_time_ms") val elapsedTimeMs: Long? = null,
        @SerialName("avg_speed_kmh") val avgSpeedKmh: Double? = null,
        @SerialName("max_speed_kmh") val maxSpeedKmh: Double? = null,
        @SerialName("sport_type") val sportType: String? = null,
        @SerialName("local_route_id") val localRouteId: String? = null,
        /** PostgreSQL hex-format bytea (`\x…`) of the DeltaEncoder payload —
         *  the ONLY correct JSON wire form (see class doc, verified live). */
        @SerialName("track_data") val trackData: String,
        @SerialName("waypoints_json") val waypointsJson: List<WaypointJson>? = null
    )

    /** Element of the `waypoints_json` jsonb array — WaypointEntity minus
     *  the link columns (the backup row itself IS the link). */
    @Serializable
    data class WaypointJson(
        val id: String,
        val name: String,
        val category: String,
        val lat: Double,
        val lon: Double,
        @SerialName("elevation_m") val elevationM: Double? = null,
        val note: String? = null,
        @SerialName("created_at_epoch_ms") val createdAtEpochMs: Long,
        val source: String
    )

    /** Id + source key of an existing backup row — the merge-skip probe. */
    @Serializable
    data class ExistingBackup(
        val id: String,
        @SerialName("source_activity_id") val sourceActivityId: String? = null,
        @SerialName("source_route_id") val sourceRouteId: String? = null
    )

    /** Upload/refresh one backup row (upsert on the PK). */
    suspend fun upsertBackup(client: SupabaseClient, row: BackupRow): Outcome {
        if (!SupabaseClientProvider.isConfigured) return Outcome.Failure(BackupError.NOT_CONFIGURED)
        return try {
            client.postgrest["activity_backups"]
                .upsert(row, onConflict = "id")
            Outcome.Success
        } catch (e: RestException) {
            Log.e(TAG, "upsertBackup failed: ${e.message}", e)
            Outcome.Failure(BackupError.UNKNOWN)
        } catch (e: HttpRequestTimeoutException) {
            Log.e(TAG, "upsertBackup timeout: ${e.message}")
            Outcome.Failure(BackupError.NETWORK)
        } catch (e: IOException) {
            Log.e(TAG, "upsertBackup network error: ${e.message}")
            Outcome.Failure(BackupError.NETWORK)
        } catch (e: Exception) {
            Log.e(TAG, "upsertBackup unexpected: ${e.message}", e)
            Outcome.Failure(BackupError.UNKNOWN)
        }
    }

    /** All backup rows of the signed-in user (RLS filters server-side),
     *  oldest first so restore recreates history in a stable order. */
    suspend fun fetchAllBackups(client: SupabaseClient): FetchOutcome {
        if (!SupabaseClientProvider.isConfigured) return FetchOutcome.Failure(BackupError.NOT_CONFIGURED)
        return try {
            val rows = client.postgrest["activity_backups"]
                .select {
                    order("created_at", io.github.jan.supabase.postgrest.query.Order.ASCENDING)
                }
                .decodeList<BackupRow>()
            FetchOutcome.Success(rows)
        } catch (e: RestException) {
            Log.e(TAG, "fetchAllBackups failed: ${e.message}", e)
            FetchOutcome.Failure(BackupError.UNKNOWN)
        } catch (e: HttpRequestTimeoutException) {
            Log.e(TAG, "fetchAllBackups timeout: ${e.message}")
            FetchOutcome.Failure(BackupError.NETWORK)
        } catch (e: IOException) {
            Log.e(TAG, "fetchAllBackups network error: ${e.message}")
            FetchOutcome.Failure(BackupError.NETWORK)
        } catch (e: Exception) {
            Log.e(TAG, "fetchAllBackups unexpected: ${e.message}", e)
            FetchOutcome.Failure(BackupError.UNKNOWN)
        }
    }

    sealed class ExistingOutcome {
        data class Success(val rows: List<ExistingBackup>) : ExistingOutcome()
        data class Failure(val error: BackupError) : ExistingOutcome()
    }

    /** Source keys of every backup row the user already has — the cheap
     *  probe backup-all uses to mint STABLE row ids (re-uploads upsert the
     *  same row instead of duplicating). Absence of a key = this source was
     *  never backed up. Ids-only select keeps this probe light even when
     *  the user has thousands of backed-up activities. */
    suspend fun fetchExistingIds(client: SupabaseClient): ExistingOutcome {
        if (!SupabaseClientProvider.isConfigured) return ExistingOutcome.Failure(BackupError.NOT_CONFIGURED)
        return try {
            val rows = client.postgrest["activity_backups"]
                .select(columns = io.github.jan.supabase.postgrest.query.Columns.list(
                    "id", "source_activity_id", "source_route_id"
                ))
                .decodeList<ExistingBackup>()
            ExistingOutcome.Success(rows)
        } catch (e: RestException) {
            Log.e(TAG, "fetchExistingIds failed: ${e.message}", e)
            ExistingOutcome.Failure(BackupError.UNKNOWN)
        } catch (e: HttpRequestTimeoutException) {
            Log.e(TAG, "fetchExistingIds timeout: ${e.message}")
            ExistingOutcome.Failure(BackupError.NETWORK)
        } catch (e: IOException) {
            Log.e(TAG, "fetchExistingIds network error: ${e.message}")
            ExistingOutcome.Failure(BackupError.NETWORK)
        } catch (e: Exception) {
            Log.e(TAG, "fetchExistingIds unexpected: ${e.message}", e)
            ExistingOutcome.Failure(BackupError.UNKNOWN)
        }
    }

    /** Targeted probe: the existing row id for ONE source key (used by
     *  single-activity/route backup so re-backups upsert the SAME row
     *  instead of duplicating — the partial unique indexes can't be used
     *  by on_conflict, see class doc). Null rows list = no existing row. */
    suspend fun fetchRowIdFor(
        client: SupabaseClient,
        sourceActivityId: String? = null,
        sourceRouteId: String? = null
    ): ExistingOutcome {
        if (!SupabaseClientProvider.isConfigured) return ExistingOutcome.Failure(BackupError.NOT_CONFIGURED)
        return try {
            val query = client.postgrest["activity_backups"]
                .select(columns = io.github.jan.supabase.postgrest.query.Columns.list(
                    "id", "source_activity_id", "source_route_id"
                )) {
                    filter {
                        // Exactly one of the two keys is non-null per backup
                        // row (schema XOR constraint) — probe with whichever
                        // the caller passed.
                        if (sourceActivityId != null) eq("source_activity_id", sourceActivityId)
                        if (sourceRouteId != null) eq("source_route_id", sourceRouteId)
                    }
                    limit(1)
                }
            ExistingOutcome.Success(query.decodeList<ExistingBackup>())
        } catch (e: RestException) {
            Log.e(TAG, "fetchRowIdFor failed: ${e.message}", e)
            ExistingOutcome.Failure(BackupError.UNKNOWN)
        } catch (e: HttpRequestTimeoutException) {
            Log.e(TAG, "fetchRowIdFor timeout: ${e.message}")
            ExistingOutcome.Failure(BackupError.NETWORK)
        } catch (e: IOException) {
            Log.e(TAG, "fetchRowIdFor network error: ${e.message}")
            ExistingOutcome.Failure(BackupError.NETWORK)
        } catch (e: Exception) {
            Log.e(TAG, "fetchRowIdFor unexpected: ${e.message}", e)
            ExistingOutcome.Failure(BackupError.UNKNOWN)
        }
    }

    companion object {
        private const val TAG = "BackupRepository"

        private val HEX = "0123456789abcdef".toCharArray()

        /** bytes → `\x…` hex string (PostgreSQL bytea hex wire format). */
        fun byteaHexEncode(bytes: ByteArray): String {
            val sb = StringBuilder(2 + bytes.size * 2)
            sb.append("\\x")
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
            }
            return sb.toString()
        }

        /** `\x…` hex string → bytes (SELECT returns bytea_output=hex).
         *  Tolerates the prefix being absent (defensive) but never accepts
         *  base64 — silently storing ASCII would corrupt the payload. */
        fun byteaHexDecode(value: String): ByteArray {
            val hex = if (value.length >= 2 && value[0] == '\\' && value[1] == 'x') value.substring(2) else value
            if (hex.length % 2 != 0) throw IllegalArgumentException("odd-length bytea hex")
            val out = ByteArray(hex.length / 2)
            for (i in out.indices) {
                out[i] = ((Character.digit(hex[i * 2], 16) shl 4)
                    or Character.digit(hex[i * 2 + 1], 16)).toByte()
            }
            return out
        }

        /** Current user id or null (no session) — the NOT_SIGNED_IN probe
         *  every auto-backup caller consults BEFORE doing any work. */
        fun currentUserIdOrNull(): String? = try {
            SupabaseClientProvider.client.auth.currentUserOrNull()?.id
        } catch (_: Exception) {
            null
        }
    }
}
