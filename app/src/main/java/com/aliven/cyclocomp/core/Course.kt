package com.aliven.cyclocomp.core

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import javax.xml.parsers.SAXParserFactory
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/** A planned route to follow, read from a GPX file: just the line, no times needed. */
class Course(val name: String, val points: List<LatLon>)

/**
 * Reads a planned route from GPX. Unlike a recorded ride it needs no timestamps: route points
 * (`rtept`) are used if the file has them, otherwise the track points (`trkpt`).
 */
object CourseParser {
    fun parse(input: InputStream): Course? {
        val factory = SAXParserFactory.newInstance()
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        } catch (e: Exception) {
            // unsupported on this parser
        }
        val handler = Handler()
        try {
            factory.newSAXParser().parse(input, handler)
        } catch (e: Exception) {
            return null
        }
        val points = if (handler.routePoints.size >= 2) handler.routePoints else handler.trackPoints
        if (points.size < 2) return null
        return Course(handler.name?.takeIf { it.isNotBlank() } ?: "Route", points)
    }

    private class Handler : DefaultHandler() {
        val routePoints = mutableListOf<LatLon>()
        val trackPoints = mutableListOf<LatLon>()
        var name: String? = null
        private val stack = ArrayDeque<String>()
        private val text = StringBuilder()

        override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes) {
            val tag = qName.substringAfter(':')
            stack.addLast(tag)
            text.setLength(0)
            if (tag == "rtept" || tag == "trkpt") {
                val lat = attrs.getValue("lat")?.toDoubleOrNull()
                val lon = attrs.getValue("lon")?.toDoubleOrNull()
                if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
                    (if (tag == "rtept") routePoints else trackPoints).add(LatLon(lat, lon))
                }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            text.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            val tag = qName.substringAfter(':')
            // Only the name of the route/track/file itself, never of a waypoint.
            if (tag == "name" && name == null && stack.size >= 2) {
                val parent = stack.elementAt(stack.size - 2)
                if (parent == "trk" || parent == "rte" || parent == "metadata") name = text.toString().trim()
            }
            if (stack.isNotEmpty()) stack.removeLast()
            text.setLength(0)
        }
    }
}

/** File names for saved routes. */
object RouteNames {
    /** A safe, unique file name such as `Ciamis loop.gpx` -> `Ciamis_loop.gpx` (or `Ciamis_loop-2.gpx`). */
    fun fileName(display: String, existing: Set<String>): String {
        val base = display.removeSuffix(".gpx").removeSuffix(".GPX")
            .replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.', '_', '-').take(60).ifEmpty { "route" }
        var candidate = "$base.gpx"
        var n = 2
        while (candidate in existing) candidate = "$base-${n++}.gpx"
        return candidate
    }

    fun displayName(fileName: String): String = fileName.removeSuffix(".gpx").replace('_', ' ')

    /** A name that is safe to use as a path segment when it comes back from the repository. */
    fun isSafe(fileName: String): Boolean =
        Regex("""[A-Za-z0-9._-]{1,80}\.gpx""").matches(fileName) && !fileName.contains("..")
}

/**
 * Turns a plain line into something that can be navigated: the total distance and turn cues worked out
 * from the shape of the line. A GPX route has no instructions of its own, so a turn is a clear change
 * of direction (35 degrees or more) once the line has been smoothed.
 */
object CourseSteps {
    private const val MAX_POINTS = 4_000
    private const val SIMPLIFY_M = 8.0
    private const val MIN_LEG_M = 15.0
    private const val TURN_MIN_DEG = 35.0
    private const val ASSUMED_SPEED_MPS = 5.0 // 18 km/h, only for the arrival estimate

