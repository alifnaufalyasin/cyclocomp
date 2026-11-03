package com.aliven.cyclocomp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliven.cyclocomp.core.Format
import com.aliven.cyclocomp.core.GpxParser
import com.aliven.cyclocomp.core.RideNames
import com.aliven.cyclocomp.core.RideSeries
import com.aliven.cyclocomp.core.RideSeriesBuilder
import com.aliven.cyclocomp.core.RideSummaries
import com.aliven.cyclocomp.core.RideSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private class Loaded(val summary: RideSummary?, val series: RideSeries)

@Composable
fun DetailScreen(file: File, onBack: () -> Unit, onDeleted: () -> Unit) {
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    val loaded by produceState<Loaded?>(null, file) {
        value = withContext(Dispatchers.IO) {
            try {
                val segments = file.inputStream().use { GpxParser.parse(it) }
                Loaded(RideSummaries.summarize(segments), RideSeriesBuilder.build(segments))
            } catch (e: Exception) {
                null
            }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("Back") }
            Text(RideNames.display(file.name), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        val l = loaded
        val s = l?.summary
        if (l == null || s == null) {
            Text("Loading… (or this file could not be read)")
        } else {
            Stat("Distance", "${Format.km(s.distanceM)} km")
            Stat("Moving time", Format.duration(s.movingMs))
            Stat("Elapsed time", Format.duration(s.elapsedMs))
            Stat("Avg / max speed", "${Format.kmh(s.avgSpeedMps)} / ${Format.kmh(s.maxSpeedMps)} km/h")
            Stat("Ascent / descent", "${Format.meters(s.ascentM)} / ${Format.meters(s.descentM)} m")
            if (s.avgHr != null) Stat("Heart rate avg / max", "${s.avgHr} / ${s.maxHr} bpm")

            LineChart("Speed", "km/h", l.series.speedKmh)
            LineChart("Elevation", "m", l.series.elevationM)
            LineChart("Heart rate", "bpm", l.series.hr)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { share(context, file) }, modifier = Modifier.weight(1f)) { Text("Share GPX") }
            OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.weight(1f)) { Text("Delete") }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this ride?") },
            text = { Text("${RideNames.display(file.name)} will be removed from this phone. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    file.delete()
                    onDeleted()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 16.sp)
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}
