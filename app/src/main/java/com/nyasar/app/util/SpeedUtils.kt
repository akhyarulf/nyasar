package com.nyasar.app.util

/**
 * Speed conversion utility for km/h and mph.
 * Used consistently across recording, activity, navigation, statistics, history, and route info.
 */
object SpeedUtils {
    /**
     * Convert speed from km/h to the target unit.
     * @param speedKmh Speed in km/h
     * @param targetUnit "kmh" or "mph"
     * @return Speed in the target unit
     */
    fun convertSpeed(speedKmh: Double, targetUnit: String): Double {
        return when (targetUnit) {
            "mph" -> speedKmh * 0.621371192
            else -> speedKmh // "kmh" or default
        }
    }

    /**
     * Format speed with appropriate unit label.
     * @param speedKmh Speed in km/h
     * @param targetUnit "kmh" or "mph"
     * @param decimals Number of decimal places
     * @return Formatted speed string with unit
     */
    fun formatSpeed(speedKmh: Double?, targetUnit: String, decimals: Int = 1): String {
        if (speedKmh == null) return "-"
        return "%.${decimals}f".format(convertSpeed(speedKmh, targetUnit)) + " " + unitLabel(targetUnit)
    }

    /**
     * Numeric-only speed value (no unit). Narrow stat grids append a
     * localized SHORT unit themselves — the full "km/h" inside a large
     * value ellipsized to "0.0 k…" in a third-width column (2026-09
     * narrow-screen report). Units as string resources: km/h via
     * speed_unit_kmh, mph via speed_unit_mph.
     */
    fun speedValue(speedKmh: Double?, targetUnit: String, decimals: Int = 1): String {
        if (speedKmh == null) return "-"
        return "%.${decimals}f".format(convertSpeed(speedKmh, targetUnit))
    }

    /** Localized-aware unit label hook: plain "km/h"/"mph" — UI that needs
     *  the short per-locale form uses the speed_unit_* string resources. */
    fun unitLabel(targetUnit: String): String = if (targetUnit == "mph") "mph" else "km/h"

    /**
     * Convert distance from meters to the appropriate unit for display.
     * @param distanceMeters Distance in meters
     * @return Formatted distance string
     */
    fun formatDistance(distanceMeters: Double): String {
        return "%.2f km".format(distanceMeters / 1000.0)
    }

    /**
     * Format pace from speed (km/h or mph).
     * Pace = minutes per distance unit (min/km or min/mi).
     * @param speedKmh Speed in km/h (null or 0 → "-" for not moving)
     * @param targetUnit "kmh" → min/km, "mph" → min/mi
     * @return Formatted pace string like "5:30 /km" or "-" when stationary
     */
    fun formatPace(speedKmh: Double?, targetUnit: String): String {
        if (speedKmh == null || speedKmh <= 0.0) return "-"
        val speedInUnit = convertSpeed(speedKmh, targetUnit)
        if (speedInUnit <= 0.0) return "-"
        val minutesPerUnit = 60.0 / speedInUnit
        val mins = minutesPerUnit.toInt()
        val secs = ((minutesPerUnit - mins) * 60).toInt()
        val unitLabel = if (targetUnit == "mph") "/mi" else "/km"
        return "%d:%02d %s".format(mins, secs, unitLabel)
    }

    /**
     * Convert distance from kilometers to miles.
     * @param km Distance in kilometers
     * @return Distance in miles
     */
    fun kmToMiles(km: Double): Double {
        return km * 0.621371192
    }

    /**
     * Format distance with appropriate unit.
     * @param distanceMeters Distance in meters
     * @param targetUnit "kmh" or "mph" (determines if we show km or miles)
     * @return Formatted distance string
     */
    fun formatDistanceWithUnit(distanceMeters: Double, targetUnit: String): String {
        return if (targetUnit == "mph") {
            "%.2f mi".format(kmToMiles(distanceMeters / 1000.0))
        } else {
            "%.2f km".format(distanceMeters / 1000.0)
        }
    }
}