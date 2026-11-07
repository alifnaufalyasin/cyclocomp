package com.aliven.cyclocomp.ui

import android.Manifest
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliven.cyclocomp.core.Format
import com.aliven.cyclocomp.core.LatLon
import com.aliven.cyclocomp.core.TurnText
import com.aliven.cyclocomp.nav.NavSession
import com.aliven.cyclocomp.nav.NavState
import com.aliven.cyclocomp.map.LiveMap
import com.aliven.cyclocomp.map.MapSettings
import com.aliven.cyclocomp.map.MapSupport
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.aliven.cyclocomp.recording.RideRecorder
import com.aliven.cyclocomp.recording.RideService
import com.aliven.cyclocomp.recording.RideSnapshot
import com.aliven.cyclocomp.recording.RideStatus

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RideScreen(
    pages: List<List<DataField>>,
    onOpenHistory: () -> Unit,
    onEditPages: () -> Unit,
    onOpenSync: () -> Unit,
    onOpenRoutes: () -> Unit,
) {
    val context = LocalContext.current
    val snap by RideRecorder.snapshot.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }
    var dim by remember { mutableStateOf(false) }
    val active = snap.status != RideStatus.IDLE

    // Keep the display on only while a ride is in progress.
    val view = LocalView.current
    DisposableEffect(active) {
        view.keepScreenOn = active
        onDispose { view.keepScreenOn = false }
    }

    // Dim mode: near-black screen at minimum brightness; tap anywhere to leave it.
    LaunchedEffect(active) { if (!active) dim = false }
    DisposableEffect(dim) {
        val window = context.findActivity()?.window
        if (window != null) {
            window.attributes = window.attributes.apply {
                screenBrightness = if (dim) 0.02f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
        onDispose {
            if (window != null) {
                window.attributes = window.attributes.apply {
                    screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
        }
    }

    val startRide = {
        RideService.start(context)
        RideRecorder.start()
        message = null
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { startRide() } // the notification is optional; recording works without it being shown

    val needsNotificationPermission = {
        Build.VERSION.SDK_INT >= 33 && !granted(context, Manifest.permission.POST_NOTIFICATIONS)
    }

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            if (needsNotificationPermission()) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                startRide()
            }
        } else {
            message = "Location permission is required to record a ride."
        }
    }

    val onStart = {
        if (granted(context, Manifest.permission.ACCESS_FINE_LOCATION)) {
            if (needsNotificationPermission()) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                startRide()
            }
        } else {
            locationLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    val onStop = {
        val saved = RideRecorder.stop()
        RideService.stop(context)
        message = if (saved != null) "Saved ${saved.name}" else "No GPS points recorded"
    }

    if (dim && active) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black).clickable { dim = false },
            contentAlignment = Alignment.Center,
        ) {
            Text(Format.kmh(snap.speedMps), color = Color.DarkGray, fontSize = 72.sp)
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (active) {
                TextButton(onClick = { dim = true }) { Text("Dim screen") }
            } else {
                TextButton(onClick = onOpenHistory) { Text("History") }
                TextButton(onClick = onOpenRoutes) { Text("Routes") }
                TextButton(onClick = onEditPages) { Text("Pages") }
                TextButton(onClick = onOpenSync) { Text("Sync") }
            }
        }

        // The map is one more swipeable page after the data pages (can be switched off in Edit pages).
        val showMap = MapSettings.showMapPage(context)
        val pageCount = pages.size + if (showMap) 1 else 0
        val mapIndex = if (showMap) pages.size else -1
        val pagerState = rememberPagerState(pageCount = { pageCount })
        val pagerScope = rememberCoroutineScope()
        var follow by remember { mutableStateOf(true) }
        val fallbackCenter = remember { MapSettings.lastPosition(context) ?: MapSupport.DEFAULT_CENTER }

        // Remember where the ride ended so the map opens there next time.
        LaunchedEffect(snap.status, snap.pointCount) {
            val lat = snap.lat
            val lon = snap.lon
            if (snap.status == RideStatus.IDLE && lat != null && lon != null) {
                MapSettings.saveLastPosition(context, LatLon(lat, lon))
            }
        }

        // On the map page horizontal drags belong to the map, so paging is off there; the buttons
        // under the map lead back to the data pages.
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = pagerState.currentPage != mapIndex,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { index ->
            if (index == mapIndex) {
                val track = remember(snap.pointCount) { RideRecorder.trackSnapshot() }
                val lat = snap.lat
                val lon = snap.lon
                val nav by NavSession.state.collectAsState()
                Column(Modifier.fillMaxSize()) {
                    NavCard(nav, snap.avgSpeedMps, Modifier.fillMaxWidth())
                    LiveMap(
                        track = track,
                        position = if (lat != null && lon != null) LatLon(lat, lon) else null,
                        fallbackCenter = fallbackCenter,
                        follow = follow,
                        onUserMoved = { follow = false },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        plannedRoute = nav.route?.points.orEmpty(),
                        destination = nav.destination,
                        onLongPress = { NavSession.setDestination(context, it) },
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { pagerScope.launch { pagerState.animateScrollToPage(0) } }) {
                            Text("‹ Data")
                        }
                        TextButton(onClick = { follow = true }, enabled = !follow) { Text("Recenter") }
                    }
                }
            } else {
                DataPage(pages[index], snap)
            }
        }
        if (pageCount > 1) {
            Text(
                "Page ${pagerState.currentPage + 1} / $pageCount",
                modifier = Modifier.fillMaxWidth(),
                fontSize = 14.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (snap.status) {
                RideStatus.IDLE -> Button(onClick = { onStart() }, modifier = Modifier.weight(1f)) {
                    Text("Start")
                }
                RideStatus.RECORDING -> {
                    OutlinedButton(onClick = { RideRecorder.pause() }, modifier = Modifier.weight(1f)) {
                        Text("Pause")
                    }
                    Button(onClick = { onStop() }, modifier = Modifier.weight(1f)) { Text("Stop") }
                }
                RideStatus.PAUSED -> {
                    Button(onClick = { RideRecorder.resume() }, modifier = Modifier.weight(1f)) {
                        Text("Resume")
                    }
                    OutlinedButton(onClick = { onStop() }, modifier = Modifier.weight(1f)) { Text("Stop") }
                }
            }
        }
        message?.let { Text(it) }
    }
}

