package com.aliven.cyclocomp.recording

import android.content.Context
import android.location.Location
import com.aliven.cyclocomp.core.GpxWriter
import com.aliven.cyclocomp.core.LatLon
import com.aliven.cyclocomp.core.RouteShape
import com.aliven.cyclocomp.nav.NavSession
import com.aliven.cyclocomp.core.RideStats
import com.aliven.cyclocomp.core.TrackJournal
import com.aliven.cyclocomp.core.TrackPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class RideStatus { IDLE, RECORDING, PAUSED }

data class RideSnapshot(
    val status: RideStatus = RideStatus.IDLE,
    val distanceM: Double = 0.0,
    val movingMs: Long = 0,
    val elapsedMs: Long = 0,
    val speedMps: Double = 0.0,
    val avgSpeedMps: Double = 0.0,
    val maxSpeedMps: Double = 0.0,
    val ascentM: Double = 0.0,
    /** Latest accepted GPS position and how many points the ride has so far (the map redraws when it changes). */
    val lat: Double? = null,
    val lon: Double? = null,
    val pointCount: Int = 0,
    /** When the last GPS fix arrived (wall clock); the ride start counts until the first fix. 0 when not riding. */
    val lastFixMs: Long = 0,
)

/** Process-wide ride state shared by [RideService] and the UI. All calls happen on the main thread. */
object RideRecorder {
    private lateinit var ridesDir: File
    private lateinit var journal: File
    private var stats = RideStats()
    private var started = false

    // This ride's accepted positions as (lat, lon), for the live map. Not persisted: the journal is.
    private val live = ArrayList<DoubleArray>()
    private var lastFix = 0L

    private val _snapshot = MutableStateFlow(RideSnapshot())
    val snapshot: StateFlow<RideSnapshot> = _snapshot

    fun init(context: Context) {
        if (started) return
        started = true
        ridesDir = File(context.filesDir, "rides").apply { mkdirs() }
        journal = File(ridesDir, "current.journal")
        // A leftover journal means the process died mid-ride: keep that ride instead of losing it.
        if (journal.exists()) finish()
    }

    fun ridesDir(): File = ridesDir

    fun rides(): List<File> =
        ridesDir.listFiles { f -> f.extension == "gpx" }?.sortedByDescending { it.name } ?: emptyList()

    fun start() {
        if (_snapshot.value.status != RideStatus.IDLE) return
        stats = RideStats()
        live.clear()
        journal.delete()
        lastFix = System.currentTimeMillis() // the first fix is awaited from now
        publish(RideStatus.RECORDING)
    }

    /** The ride's track so far, thinned to at most [maxPoints] so a long ride does not slow the map. */
    fun trackSnapshot(maxPoints: Int = 1500): List<LatLon> =
        RouteShape.downsample(live.map { LatLon(it[0], it[1]) }, maxPoints)

    fun pause() {
        if (_snapshot.value.status != RideStatus.RECORDING) return
        publish(RideStatus.PAUSED)
    }

    fun resume() {
        if (_snapshot.value.status != RideStatus.PAUSED) return
        stats.breakSegment()
        journal.appendText(TrackJournal.SEGMENT_LINE + "\n")
        publish(RideStatus.RECORDING)
    }

    fun onLocation(loc: Location) {
        if (_snapshot.value.status != RideStatus.RECORDING) return
        val p = TrackPoint(
            timeMs = loc.time,
            lat = loc.latitude,
            lon = loc.longitude,
            ele = if (loc.hasAltitude()) loc.altitude else null,
            accuracyM = if (loc.hasAccuracy()) loc.accuracy else null,
            speedMps = if (loc.hasSpeed()) loc.speed else null,
        )
        if (!stats.add(p)) return
        journal.appendText(TrackJournal.encode(p) + "\n")
        live.add(doubleArrayOf(p.lat, p.lon))
        NavSession.onPosition(p.lat, p.lon, p.timeMs)
        lastFix = System.currentTimeMillis()
        publish(RideStatus.RECORDING)
    }

    /** Stops the ride and returns the saved GPX, or null if no points were recorded. */
    fun stop(): File? {
        if (_snapshot.value.status == RideStatus.IDLE) return null
        val saved = finish()
        stats = RideStats()
        NavSession.onRideEnded()
        publish(RideStatus.IDLE)
        return saved
    }

    private fun finish(): File? {
        val lines = if (journal.exists()) journal.readLines() else emptyList()
        journal.delete()
        val segments = TrackJournal.decode(lines)
        val first = segments.firstOrNull()?.firstOrNull() ?: return null
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(first.timeMs))
        val file = File(ridesDir, "$stamp.gpx")
        file.writeText(GpxWriter.write("Ride $stamp", segments))
        return file
    }

    private fun publish(status: RideStatus) {
        _snapshot.value = RideSnapshot(
            status = status,
            distanceM = stats.distanceM,
            movingMs = stats.movingMs,
            elapsedMs = stats.elapsedMs,
            speedMps = stats.currentSpeedMps,
            avgSpeedMps = stats.avgSpeedMps,
            maxSpeedMps = stats.maxSpeedMps,
            ascentM = stats.ascentM,
            lat = live.lastOrNull()?.get(0),
            lon = live.lastOrNull()?.get(1),
            pointCount = live.size,
            lastFixMs = if (status == RideStatus.IDLE) 0L else lastFix,
        )
    }
}
