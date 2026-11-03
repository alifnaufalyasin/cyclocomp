package com.aliven.cyclocomp.core

import kotlin.math.abs
import kotlin.math.max

/**
 * Accumulates ride metrics from a stream of GPS points. Pure Kotlin so it can be unit tested.
 * Auto-pause is implicit: segments slower than [MOVING_MPS] add elapsed time but not moving time
 * or distance, which also stops GPS jitter at a standstill from inflating the odometer.
 */
class RideStats {
    var distanceM = 0.0
        private set
    var movingMs = 0L
        private set
    var elapsedMs = 0L
        private set
    var maxSpeedMps = 0.0
        private set
    var currentSpeedMps = 0.0
        private set
    var ascentM = 0.0
        private set
    var descentM = 0.0
        private set

    private var last: TrackPoint? = null
    private var eleRef: Double? = null

    val avgSpeedMps: Double
        get() = if (movingMs > 0) distanceM / (movingMs / 1000.0) else 0.0

    /** Call when recording resumes after a manual pause so the gap is not counted as a segment. */
    fun breakSegment() {
        last = null
        currentSpeedMps = 0.0
    }

    /** Returns true if the point was accepted (and should be persisted). */
    fun add(p: TrackPoint): Boolean {
        if ((p.accuracyM ?: 0f) > MAX_ACCURACY_M) return false
        val prev = last
        if (prev != null && p.timeMs <= prev.timeMs) return false
        last = p
        updateElevation(p.ele)
        if (prev == null) {
            currentSpeedMps = 0.0
            return true
        }
        val dtMs = p.timeMs - prev.timeMs
        val d = Geo.distanceM(prev.lat, prev.lon, p.lat, p.lon)
        val speed = p.speedMps?.toDouble() ?: (d / (dtMs / 1000.0))
        elapsedMs += dtMs
        if (speed >= MOVING_MPS) {
            movingMs += dtMs
            distanceM += d
            currentSpeedMps = speed
            if (speed <= MAX_PLAUSIBLE_MPS) maxSpeedMps = max(maxSpeedMps, speed)
        } else {
            currentSpeedMps = 0.0
        }
        return true
    }

    // Hysteresis: only count a climb/descent once it exceeds ELE_THRESHOLD_M from the last
    // reference, so GPS altitude noise doesn't accumulate into phantom ascent.
    private fun updateElevation(ele: Double?) {
        if (ele == null) return
        val ref = eleRef
        if (ref == null) {
            eleRef = ele
            return
        }
        val delta = ele - ref
        if (abs(delta) >= ELE_THRESHOLD_M) {
            if (delta > 0) ascentM += delta else descentM += -delta
            eleRef = ele
        }
    }

    companion object {
        const val MOVING_MPS = 1.0
        const val MAX_ACCURACY_M = 30f
        const val MAX_PLAUSIBLE_MPS = 25.0
        const val ELE_THRESHOLD_M = 3.0
    }
}