@Composable
private fun DataPage(fields: List<DataField>, snap: RideSnapshot) {
    val valueSize = when (fields.size) {
        1 -> 120.sp
        2 -> 88.sp
        3 -> 72.sp
        else -> 52.sp
    }
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        fields.forEach { field ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(field.value(snap), fontSize = valueSize, fontWeight = FontWeight.Bold)
                Text(if (field.unit.isEmpty()) field.label else "${field.label} (${field.unit})", fontSize = 16.sp)
            }
        }
    }
}

/** Shows the next maneuver and what is left of the route; hidden until a destination is set. */
@Composable
private fun NavCard(nav: NavState, avgSpeedMps: Double, modifier: Modifier = Modifier) {
    if (nav.destination == null) return
    Column(modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val route = nav.route
        val p = nav.progress
        nav.courseName?.let { Text("Following: $it", fontSize = 12.sp) }
        when {
            nav.arrived -> Text("You have arrived", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            route != null && p != null -> {
                val next = route.steps.getOrNull(p.stepIndex + 1)
                val text = if (next == null) "Arrive at your destination" else TurnText.of(next)
                Text(
                    "${TurnText.distance(p.toNextManeuverM)} · $text",
                    fontSize = 22.sp, fontWeight = FontWeight.Bold,
                )
                Text(
                    "${TurnText.distance(p.remainingM)} left · about ${TurnText.etaMinutes(p.remainingM, avgSpeedMps)} min" +
                        if (nav.loading) " · rerouting…" else "",
                    fontSize = 14.sp,
                )
            }
            nav.loading -> Text("Finding a route…", fontSize = 16.sp)
            nav.courseName != null -> Text("Waiting for GPS to start the route.", fontSize = 14.sp)
            else -> Text(nav.message ?: "Destination set. The route is found once GPS has a fix.", fontSize = 14.sp)
        }
        nav.message?.let { if (route != null || nav.arrived) Text(it, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
        TextButton(onClick = { NavSession.clear() }) { Text(if (nav.courseName != null) "Stop following" else "Clear destination") }
    }
}
