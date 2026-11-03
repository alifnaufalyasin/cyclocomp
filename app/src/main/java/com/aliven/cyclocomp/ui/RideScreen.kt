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
                TextButton(onClick = onEditPages) { Text("Edit pages") }
                TextButton(onClick = onOpenSync) { Text("Sync") }
            }
        }

        val pagerState = rememberPagerState(pageCount = { pages.size })
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f).fillMaxWidth()) { index ->
            DataPage(pages[index], snap)
        }
        if (pages.size > 1) {
            Text(
                "Page ${pagerState.currentPage + 1} / ${pages.size}",
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
