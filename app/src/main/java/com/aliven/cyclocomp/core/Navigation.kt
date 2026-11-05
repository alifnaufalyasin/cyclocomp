package com.aliven.cyclocomp.core

import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** One instruction of a route. [startAlongM] is how far along the route it begins. */
data class NavStep(
    val type: String,
    val modifier: String?,
    val name: String,
    val distanceM: Double,
    val exit: Int?,
    val startAlongM: Double,
    val at: LatLon,
)

data class NavRoute(
    val points: List<LatLon>,
    val steps: List<NavStep>,
    val distanceM: Double,
    val durationS: Double,
)

/** Reads an OSRM `/route/v1` response (geometries=geojson, steps=true). Null if it has no route. */
object OsrmParser {
    /** OSRM's own error code and message (e.g. `NoRoute`) from a failed response, if it sent one. */
    fun reason(text: String): String? = try {
        val o = JSONObject(text)
        val code = o.optString("code")
        if (code.isEmpty() || code == "Ok") null else (o.optString("message").ifEmpty { code })
    } catch (e: Exception) {
        null
    }

    fun parse(text: String): NavRoute? = try {
        val root = JSONObject(text)
        if (root.optString("code") != "Ok") null
        else {
            val route = root.getJSONArray("routes").getJSONObject(0)
            val coords = route.getJSONObject("geometry").getJSONArray("coordinates")
            val points = List(coords.length()) { i ->
                val c = coords.getJSONArray(i)
                LatLon(c.getDouble(1), c.getDouble(0)) // GeoJSON order is lon, lat
            }
            val steps = mutableListOf<NavStep>()
            var along = 0.0
            val legs = route.getJSONArray("legs")
            for (l in 0 until legs.length()) {
                val raw = legs.getJSONObject(l).optJSONArray("steps") ?: continue
                for (s in 0 until raw.length()) {
                    val o = raw.getJSONObject(s)
                    val m = o.getJSONObject("maneuver")
                    val loc = m.getJSONArray("location")
                    steps.add(
                        NavStep(
                            type = m.optString("type"),
                            modifier = m.optString("modifier").ifEmpty { null },
                            name = o.optString("name"),
                            distanceM = o.optDouble("distance", 0.0),
                            exit = if (m.has("exit")) m.getInt("exit") else null,
                            startAlongM = along,
                            at = LatLon(loc.getDouble(1), loc.getDouble(0)),
                        )
                    )
                    along += o.optDouble("distance", 0.0)
                }
            }
            if (points.size < 2) null
            else NavRoute(points, steps, route.optDouble("distance", along), route.optDouble("duration", 0.0))
        }
    } catch (e: Exception) {
        null
    }
}

/** Where a position is relative to a route. */
class NavProgress(
    /** Distance travelled along the route at the nearest point. */
    val alongM: Double,
    /** How far the position is from the route line. */
    val offRouteM: Double,
    val remainingM: Double,
    /** Index of the step being ridden (the one whose end is the next maneuver), or -1 if there are no steps. */
    val stepIndex: Int,
    val toNextManeuverM: Double,
    /** Segment the position was snapped to; pass it back as the hint to keep the search local. */
    val segment: Int,
)