    fun toNavRoute(course: Course): NavRoute {
        val pts = RouteShape.downsample(course.points, MAX_POINTS)
        val kx = cos(Math.toRadians(pts.first().lat)) * 111_320.0
        val ky = 110_540.0
        val xs = DoubleArray(pts.size) { pts[it].lon * kx }
        val ys = DoubleArray(pts.size) { pts[it].lat * ky }
        val cum = DoubleArray(pts.size)
        for (i in 1 until pts.size) cum[i] = cum[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        val total = cum.last()

        val kept = simplify(xs, ys, SIMPLIFY_M)
        val steps = mutableListOf(
            NavStep("depart", null, course.name, 0.0, null, 0.0, pts.first()),
        )
        for (k in 1 until kept.size - 1) {
            val i = kept[k]
            val incoming = bearing(xs, ys, kept, k, backwards = true)
            val outgoing = bearing(xs, ys, kept, k, backwards = false)
            if (incoming == null || outgoing == null) continue
            val delta = normalize(outgoing - incoming)
            if (abs(delta) >= TURN_MIN_DEG) {
                steps.add(NavStep("turn", modifier(delta), "", 0.0, null, cum[i], pts[i]))
            }
        }
        steps.add(NavStep("arrive", null, "", 0.0, null, total, pts.last()))

        // Each step's length is the distance to the next one.
        val withLengths = steps.mapIndexed { i, s ->
            val next = steps.getOrNull(i + 1)?.startAlongM ?: s.startAlongM
            s.copy(distanceM = next - s.startAlongM)
        }
        return NavRoute(pts, withLengths, total, total / ASSUMED_SPEED_MPS)
    }

    // Direction of travel arriving at / leaving kept vertex k, measured over at least MIN_LEG_M so GPS jitter is ignored.
    private fun bearing(xs: DoubleArray, ys: DoubleArray, kept: List<Int>, k: Int, backwards: Boolean): Double? {
        val here = kept[k]
        var j = k
        while (true) {
            j += if (backwards) -1 else 1
            if (j < 0 || j >= kept.size) return null
            val other = kept[j]
            val d = hypot(xs[other] - xs[here], ys[other] - ys[here])
            if (d >= MIN_LEG_M || j == 0 || j == kept.size - 1) {
                val (fx, fy, tx, ty) = if (backwards) listOf(xs[other], ys[other], xs[here], ys[here])
                else listOf(xs[here], ys[here], xs[other], ys[other])
                if (hypot(tx - fx, ty - fy) < 1.0) return null
                return Math.toDegrees(atan2(tx - fx, ty - fy)) // 0 = north, 90 = east
            }
        }
    }

    private fun normalize(deg: Double): Double {
        var d = deg % 360.0
        if (d > 180) d -= 360
        if (d <= -180) d += 360
        return d
    }

    /** Positive = clockwise = right. */
    private fun modifier(delta: Double): String {
        val side = if (delta > 0) "right" else "left"
        val a = abs(delta)
        return when {
            a >= 165 -> "uturn"
            a >= 120 -> "sharp $side"
            a >= 70 -> side
            else -> "slight $side"
        }
    }

    /** Douglas-Peucker: indices of the vertices that still describe the line within [tolerance] metres. */
    private fun simplify(xs: DoubleArray, ys: DoubleArray, tolerance: Double): List<Int> {
        val n = xs.size
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        val work = ArrayDeque<Pair<Int, Int>>()
        work.addLast(0 to n - 1)
        while (work.isNotEmpty()) {
            val (a, b) = work.removeLast()
            var worst = -1.0
            var at = -1
            for (i in a + 1 until b) {
                val d = distanceToSegment(xs[i], ys[i], xs[a], ys[a], xs[b], ys[b])
                if (d > worst) {
                    worst = d
                    at = i
                }
            }
            if (at >= 0 && worst > tolerance) {
                keep[at] = true
                work.addLast(a to at)
                work.addLast(at to b)
            }
        }
        return keep.indices.filter { keep[it] }
    }

    private fun distanceToSegment(px: Double, py: Double, x1: Double, y1: Double, x2: Double, y2: Double): Double {
        val dx = x2 - x1
        val dy = y2 - y1
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((px - x1) * dx + (py - y1) * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(px - (x1 + t * dx), py - (y1 + t * dy))
    }
}
