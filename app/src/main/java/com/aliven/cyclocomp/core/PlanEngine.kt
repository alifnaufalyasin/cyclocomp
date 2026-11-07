package com.aliven.cyclocomp.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What the rider wants: a weekly distance to reach, built up gradually. */
data class PlanGoal(
    val weeklyTargetKm: Double,
    val ridesPerWeek: Int,
    /** Largest increase from one week to the next, as a percentage of the week just ridden. */
    val rampPercent: Int,
    /** Monday of the week the plan started; every 4th week from there is a lighter recovery week. */
    val startedOn: LocalDate,
    /** Where to begin when there is no riding history yet. */
    val startKm: Double,
)

data class PlanWeek(
    val weekStart: LocalDate,
    val targetKm: Double,
    val rides: Int,
    val longRideKm: Double,
    val note: String,
    val recovery: Boolean,
)

data class PlanOutlook(
    /** The current week first, then the coming weeks. */
    val weeks: List<PlanWeek>,
    /** Weeks from the current one until the planned distance reaches the goal; null if it does not within the outlook. */
    val weeksToGoal: Int?,
)

/**
 * Turns the rider's own history into weekly distance targets. Rules, in order:
 * - build from what was actually ridden last week, not from what was planned;
 * - never raise more than the ramp percentage per week;
 * - every 4th week of the plan is a recovery week (75% of the previous target);
 * - hold or ease off when the recent load is high compared with the rider's recent average;
 * - after a gap with no riding, restart at about 70% of the recent average;
 * - never plan above the goal.
 * These are common training rules of thumb, not medical advice.
 */
object PlanEngine {
    const val RECOVERY_EVERY = 4
    const val RECOVERY_FACTOR = 0.75
    const val RESTART_FACTOR = 0.7

    fun outlook(goal: PlanGoal, overview: Overview, weeksAhead: Int = 4): PlanOutlook {
        // The last entry of overview.weeks is the week in progress; the ones before it are complete.
        val completed = overview.weeks.dropLast(1)
        val current = overview.weeks.last().weekStart
        val lastDone = completed.lastOrNull()?.distanceKm ?: 0.0
        val recent = completed.takeLast(4).map { it.distanceKm }.filter { it > 0 }
        val recentAvg = if (recent.isEmpty()) 0.0 else recent.average()
        val ratio = overview.ratio
        val ramp = goal.rampPercent / 100.0

        val weeks = mutableListOf<PlanWeek>()
        var previousTarget = lastDone
        for (i in 0..weeksAhead) {
            val week = current.plusWeeks(i.toLong())
            val index = ChronoUnit.WEEKS.between(goal.startedOn, week).toInt()
            val recovery = index > 0 && (index + 1) % RECOVERY_EVERY == 0

            var note: String
            var target: Double
            if (i == 0) {
                // The week in progress is based on real riding.
                when {
                    lastDone == 0.0 && recentAvg == 0.0 -> {
                        target = goal.startKm
                        note = "Starting point: no recent rides, so begin easy."
                    }
                    lastDone == 0.0 -> {
                        target = recentAvg * RESTART_FACTOR
                        note = "Restart after a week without rides: about 70% of your recent average."
                    }
                    else -> {
                        target = lastDone * (1 + ramp)
                        note = "Up to ${goal.rampPercent}% on last week's ${fmt(lastDone)} km."
                    }
                }
                if (ratio != null && ratio > 1.5 && lastDone > 0) {
                    target = min(target, lastDone)
                    note = "Held at last week's ${fmt(lastDone)} km: your load is high compared with your recent average."
                } else if (ratio != null && ratio > 1.3 && lastDone > 0) {
                    target = min(target, lastDone * (1 + ramp / 2))
                    note = "Half the usual increase: your load is rising quickly."
                }
            } else {
                // Later weeks continue from the plan itself.
                target = previousTarget * (1 + ramp)
                note = "Up to ${goal.rampPercent}% on the week before."
            }
            if (recovery) {
                target = previousTarget * RECOVERY_FACTOR
                note = "Recovery week: lighter on purpose so the next block builds on fresher legs."
            }
            if (target >= goal.weeklyTargetKm) {
                target = goal.weeklyTargetKm
                if (!recovery) note = "Goal distance reached: hold it here."
            }
            target = max(target, 0.0)

            val rides = max(1, goal.ridesPerWeek)
            val long = if (rides >= 2) target * 0.4 else target
            weeks.add(PlanWeek(week, round1(target), rides, round1(long), note, recovery))
            previousTarget = target
        }

        val toGoal = weeks.indexOfFirst { it.targetKm >= goal.weeklyTargetKm && !it.recovery }.takeIf { it >= 0 }
        return PlanOutlook(weeks, toGoal)
    }

    private fun round1(v: Double) = (v * 10).roundToInt() / 10.0

    private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.1f", v)
}
