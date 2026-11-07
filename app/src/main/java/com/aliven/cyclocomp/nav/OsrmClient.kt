package com.aliven.cyclocomp.nav

import android.content.Context
import com.aliven.cyclocomp.core.LatLon
import com.aliven.cyclocomp.core.NavRoute
import com.aliven.cyclocomp.core.OsrmParser
import com.aliven.cyclocomp.sync.Http
import com.aliven.cyclocomp.sync.HttpResponse
import com.aliven.cyclocomp.sync.ServerAddress
import java.util.Locale

class NavException(message: String) : Exception(message)

/**
 * Address of the user's own OSRM server. Only https is accepted: Android blocks cleartext traffic
 * by default, so an http address would just fail at ride time.
 */
object OsrmAddress {
    /** The address without a trailing slash, or null if it is not a usable https address. */
    fun normalize(input: String): String? = ServerAddress.normalize(input)
}

object NavSettings {
    private const val PREFS = "cyclocomp_nav"

    /** Empty until the user enters it; the app ships without any server address. */
    fun osrmUrl(context: Context): String = prefs(context).getString("osrm_url", "") ?: ""

    fun saveOsrmUrl(context: Context, url: String) {
        prefs(context).edit().putString("osrm_url", url.trim()).apply()
    }

    /** Keep routes off toll roads. On by default: the routing server may use a motorcycle/car profile. */
    fun avoidToll(context: Context): Boolean = prefs(context).getBoolean("avoid_toll", true)

    fun saveAvoidToll(context: Context, avoid: Boolean) {
        prefs(context).edit().putBoolean("avoid_toll", avoid).apply()
    }

    /** Spoken turn directions during navigation. On by default. */
    fun voiceCues(context: Context): Boolean = prefs(context).getBoolean("voice_cues", true)

    fun saveVoiceCues(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("voice_cues", on).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

class OsrmClient(baseUrl: String, private val http: Http) {
    private val base = baseUrl.trimEnd('/')

    /**
     * Blocking; call off the main thread. With [avoidToll] the server is asked to keep off toll roads,
     * which matters for a motorcycle/car profile; a server that does not know that option answers
     * 400 "InvalidValue", and the request is simply repeated without it.
     */
    fun route(from: LatLon, to: LatLon, avoidToll: Boolean = true): NavRoute {
        val first = request(from, to, avoidToll)
        val r = if (avoidToll && first.code == 400 && first.text.contains("InvalidValue")) request(from, to, false) else first
        if (r.code != 200) {
            // OSRM reports "NoRoute" / "NoSegment" with 400; show that instead of a bare status.
            val reason = OsrmParser.reason(r.text)
            throw NavException(if (reason != null) "No route: $reason" else "Routing server answered ${r.code}")
        }
        return OsrmParser.parse(r.text) ?: throw NavException("No route found between these points")
    }

    private fun request(from: LatLon, to: LatLon, avoidToll: Boolean): HttpResponse {
        // osrm-routed answers with whatever profile its data was built for; the name in the path is not used.
        val url = String.format(
            Locale.US,
            "%s/route/v1/bike/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson&steps=true&alternatives=false%s",
            base, from.lon, from.lat, to.lon, to.lat, if (avoidToll) "&exclude=toll" else "",
        )
        return try {
            http.request("GET", url, mapOf("Accept" to "application/json", "User-Agent" to "cyclocomp"), null)
        } catch (e: Exception) {
            throw NavException("Routing server not reachable (${e.message ?: e.javaClass.simpleName})")
        }
    }
}
