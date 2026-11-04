package com.aliven.cyclocomp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliven.cyclocomp.core.Format
import com.aliven.cyclocomp.core.GpxParser
import com.aliven.cyclocomp.core.RideNames
import com.aliven.cyclocomp.core.RideSummaries
import com.aliven.cyclocomp.core.RideSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Parses a saved ride off the main thread; null while loading or if the file is unreadable. */
@Composable
fun rememberSummary(file: File): RideSummary? {
    val summary by produceState<RideSummary?>(null, file) {
        value = withContext(Dispatchers.IO) {
            try {
                file.inputStream().use { RideSummaries.summarize(GpxParser.parse(it)) }
            } catch (e: Exception) {
                null
            }
        }
    }
    return summary
}

@Composable
fun HistoryScreen(rides: List<File>, onOpen: (File) -> Unit, onOpenProgress: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Ride history", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = onOpenProgress) { Text("Progress") }
        }
        if (rides.isEmpty()) {
            Text("No rides yet. Record one from the ride screen.")
        } else {
            LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(rides, key = { it.name }) { file ->
                    val s = rememberSummary(file)
                    Column(Modifier.fillMaxWidth().clickable { onOpen(file) }) {
                        Text(RideNames.display(file.name), fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        Text(
                            if (s == null) "…" else
                                "${Format.km(s.distanceM)} km · ${Format.duration(s.movingMs)} · " +
                                    "${Format.kmh(s.avgSpeedMps)} km/h · ↑${Format.meters(s.ascentM)} m"
                        )
                    }
                }
            }
        }
    }
}
