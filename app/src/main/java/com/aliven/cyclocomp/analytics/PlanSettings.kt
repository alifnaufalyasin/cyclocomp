package com.aliven.cyclocomp.analytics

import android.content.Context
import com.aliven.cyclocomp.core.PlanGoal
import java.time.LocalDate

/** The rider's goal. Preferences on this phone; shared between phones through the repo (newest save wins). */
object PlanSettings {
    private const val PREFS = "cyclocomp_plan"

    /** Null until a goal has been saved (here or pulled from the repo). */
    fun load(context: Context): PlanGoal? {
        val p = prefs(context)
        val target = p.getString("target_km", null)?.toDoubleOrNull() ?: return null
        val start = p.getString("started_on", null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        return PlanGoal(
            weeklyTargetKm = target,
            ridesPerWeek = p.getInt("rides", 3),
            rampPercent = p.getInt("ramp", 10),
            startedOn = start,
            startKm = p.getString("start_km", null)?.toDoubleOrNull() ?: 15.0,
        )
    }

    fun updatedMs(context: Context): Long = prefs(context).getLong("updated_ms", 0L)

    fun save(context: Context, goal: PlanGoal, updatedMs: Long = System.currentTimeMillis()) {
        prefs(context).edit()
            .putString("target_km", goal.weeklyTargetKm.toString())
            .putInt("rides", goal.ridesPerWeek)
            .putInt("ramp", goal.rampPercent)
            .putString("started_on", goal.startedOn.toString())
            .putString("start_km", goal.startKm.toString())
            .putLong("updated_ms", updatedMs)
            .apply()
    }

    /** Sensible limits: a weekly goal between 5 and 500 km, 1–7 rides, a 3–20% weekly increase. */
    fun isValid(goal: PlanGoal): Boolean =
        goal.weeklyTargetKm in 5.0..500.0 && goal.ridesPerWeek in 1..7 &&
            goal.rampPercent in 3..20 && goal.startKm in 1.0..goal.weeklyTargetKm

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
