package com.nyasar.app.backup

import com.nyasar.app.data.db.ActivityPointEntity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Fase 3 (Backup Pribadi) item 1 — binary codec that packs a recorded
 * activity's GPS points into the `activity_backups.track_data` bytea
 * (schema_v1.sql: "selisih antar titik GPS berdekatan itu angka kecil,
 * jauh lebih hemat daripada nyimpen koordinat penuh berulang-ulang").
 *
 * Pipeline: per-field DELTA encoding (varint — small deltas dominate
 * between adjacent 1-second GPS fixes) then GZIP over the whole payload
 * (catches the remaining repetition in elevation/accuracy streams).
 *
 * Wire format (little-endian fixed ints, LEB128-style zigzag varints):
 *
 *   bytes 0-1   magic 'N','Y'
 *   byte  2     format version (currently 1 — decode refuses others)
 *   byte  3     reserved (0)
 *   bytes 4-11  point[0].timestampMs (Long LE) — the absolute time base
 *   bytes 12..  per-8-point groups:
 *                 1 mask byte  — bit(i*2)=elevation null, bit(i*2+1)=speed
 *                                null for the i-th point in the group
 *                 per point:
 *                   varint zigzag(dtMs)        — vs previous point
 *                   varint zigzag(dLat)        — fixed 1e-6 deg vs previous
 *                   varint zigzag(dLon)        — fixed 1e-6 deg vs previous
 *                   varint zigzag(dEleCm)      — cm vs previous NON-null ele
 *                   ushort LE accuracyDm       — 0.1 m units, clamped
 *                   varint zigzag(speedCms)    — raw cm/s (not delta)
 *
 * The first point's own lat/lon are the first deltas (previous = 0), so
 * nothing else is needed to reconstruct absolute values. Point ids are
 * NOT stored — restore re-inserts with autogenerate; `sequence` is the
 * list index. `activityId` is not stored either — the backup row's
 * source_activity_id already carries it.
 *
 * All failures throw [FormatException] with the reason — never a bare
 * IndexOutOfBounds from a truncated buffer.
 */
object DeltaEncoder {

    /** Raised on any malformed input: bad magic/version, truncated buffer,
     *  or a gzip layer failure. Decoders map it to a user-facing error. */
    class FormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private const val MAGIC_0 = 'N'.code.toByte()
    private const val MAGIC_1 = 'Y'.code.toByte()
    private const val VERSION = 1

    private const val HEADER_BYTES = 12

    private const val ELE_SCALE = 100.0        // cm resolution
    private const val COORD_SCALE = 1e6        // 1e-6 deg ≈ 0.11 m
    private const val SPEED_SCALE = 100.0      // cm/s
    private const val ACC_SCALE = 10.0         // 0.1 m resolution
    private const val MAX_ACC_DM = 0xFFFF      // ushort clamp (>6.5 km — junk fix)

    // ── Encode ──

    fun encode(points: List<ActivityPointEntity>): ByteArray {
        val raw = ByteArrayOutputStream(points.size * 10 + HEADER_BYTES)
        raw.write(MAGIC_0.toInt())
        raw.write(MAGIC_1.toInt())
        raw.write(VERSION)
        raw.write(0) // reserved

        val startTime = points.firstOrNull()?.timestampMs ?: 0L
        writeLongLe(raw, startTime)

        // prevTime starts at the HEADER base (not 0): the first point's
        // delta is then ts0 - ts0 = 0, so decode's header+delta telescope
        // to the absolute timestamp exactly once — writing the first delta
        // as the absolute value here would count ts0 twice on decode.
        var prevTime = startTime
        var prevLat = 0.0
        var prevLon = 0.0
        var prevEleCm = 0L // last NON-null elevation, in cm

        var i = 0
        while (i < points.size) {
            // Mask byte for this group of up-to-8 points (null flags first,
            // so the decoder knows what presence to expect before reading).
            var mask = 0
            for (j in 0 until 8) {
                val p = points.getOrNull(i + j) ?: break
                if (p.elevationM == null) mask = mask or (1 shl (j * 2))
                if (p.speedMps == null) mask = mask or (1 shl (j * 2 + 1))
            }
            raw.write(mask)

            for (j in 0 until 8) {
                val p = points.getOrNull(i + j) ?: break
                writeVarint(raw, zigzag(p.timestampMs - prevTime))
                writeVarint(raw, zigzag(Math.round(p.lat * COORD_SCALE) - Math.round(prevLat * COORD_SCALE)))
                writeVarint(raw, zigzag(Math.round(p.lon * COORD_SCALE) - Math.round(prevLon * COORD_SCALE)))
                val ele = p.elevationM
                if (ele != null) {
                    val eleCm = Math.round(ele * ELE_SCALE)
                    writeVarint(raw, zigzag(eleCm - prevEleCm))
                    prevEleCm = eleCm
                }
                writeUShortLe(raw, Math.round((p.accuracyMeters * ACC_SCALE)).toInt().coerceIn(0, MAX_ACC_DM))
                val speed = p.speedMps
                if (speed != null) {
                    writeVarint(raw, zigzag(Math.round(speed * SPEED_SCALE)))
                }
                prevTime = p.timestampMs
                prevLat = p.lat
                prevLon = p.lon
            }
            i += 8
        }

        return gzip(raw.toByteArray())
    }

    // ── Decode ──

    /** Rebuilds the exact point list (ids zeroed — autogenerate reassigns;
     *  sequence = list index). [activityId] is stamped onto every row. */
    fun decode(data: ByteArray, activityId: String): List<ActivityPointEntity> {
        val raw = try {
            gunzip(data)
        } catch (e: FormatException) {
            throw e
        } catch (e: Exception) {
            throw FormatException("gzip layer failed", e)
        }
        val buf = ByteArrayInputStream(raw)
        fun need(n: Int) {
            if (buf.available() < n) throw FormatException("truncated (need $n, have ${buf.available()})")
        }

        need(4)
        if (buf.read() != MAGIC_0.toInt() || buf.read() != MAGIC_1.toInt()) {
            throw FormatException("bad magic — not a Nyasar backup payload")
        }
        val version = buf.read()
        if (version != VERSION) throw FormatException("unsupported version $version")
        buf.read() // reserved

        need(8)
        var prevTime = readLongLe(buf)
        var prevLatCm = 0L
        var prevLonCm = 0L
        var prevEleCm = 0L

        val out = ArrayList<ActivityPointEntity>(64)
        var seq = 0
        while (buf.available() > 0) {
            need(1)
            val mask = buf.read()
            for (j in 0 until 8) {
                if (buf.available() == 0) break
                val eleNull = mask and (1 shl (j * 2)) != 0
                val speedNull = mask and (1 shl (j * 2 + 1)) != 0

                val dt = unzigzag(readVarint(buf, ::need))
                val dLat = unzigzag(readVarint(buf, ::need))
                val dLon = unzigzag(readVarint(buf, ::need))
                val dEle = if (eleNull) 0L else unzigzag(readVarint(buf, ::need))
                need(2)
                val accDm = readUShortLe(buf)
                val speedCms = if (speedNull) 0L else unzigzag(readVarint(buf, ::need))

                prevTime += dt
                val latCm = prevLatCm + dLat
                val lonCm = prevLonCm + dLon
                prevLatCm = latCm
                prevLonCm = lonCm
                if (!eleNull) prevEleCm += dEle

                out.add(
                    ActivityPointEntity(
                        id = 0,
                        activityId = activityId,
                        sequence = seq,
                        lat = latCm / COORD_SCALE,
                        lon = lonCm / COORD_SCALE,
                        elevationM = if (eleNull) null else prevEleCm / ELE_SCALE,
                        speedMps = if (speedNull) null else (speedCms / SPEED_SCALE).toFloat(),
                        accuracyMeters = (accDm / ACC_SCALE).toFloat(),
                        timestampMs = prevTime
                    )
                )
                seq++
            }
        }
        return out
    }

    // ── Varint / zigzag primitives ──

    private fun zigzag(v: Long): Long = (v shl 1) xor (v shr 63)
    private fun unzigzag(v: Long): Long = (v ushr 1) xor -(v and 1)

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        // Unsigned LEB128 — 10 bytes max for a negative-start Long.
        while (true) {
            if (v and 0x7FL.inv() == 0L) {
                out.write(v.toInt())
                return
            }
            out.write(((v and 0x7FL) or 0x80L).toInt())
            v = v ushr 7
        }
    }

    private inline fun readVarint(buf: ByteArrayInputStream, need: (Int) -> Unit): Long {
        var result = 0L
        var shift = 0
        while (true) {
            need(1)
            val b = buf.read()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 63) throw FormatException("varint too long")
        }
    }

    private fun writeLongLe(out: ByteArrayOutputStream, v: Long) {
        repeat(8) { out.write((v ushr (it * 8)).toInt()) }
    }

    private fun readLongLe(buf: ByteArrayInputStream): Long {
        var v = 0L
        repeat(8) { i -> v = v or (buf.read().toLong() and 0xFFL shl (i * 8)) }
        return v
    }

    private fun writeUShortLe(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }

    private fun readUShortLe(buf: ByteArrayInputStream): Int =
        (buf.read() and 0xFF) or ((buf.read() and 0xFF) shl 8)

    // ── Gzip layer ──

    private fun gzip(data: ByteArray): ByteArray = ByteArrayOutputStream(data.size / 4 + 32).let { raw ->
        GZIPOutputStream(raw).use { it.write(data) }
        raw.toByteArray()
    }

    private fun gunzip(data: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(data)).use { stream ->
            val out = ByteArrayOutputStream(data.size * 4)
            val buf = ByteArray(8 * 1024)
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
}
