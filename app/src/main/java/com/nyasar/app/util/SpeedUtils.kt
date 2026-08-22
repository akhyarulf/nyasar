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
        val converted = convertSpeed(speedKmh, targetUnit)
        val unitLabel = if (targetUnit == "mph") "mph" else "km/h"
        return "%.${decimals}f $unitLabel".format(converted)
    }

    /**
     * Convert distance from meters to the appropriate unit for display.
     * @param distanceMeters Distance in meters
     * @return Formatted distance string
     */
    fun formatDistance(distanceMeters: Double): String {
        return "%.2f km".format(distanceMeters / 1000.0)
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