package com.nyasar.app.navigation

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Sunrise and sunset for one calendar day at one place, in epoch millis.
 * Either side is null when the sun does not cross the horizon that day
 * (polar day/night) — never a made-up time.
 */
data class SunTimes(
    val sunriseEpochMs: Long?,
    val sunsetEpochMs: Long?
)

/**
 * Sunrise/sunset without network, without Play Services, and without a
 * dependency: the standard "sunrise equation" (mean solar noon + solar mean
 * anomaly + equation of the centre + declination + hour angle), good to
 * roughly a minute at Indonesian latitudes — far below the precision that
 * matters when the question is "will I still be on the trail after dark?".
 *
 * Why this and not a library: the app is offline-first and greedy about size
 * and dependencies (see PROJECT_CONTEXT.md, modal nol), and this is ~40 lines
 * of pure math. The only thing it needs from the platform is the time zone,
 * because "today" is a local-calendar question.
 */
object SunTimesCalc {

    private const val JULIAN_DAY_UNIX_EPOCH = 2440587.5
    private const val JULIAN_DAY_J2000 = 2451545.0
    private const val MS_PER_DAY = 86_400_000.0
    private const val DEG = PI / 180.0
    /** Earth's axial tilt at J2000 — the equation's own constant, not a
     *  per-date nutation term (well under a minute of error). */
    private const val OBLIQUITY = 23.4397 * DEG
    /** Standard refraction-corrected zenith for sunrise/sunset: the sun's
     *  upper limb touching the horizon (−0.833°). */
    private const val SUNRISE_ZENITH = -0.833 * DEG

    /**
     * Sun times for the local calendar day containing [nowEpochMs] at
     * (lat, lon), with longitude positive east — the same convention as
     * every other coordinate in this app.
     */
    fun forLocalDay(
        nowEpochMs: Long,
        lat: Double,
        lon: Double,
        timeZone: TimeZone = TimeZone.getDefault()
    ): SunTimes {
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return SunTimes(null, null)

        // Julian date of local noon on that day: anchoring on local noon
        // rather than on "now" keeps the answer stable all day (a fix at
        // 06:00 and one at 18:00 must not produce two different sunsets).
        val julianDay = julianDayOfLocalNoon(nowEpochMs, timeZone)
        val daysSinceJ2000 = ceil(julianDay - JULIAN_DAY_J2000 + 0.0008)

        // Mean solar noon, then the sun's ecliptic position for that day.
        val solarNoon = daysSinceJ2000 - lon / 360.0
        val meanAnomaly = (357.5291 + 0.98560028 * solarNoon).mod(360.0)
        val anomalyRad = meanAnomaly * DEG
        val equationOfCentre =
            1.9148 * sin(anomalyRad) + 0.02 * sin(2 * anomalyRad) + 0.0003 * sin(3 * anomalyRad)
        val eclipticLongitude = (meanAnomaly + equationOfCentre + 180.0 + 102.9372).mod(360.0)
        val eclipticRad = eclipticLongitude * DEG

        // Declination of the sun, then the hour angle at which it rises.
        val declination = asin(sin(eclipticRad) * sin(OBLIQUITY))
        val latRad = lat * DEG
        val cosHourAngle =
            (sin(SUNRISE_ZENITH) - sin(latRad) * sin(declination)) / (cos(latRad) * cos(declination))
        // |cos| > 1: the sun never reaches the horizon that day (polar
        // summer/winter). Nothing honest to show.
        if (cosHourAngle < -1.0 || cosHourAngle > 1.0) return SunTimes(null, null)
        val hourAngleDeg = acos(cosHourAngle) / DEG

        // Solar transit (local apparent noon) in Julian days, then the two
        // crossings around it.
        val transit = JULIAN_DAY_J2000 + solarNoon +
            0.0053 * sin(anomalyRad) - 0.0069 * sin(2 * eclipticRad)
        return SunTimes(
            sunriseEpochMs = julianDayToEpochMs(transit - hourAngleDeg / 360.0),
            sunsetEpochMs = julianDayToEpochMs(transit + hourAngleDeg / 360.0)
        )
    }

    private fun julianDayOfLocalNoon(epochMs: Long, timeZone: TimeZone): Double {
        val calendar = Calendar.getInstance(timeZone).apply {
            timeInMillis = epochMs
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis / MS_PER_DAY + JULIAN_DAY_UNIX_EPOCH
    }

    private fun julianDayToEpochMs(julianDay: Double): Long =
        ((julianDay - JULIAN_DAY_UNIX_EPOCH) * MS_PER_DAY).roundToLong()
}