class RouteGeometry(val route: NavRoute) {
    private val refLat = route.points.first().lat
    private val kx = cos(Math.toRadians(refLat)) * 111_320.0
    private val ky = 110_540.0
    private val xs = DoubleArray(route.points.size) { route.points[it].lon * kx }
    private val ys = DoubleArray(route.points.size) { route.points[it].lat * ky }
    private val cum = DoubleArray(route.points.size).also { c ->
        for (i in 1 until c.size) c[i] = c[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
    }

    val lengthM: Double get() = cum.last()

    /**
     * Snaps [pos] to the route. With a [hintSegment] only nearby segments are searched, so a route
     * that doubles back on itself (an out-and-back ride) is not mistaken for its other half.
     */
    fun progress(pos: LatLon, hintSegment: Int? = null): NavProgress {
        val px = pos.lon * kx
        val py = pos.lat * ky
        val last = route.points.size - 2
        val hint = hintSegment?.coerceIn(0, last)
        val from = if (hint == null) 0 else max(0, hint - 5)
        val to = if (hint == null) last else min(last, hint + 200)

        var bestDist = Double.MAX_VALUE
        var bestSeg = from
        var bestAlong = 0.0
        for (i in from..to) {
            val dx = xs[i + 1] - xs[i]
            val dy = ys[i + 1] - ys[i]
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (((px - xs[i]) * dx + (py - ys[i]) * dy) / len2).coerceIn(0.0, 1.0)
            val d = hypot(px - (xs[i] + t * dx), py - (ys[i] + t * dy))
            if (d < bestDist) {
                bestDist = d
                bestSeg = i
                bestAlong = cum[i] + t * hypot(dx, dy)
            }
        }

        // OSRM's distances and this flat-earth estimate differ slightly; scale to OSRM's so steps line up.
        val measured = cum.last()
        val total = if (route.distanceM > 0) route.distanceM else measured
        val along = if (measured > 0) bestAlong * total / measured else 0.0
        val steps = route.steps
        // The step being ridden is the last one that started at or before here; the maneuver is where the next one starts.
        var idx = -1
        for (k in steps.indices) {
            if (steps[k].startAlongM <= along + 1.0) idx = k else break
        }
        val nextStart = steps.getOrNull(idx + 1)?.startAlongM ?: total
        return NavProgress(
            alongM = along,
            offRouteM = bestDist,
            remainingM = max(0.0, total - along),
            stepIndex = idx,
            toNextManeuverM = max(0.0, nextStart - along),
            segment = bestSeg,
        )
    }
}

/** Asks for a new route once the rider has been away from the current one for a while. */
class OffRouteDetector(
    private val thresholdM: Double = 50.0,
    private val minOffMs: Long = 8_000,
    private val cooldownMs: Long = 30_000,
) {
    private var offSince: Long? = null
    private var lastReroute: Long = Long.MIN_VALUE / 2

    /** True when a reroute should be requested now. */
    fun update(offRouteM: Double, nowMs: Long): Boolean {
        if (offRouteM <= thresholdM) {
            offSince = null
            return false
        }
        val since = offSince ?: nowMs.also { offSince = it }
        if (nowMs - since < minOffMs || nowMs - lastReroute < cooldownMs) return false
        lastReroute = nowMs
        offSince = null
        return true
    }

    fun reset() {
        offSince = null
        lastReroute = Long.MIN_VALUE / 2
    }
}

object TurnText {
    fun distance(m: Double): String = when {
        m >= 10_000 -> "${Math.round(m / 1000)} km"
        m >= 1_000 -> String.format(java.util.Locale.US, "%.1f km", m / 1000)
        else -> "${(Math.round(m / 10) * 10).toInt()} m"
    }

    fun of(step: NavStep): String {
        val road = if (step.name.isBlank()) "" else " onto ${step.name}"
        val dir = step.modifier
        return when (step.type) {
            "depart" -> if (step.name.isBlank()) "Head out" else "Head out on ${step.name}"
            "arrive" -> "Arrive at your destination"
            "roundabout", "rotary" ->
                if (step.exit != null) "At the roundabout take exit ${step.exit}$road" else "Enter the roundabout$road"
            // OSRM adds a separate step when the rider leaves a roundabout.
            "exit roundabout", "exit rotary" -> "Leave the roundabout$road"
            "roundabout turn" -> if (dir == null) "Continue$road" else "At the roundabout turn $dir$road"
            "continue" -> if (dir == null || dir == "straight") "Continue straight" else "Continue $dir"
            "new name" -> "Continue$road"
            "turn", "end of road", "fork", "merge", "on ramp", "off ramp" ->
                when (dir) {
                    null -> "Continue$road"
                    "straight" -> "Continue straight$road"
                    "uturn" -> "Make a U-turn"
                    else -> "Turn $dir$road"
                }
            else -> if (dir == null) "Continue$road" else "Go $dir$road"
        }
    }

    /** Arrival estimate from the remaining distance at the average speed so far (falls back to [fallbackKmh]). */
    fun etaMinutes(remainingM: Double, avgSpeedMps: Double, fallbackKmh: Double = 18.0): Int {
        val v = if (avgSpeedMps > 1.0) avgSpeedMps else fallbackKmh / 3.6
        return Math.ceil(remainingM / v / 60.0).toInt()
    }
}

/** Distance helper kept next to the navigation code: straight-line metres between two positions. */
fun LatLon.distanceTo(other: LatLon): Double = Geo.distanceM(lat, lon, other.lat, other.lon)
