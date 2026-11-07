package com.aliven.cyclocomp.nav

import android.content.Context
import com.aliven.cyclocomp.core.Course
import com.aliven.cyclocomp.core.CourseSteps
import com.aliven.cyclocomp.core.LatLon
import com.aliven.cyclocomp.core.NavProgress
import com.aliven.cyclocomp.core.NavRoute
import com.aliven.cyclocomp.core.OffRouteDetector
import com.aliven.cyclocomp.core.RouteGeometry
import com.aliven.cyclocomp.core.TurnText
import com.aliven.cyclocomp.sync.UrlConnectionHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NavState(
    val destination: LatLon? = null,
    val route: NavRoute? = null,
    val progress: NavProgress? = null,
    val loading: Boolean = false,
    val message: String? = null,
    val arrived: Boolean = false,
    /** Set while following a saved GPX route instead of one found by the routing server. */
    val courseName: String? = null,
)

/**
 * Turn-by-turn navigation for the ride in progress. A destination can be set before the ride
 * starts; the route is fetched when the first GPS fix arrives, and again if the rider leaves it.
 */
object NavSession {
    private const val ARRIVED_WITHIN_M = 25.0
    private const val OFF_COURSE_M = 50.0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(NavState())
    val state: StateFlow<NavState> = _state

    @Volatile private var geometry: RouteGeometry? = null
    private val detector = OffRouteDetector()
    private var appContext: Context? = null
    private var lastPosition: LatLon? = null

    fun setDestination(context: Context, destination: LatLon) {
        appContext = context.applicationContext
        detector.reset()
        geometry = null
        _state.value = NavState(destination = destination, message = null)
        lastPosition?.let { fetch(it) }
    }

    /**
     * Follow a saved GPX route. It works without the routing server: the line and its turn cues
     * come from the file, and leaving the line is reported instead of being rerouted.
     */
    fun followCourse(course: Course) {
        val route = CourseSteps.toNavRoute(course)
        val g = RouteGeometry(route)
        detector.reset()
        geometry = g
        _state.value = NavState(
            destination = route.points.last(),
            route = route,
            progress = lastPosition?.let { g.progress(it) },
            courseName = course.name,
        )
    }

    /** The ride stopped: the last GPS position is stale now, so a new destination waits for a fresh fix. */
    fun onRideEnded() {
        lastPosition = null
    }

    fun clear() {
        detector.reset()
        geometry = null
        _state.value = NavState()
    }

    /** Called for every accepted GPS point of a recording ride. Cheap: only the local part of the route is searched. */
    fun onPosition(lat: Double, lon: Double, nowMs: Long) {
        val pos = LatLon(lat, lon)
        lastPosition = pos
        val st = _state.value
        if (st.destination == null || st.arrived) return
        val g = geometry
        if (st.route == null || g == null) {
            if (!st.loading) fetch(pos)
            return
        }
        val p = g.progress(pos, st.progress?.segment)
        if (p.remainingM <= ARRIVED_WITHIN_M) {
            _state.update { it.copy(progress = p, arrived = true, message = null) }
            return
        }
        if (st.courseName != null) {
            // A saved route is followed as it is; the rider is only told how far off the line they are.
            val off = if (p.offRouteM > OFF_COURSE_M) "Off the course (${TurnText.distance(p.offRouteM)} from it)" else null
            _state.update { it.copy(progress = p, message = off) }
            return
        }
        _state.update { it.copy(progress = p) }
        if (detector.update(p.offRouteM, nowMs) && !st.loading) fetch(pos)
    }

    private fun fetch(from: LatLon) {
        val dest = _state.value.destination ?: return
        val ctx = appContext
        val url = ctx?.let { NavSettings.osrmUrl(it) }.orEmpty()
        val avoidToll = ctx?.let { NavSettings.avoidToll(it) } ?: true
        val base = OsrmAddress.normalize(url)
        if (base == null) {
            _state.update { it.copy(message = "Set the routing server (https) in Edit pages first.") }
            return
        }
        _state.update { it.copy(loading = true, message = null) }
        scope.launch {
            try {
                val route = OsrmClient(base, UrlConnectionHttp()).route(from, dest, avoidToll)
                val g = RouteGeometry(route)
                geometry = g
                // Only keep the result if the destination was not changed or cleared meanwhile.
                _state.update {
                    if (it.destination == dest) it.copy(route = route, progress = g.progress(from), loading = false, message = null)
                    else it
                }
            } catch (e: NavException) {
                _state.update { if (it.destination == dest) it.copy(loading = false, message = e.message) else it }
            } catch (e: Exception) {
                _state.update {
                    if (it.destination == dest) it.copy(loading = false, message = e.message ?: "Routing failed") else it
                }
            }
        }
    }
}
