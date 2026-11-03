package com.aliven.cyclocomp.core

import kotlin.math.abs

class RideCandidate(val name: String, val startMs: Long, val distanceM: Double)

sealed interface ImportDecision {
    /** The watch recorded the same ride the phone did: copy heart rate onto the phone's track. */
    class Merge(val rideName: String) : ImportDecision

    /** No phone ride matches: keep the file as a new ride. */
    data object New : ImportDecision

    class Skip(val reason: String) : ImportDecision
}

/**
 * Decides what to do with a GPX that arrived from the watch.
 *
 * Matching uses start time and distance, never duration: real watch exports can contain long
 * time jumps between stretches (pauses), so the end time and duration are not reliable.
 */
object ImportPlanner {
    const val MAX_START_DIFF_MS = 10 * 60 * 1000L
    const val MAX_DISTANCE_RATIO = 0.15

    fun decide(local: List<RideCandidate>, watch: List<List<TrackPoint>>): ImportDecision {
        val summary = RideSummaries.summarize(watch) ?: return ImportDecision.Skip("no track points in file")
        val best = local
            .filter { abs(it.startMs - summary.startMs) <= MAX_START_DIFF_MS }
            .filter { distanceClose(it.distanceM, summary.distanceM) }
            .minByOrNull { abs(it.startMs - summary.startMs) }
        if (best != null) return ImportDecision.Merge(best.name)
        // Same start time but a different distance is suspicious: do not silently create a duplicate.
        val sameStart = local.any { abs(it.startMs - summary.startMs) <= MAX_START_DIFF_MS }
        return if (sameStart) {
            ImportDecision.Skip("starts like an existing ride but the distance differs by more than 15%")
        } else {
            ImportDecision.New
        }
    }

    private fun distanceClose(a: Double, b: Double): Boolean {
        val larger = maxOf(a, b)
        return larger > 0 && abs(a - b) / larger <= MAX_DISTANCE_RATIO
    }
}

object HrMerger {
    const val MAX_GAP_MS = 10_000L

    /** Returns copies of [phone] with `hr` taken from the nearest watch point within [maxGapMs]. */
    fun merge(
        phone: List<List<TrackPoint>>,
        watch: List<List<TrackPoint>>,
        maxGapMs: Long = MAX_GAP_MS,
    ): List<List<TrackPoint>> {
        val hrPoints = watch.flatten().filter { it.hr != null }.sortedBy { it.timeMs }
        if (hrPoints.isEmpty()) return phone
        return phone.map { seg ->
            seg.map { p ->
                val nearest = nearest(hrPoints, p.timeMs)
                if (nearest != null && abs(nearest.timeMs - p.timeMs) <= maxGapMs) p.copy(hr = nearest.hr) else p
            }
        }
    }

    fun countWithHr(segments: List<List<TrackPoint>>) = segments.sumOf { seg -> seg.count { it.hr != null } }

    private fun nearest(sorted: List<TrackPoint>, t: Long): TrackPoint? {
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid].timeMs < t) lo = mid + 1 else hi = mid
        }
        val after = sorted.getOrNull(lo)
        val before = sorted.getOrNull(lo - 1)
        return when {
            after == null -> before
            before == null -> after
            abs(after.timeMs - t) < abs(t - before.timeMs) -> after
            else -> before
        }
    }
}
