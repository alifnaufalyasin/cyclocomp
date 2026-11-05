package com.aliven.cyclocomp.map

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.views.overlay.MapEventsOverlay
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.aliven.cyclocomp.core.LatLon
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

private class MapParts(
    val view: MapView,
    val line: Polyline,
    val marker: Marker?,
    val planned: Polyline? = null,
    val dest: Marker? = null,
)

private fun List<LatLon>.geo() = map { GeoPoint(it.lat, it.lon) }

private fun newMapView(context: android.content.Context): MapView {
    MapSupport.init(context)
    return MapView(context).apply {
        setTileSource(MapSupport.tileSource(context))
        setMultiTouchControls(true)
        isTilesScaledToDpi = true
        zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
    }
}

private fun lineFor(view: MapView, colorInt: Int): Polyline = Polyline(view).apply {
    outlinePaint.color = colorInt
    outlinePaint.strokeWidth = 9f
}

/** Keeps the lifecycle calls osmdroid needs, so tiles load and the view is released properly. */
@Composable
private fun MapLifecycle(parts: MapParts) {
    DisposableEffect(parts) {
        parts.view.onResume()
        onDispose {
            parts.view.onPause()
            parts.view.onDetach()
        }
    }
}

/**
 * Live map for the ride screen: breadcrumb of the ride so far and a marker for the current
 * position. While [follow] is true the map keeps the position centred; dragging the map calls
 * [onUserMoved] so the screen can stop following.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun LiveMap(
    track: List<LatLon>,
    position: LatLon?,
    fallbackCenter: LatLon,
    follow: Boolean,
    onUserMoved: () -> Unit,
    modifier: Modifier = Modifier,
    /** The route to ride (navigation) and where it ends; empty / null when none is set. */
    plannedRoute: List<LatLon> = emptyList(),
    destination: LatLon? = null,
    /** Long-press on the map picks a destination. */
    onLongPress: (LatLon) -> Unit = {},
) {
    val context = LocalContext.current
    val longPress by rememberUpdatedState(onLongPress)
    val parts = remember {
        val view = newMapView(context)
        // Long-press events come from an overlay at the bottom of the stack.
        view.overlays.add(
            MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean = false
                override fun longPressHelper(p: GeoPoint): Boolean {
                    longPress(LatLon(p.latitude, p.longitude))
                    return true
                }
            })
        )
        val planned = lineFor(view, Color.rgb(142, 36, 170))
        val dest = Marker(view).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "Destination"
        }
        val line = lineFor(view, Color.rgb(30, 136, 229))
        val marker = Marker(view).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            setInfoWindow(null)
        }
        view.overlays.add(planned)
        view.overlays.add(line)
        view.overlays.add(dest)
        view.overlays.add(marker)
        view.controller.setZoom(16.0)
        view.controller.setCenter((position ?: fallbackCenter).let { GeoPoint(it.lat, it.lon) })
        view.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_MOVE) onUserMoved()
            false
        }
        MapParts(view, line, marker, planned, dest)
    }
    MapLifecycle(parts)

    AndroidView(
        modifier = modifier,
        factory = { parts.view },
        update = {
            parts.line.setPoints(track.geo())
            parts.planned?.setPoints(plannedRoute.geo())
            if (destination != null) {
                parts.dest?.position = GeoPoint(destination.lat, destination.lon)
                parts.dest?.isEnabled = true
            } else {
                parts.dest?.isEnabled = false
            }
            if (position != null) {
                val p = GeoPoint(position.lat, position.lon)
                parts.marker?.position = p
                parts.marker?.isEnabled = true
                if (follow) parts.view.controller.animateTo(p)
            } else {
                parts.marker?.isEnabled = false
            }
            parts.view.invalidate()
        },
    )
}

/** Static map of a finished ride: the route with start and end markers, zoomed to fit. */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun RouteMap(route: List<LatLon>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val parts = remember {
        val view = newMapView(context)
        val line = lineFor(view, Color.rgb(229, 57, 53))
        view.overlays.add(line)
        // The map sits inside a scrolling screen: keep drags for the map, not for the page.
        view.setOnTouchListener { v, ev ->
            if (ev.action == MotionEvent.ACTION_DOWN) v.parent?.requestDisallowInterceptTouchEvent(true)
            false
        }
        MapParts(view, line, null)
    }
    MapLifecycle(parts)

    AndroidView(
        modifier = modifier,
        factory = { parts.view },
        update = {
            val pts = route.geo()
            parts.line.setPoints(pts)
            parts.view.overlays.removeAll { it is Marker }
            if (pts.isNotEmpty()) {
                parts.view.overlays.add(endpoint(parts.view, pts.first(), "Start"))
                parts.view.overlays.add(endpoint(parts.view, pts.last(), "End"))
            }
            if (pts.size >= 2) {
                val box = BoundingBox.fromGeoPoints(pts)
                // Zooming needs a measured view; do it now if it is, otherwise on the first layout.
                if (parts.view.width > 0) parts.view.zoomToBoundingBox(box, false, 64)
                else parts.view.addOnFirstLayoutListener { _, _, _, _, _ -> parts.view.zoomToBoundingBox(box, false, 64) }
            } else if (pts.size == 1) {
                parts.view.controller.setZoom(16.0)
                parts.view.controller.setCenter(pts.first())
            }
            parts.view.invalidate()
        },
    )
}

private fun endpoint(view: MapView, at: GeoPoint, title: String) = Marker(view).apply {
    position = at
    this.title = title
    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
}
