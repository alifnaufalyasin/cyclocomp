package com.aliven.cyclocomp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import com.aliven.cyclocomp.analytics.PlanSettings
import com.aliven.cyclocomp.analytics.RideRecords
import com.aliven.cyclocomp.analytics.TrainingSettings
import com.aliven.cyclocomp.core.Overview
import com.aliven.cyclocomp.core.PlanEngine
import com.aliven.cyclocomp.core.PlanGoal
import com.aliven.cyclocomp.core.TrainingStats
import com.aliven.cyclocomp.recording.RideRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun PlanScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var goal by remember { mutableStateOf(PlanSettings.load(context)) }
    var message by remember { mutableStateOf<String?>(null) }

    val overview by produceState<Overview?>(null) {
        value = withContext(Dispatchers.IO) {
            TrainingStats.compute(
                RideRecords.load(RideRecorder.ridesDir()), Instant.now(), ZoneId.systemDefault(),
                TrainingSettings.restHr(context), TrainingSettings.maxHr(context),
            )
        }
    }

    // Form values; the starting distance defaults to the recent average once the history is known.
    var target by remember { mutableStateOf(goal?.weeklyTargetKm?.let { trim(it) } ?: "40") }
    var rides by remember { mutableStateOf(goal?.ridesPerWeek?.toString() ?: "3") }
    var ramp by remember { mutableStateOf(goal?.rampPercent?.toString() ?: "10") }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Training plan", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "Set a weekly distance to work towards. The plan builds up from what you actually rode last week, " +
                "adds a lighter recovery week every 4th week, and holds back when your recent load is high.",
            fontSize = 14.sp,
        )

        OutlinedTextField(
            value = target, onValueChange = { target = it }, singleLine = true,
            label = { Text("Goal: km per week") }, modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = rides, onValueChange = { rides = it }, singleLine = true,
                label = { Text("Rides per week") }, modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = ramp, onValueChange = { ramp = it }, singleLine = true,
                label = { Text("Max increase %") }, modifier = Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                modifier = Modifier.weight(1f),
                onClick = {
                    val o = overview
                    val avg = o?.weeks?.dropLast(1)?.takeLast(4)?.map { it.distanceKm }?.filter { it > 0 }
                        ?.let { if (it.isEmpty()) null else it.average() }
                    val t = target.trim().replace(',', '.').toDoubleOrNull()
                    val r = rides.trim().toIntOrNull()
                    val p = ramp.trim().toIntOrNull()
                    if (t == null || r == null || p == null) {
                        message = "Enter numbers for the goal, rides per week and the increase."
                    } else {
                        val candidate = PlanGoal(
                            weeklyTargetKm = t, ridesPerWeek = r, rampPercent = p,
                            startedOn = goal?.startedOn ?: TrainingStats.weekStart(LocalDate.now()),
                            startKm = goal?.startKm ?: (avg ?: 15.0).coerceAtMost(t),
                        )
                        if (PlanSettings.isValid(candidate)) {
                            PlanSettings.save(context, candidate)
                            goal = candidate
                            message = "Saved. Sync to share it with your other phone."
                        } else {
                            message = "Goal 5–500 km, 1–7 rides, increase 3–20%, and the start distance below the goal."
                        }
                    }
                },
            ) { Text(if (goal == null) "Start plan" else "Save") }
            if (goal != null) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        goal?.let {
                            val restarted = it.copy(startedOn = TrainingStats.weekStart(LocalDate.now()))
                            PlanSettings.save(context, restarted)
                            goal = restarted
                            message = "Plan restarted from this week."
                        }
                    },
                ) { Text("Restart from this week") }
            }
        }
        message?.let { Text(it, fontSize = 14.sp) }

        val g = goal
        val o = overview
        if (g != null && o != null) {
            val out = PlanEngine.outlook(g, o)
            val thisWeek = out.weeks.first()
            val done = o.weeks.last().distanceKm
            val fmt = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())

            Text("This week", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(
                String.format(Locale.US, "%.1f of %.1f km ridden (%d%%)", done, thisWeek.targetKm,
                    if (thisWeek.targetKm > 0) Math.round(done / thisWeek.targetKm * 100).toInt() else 0),
                fontSize = 16.sp,
            )
            Text(
                "${thisWeek.rides} rides, the longest about ${trim(thisWeek.longRideKm)} km. ${thisWeek.note}",
                fontSize = 14.sp,
            )

            Text("Coming weeks", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            out.weeks.drop(1).forEach { w ->
                Text(
                    "${w.weekStart.format(fmt)}: ${trim(w.targetKm)} km" + if (w.recovery) " (recovery)" else "",
                    fontWeight = FontWeight.SemiBold,
                )
                Text(w.note, fontSize = 12.sp)
            }
            Text(
                when (val n = out.weeksToGoal) {
                    null -> "The goal is more than ${out.weeks.size - 1} weeks away at this pace."
                    0 -> "You are at the goal distance this week."
                    else -> "At this pace you reach ${trim(g.weeklyTargetKm)} km per week in about $n weeks."
                },
                fontSize = 14.sp,
            )
        }

        Text(
            "Weekly targets are a rule of thumb, not medical advice. Listen to your body and take rest days. " +
                "For weight loss, steady rides plus your diet matter more than any single week.",
            fontSize = 12.sp,
        )
    }
}

private fun trim(v: Double): String =
    if (v == Math.floor(v)) v.toInt().toString() else String.format(Locale.US, "%.1f", v)
