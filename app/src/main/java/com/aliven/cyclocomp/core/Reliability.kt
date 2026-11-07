package com.aliven.cyclocomp.core

/** How much the app may use location, from Android's point of view. */
enum class LocationLevel { NONE, WHILE_IN_USE, ALWAYS }

/** Things the rider can change so that recording keeps going with the screen off. */
enum class ReliabilityAdvice { ALLOW_ALL_THE_TIME, STOP_BATTERY_SAVING }

object Reliability {
    /**
     * What is worth fixing before a ride. With no location permission at all nothing is suggested here:
     * the permission is asked for when the ride starts.
     */
    fun advice(level: LocationLevel, batteryUnrestricted: Boolean): List<ReliabilityAdvice> = buildList {
        if (level == LocationLevel.WHILE_IN_USE) add(ReliabilityAdvice.ALLOW_ALL_THE_TIME)
        if (level != LocationLevel.NONE && !batteryUnrestricted) add(ReliabilityAdvice.STOP_BATTERY_SAVING)
    }

    /** True when a ride is recording but no GPS fix has arrived for [thresholdMs] (0 = no fix expected yet). */
    fun gpsLost(nowMs: Long, lastFixMs: Long, thresholdMs: Long = 30_000): Boolean =
        lastFixMs > 0 && nowMs - lastFixMs > thresholdMs
}
