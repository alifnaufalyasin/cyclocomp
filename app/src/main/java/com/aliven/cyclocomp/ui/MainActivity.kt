package com.aliven.cyclocomp.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.aliven.cyclocomp.core.Format
import com.aliven.cyclocomp.recording.RideRecorder
import com.aliven.cyclocomp.recording.RideService
import com.aliven.cyclocomp.recording.RideStatus
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RideRecorder.init(applicationContext)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) { RideScreen() }
            }
        }
    }
}

@Composable
private fun RideScreen() {
    val context = LocalContext.current
    val snap by RideRecorder.snapshot.collectAsState()
    var rides by remember { mutableStateOf(RideRecorder.rides()) }
    var message by remember { mutableStateOf<String?>(null) }

    // Keep the display on only while a ride is in progress.
    val view = LocalView.current
    val active = snap.status != RideStatus.IDLE
    DisposableEffect(active) {
        view.keepScreenOn = active
        onDispose { view.keepScreenOn = false }
    }

    val startRide = {
        RideService.start(context)
        RideRecorder.start()
        message = null
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { startRide() } // the notification is optional; recording works without it being shown

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            if (Build.VERSION.SDK_INT >= 33 && !granted(context, Manifest.permission.POST_NOTIFICATIONS)) {
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
            if (Build.VERSION.SDK_INT >= 33 && !granted(context, Manifest.permission.POST_NOTIFICATIONS)) {
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

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(Format.kmh(snap.speedMps), fontSize = 96.sp, fontWeight = FontWeight.Bold)
            Text("km/h", fontSize = 20.sp)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Metric("Distance km", Format.km(snap.distanceM))
            Metric("Moving", Format.duration(snap.movingMs))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Metric("Avg km/h", Format.kmh(snap.avgSpeedMps))
            Metric("Max km/h", Format.kmh(snap.maxSpeedMps))
            Metric("Ascent m", Format.meters(snap.ascentM))
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
                    Button(onClick = {
                        val saved = RideRecorder.stop()
                        RideService.stop(context)
                        rides = RideRecorder.rides()
                        message = if (saved != null) "Saved ${saved.name}" else "No GPS points recorded"
                    }, modifier = Modifier.weight(1f)) { Text("Stop") }
                }
                RideStatus.PAUSED -> {
                    Button(onClick = { RideRecorder.resume() }, modifier = Modifier.weight(1f)) {
                        Text("Resume")
                    }
                    OutlinedButton(onClick = {
                        val saved = RideRecorder.stop()
                        RideService.stop(context)
                        rides = RideRecorder.rides()
                        message = if (saved != null) "Saved ${saved.name}" else "No GPS points recorded"
                    }, modifier = Modifier.weight(1f)) { Text("Stop") }
                }
            }
        }

        message?.let { Text(it) }

        if (snap.status == RideStatus.IDLE) {
            Text("Saved rides (tap to share GPX)", fontWeight = FontWeight.Bold)
            LazyColumn(Modifier.fillMaxWidth()) {
                items(rides, key = { it.name }) { file ->
                    TextButton(onClick = { share(context, file) }, modifier = Modifier.fillMaxWidth()) {
                        Text(file.name)
                    }
                }
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
        Text(label, fontSize = 14.sp)
    }
}

private fun granted(context: android.content.Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun share(context: android.content.Context, file: File) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/gpx+xml"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share ride"))
}
