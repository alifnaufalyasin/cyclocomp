package com.aliven.cyclocomp.core

/** A position on the map, kept free of any map-library type so it can be unit tested. */
data class LatLon(val lat: Double, val lon: Double)

object RouteShape {
    /** All track points of a ride in order, thinned to at most [maxPoints] while keeping the first and last. */
    fun points(segments: List<List<TrackPoint>>, maxPoints: Int = 800): List<LatLon> =
        downsample(segments.flatten().map { LatLon(it.lat, it.lon) }, maxPoints)

    fun <T> downsample(items: List<T>, maxPoints: Int): List<T> {
        if (items.size <= maxPoints || maxPoints < 2) return items
        return List(maxPoints) { items[it * (items.size - 1) / (maxPoints - 1)] }
    }
}

/**
 * Raster tile URL template such as `https://tiles.example.org/{z}/{x}/{y}.png`.
 * Only https is accepted: Android blocks cleartext traffic by default, so an http address would
 * silently show an empty map.
 */
object TileTemplate {
    fun isValid(template: String): Boolean {
        val t = template.trim()
        return t.startsWith("https://") && t.length > "https://".length &&
            "{z}" in t && "{x}" in t && "{y}" in t && !t.any { it.isWhitespace() }
    }

    fun expand(template: String, z: Int, x: Int, y: Int): String =
        template.trim().replace("{z}", z.toString()).replace("{x}", x.toString()).replace("{y}", y.toString())
}
