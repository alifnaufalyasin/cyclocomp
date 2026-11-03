package com.aliven.cyclocomp.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class TrackPoint(
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val ele: Double? = null,
    val accuracyM: Float? = null,
    val speedMps: Float? = null,
    val hr: Int? = null,
)

object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Great-circle distance in meters (haversine). */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1)
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
    }
}
