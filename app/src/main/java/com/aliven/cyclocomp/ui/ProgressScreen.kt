package com.aliven.cyclocomp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliven.cyclocomp.analytics.RideRecords
import com.aliven.cyclocomp.analytics.TrainingSettings
import com.aliven.cyclocomp.core.Overview
import com.aliven.cyclocomp.core.TrainingStats
import com.aliven.cyclocomp.recording.RideRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun ProgressScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var rest by remember { mutableStateOf(TrainingSettings.restHr(context).toString()) }
    var max by remember { mutableStateOf(TrainingSettings.maxHr(context).toString()) }
    var applied by remember { mutableStateOf(TrainingSettings.restHr(context) to TrainingSettings.maxHr(context)) }
    var message by remember { mutableStateOf<String?>(null) }

    val overview by produceState<Overview?>(null, applied) {
        value = withContext(Dispatchers.IO) {
            val records = RideRecords.load(RideRecorder.ridesDir())
            TrainingStats.compute(records, Instant.now(), ZoneId.systemDefault(), applied.first, applied.second)
        }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Progress", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        val o = overview
        if (o == null) {
            Text("Calculating…")
        } else {
            BarChart("Distance per week", "km", o.weeks.map { it.distanceKm.toFloat() })
            BarChart("Training load per week", "load", o.weeks.map { it.load.toFloat() })

            val fmt = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
            Text("Weeks (oldest first, last = this week)", fontWeight = FontWeight.Bold)
            o.weeks.forEach { w ->
                Text(
                    String.format(
                        Locale.US, "%s  %d rides  %.1f km  %d min  ↑%d m  load %.0f%s",
                        w.weekStart.format(fmt), w.rides, w.distanceKm, w.movingMin, w.ascentM, w.load,
                        if (w.kcal > 0) "  ${w.kcal} kcal" else "",
                    ),
                    fontSize = 14.sp,
                )
            }

            Text(
                String.format(
                    Locale.US, "Load last 7 days %.0f · average week over 28 days %.0f%s",
                    o.acuteLoad, o.chronicLoad, o.ratio?.let { String.format(Locale.US, " · ratio %.2f", it) } ?: "",
                ),
                fontWeight = FontWeight.SemiBold,
            )
            o.notes.forEach { Text("• $it", fontSize = 14.sp) }
        }

        Text("Heart rate settings (used for training load)", fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = rest, onValueChange = { rest = it }, singleLine = true,
                label = { Text("Resting HR") }, modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = max, onValueChange = { max = it }, singleLine = true,
                label = { Text("Max HR") }, modifier = Modifier.weight(1f),
            )
        }
        OutlinedButton(
            onClick = {
                val r = rest.trim().toIntOrNull()
                val m = max.trim().toIntOrNull()
                if (r != null && m != null && TrainingSettings.isValid(r, m)) {
                    TrainingSettings.save(context, r, m)
                    applied = r to m
                    message = "Saved."
                } else {
                    message = "Resting HR should be 30–100 and below max HR (120–230)."
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save and recalculate") }
        message?.let { Text(it, fontSize = 14.sp) }
        Text(
            "Load is a heart-rate estimate (TRIMP) and the notes are rules of thumb, not medical advice. " +
                "Defaults are rough; use your real resting and maximum heart rate for better numbers.",
            fontSize = 12.sp,
        )
    }
}
