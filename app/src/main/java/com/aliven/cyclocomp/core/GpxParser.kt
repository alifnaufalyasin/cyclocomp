package com.aliven.cyclocomp.core

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.time.Instant
import javax.xml.parsers.SAXParserFactory

/**
 * Reads GPX tracks into segments. Namespace-unaware on purpose: extension tags such as
 * `gpxtpx:hr` are matched by their local name, so Strava/Garmin/Huawei-style files all work.
 * Uses SAX because it exists on both Android and the JVM (the unit tests).
 */
object GpxParser {
    fun parse(input: InputStream): List<List<TrackPoint>> {
        val factory = SAXParserFactory.newInstance()
        // Not every parser supports this feature; ignore when unsupported (never resolves entities anyway).
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        } catch (e: Exception) {
            // unsupported on this parser
        }
        val handler = Handler()
        factory.newSAXParser().parse(input, handler)
        return handler.segments.filter { it.isNotEmpty() }
    }

    private class Handler : DefaultHandler() {
        val segments = mutableListOf<MutableList<TrackPoint>>()
        private var inPoint = false
        private var lat = 0.0
        private var lon = 0.0
        private var ele: Double? = null
        private var time: Long? = null
        private var hr: Int? = null
        private val text = StringBuilder()

        override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes) {
            text.setLength(0)
            when (local(qName)) {
                "trkseg" -> segments.add(mutableListOf())
                "trkpt" -> {
                    inPoint = true
                    lat = attrs.getValue("lat")?.toDoubleOrNull() ?: Double.NaN
                    lon = attrs.getValue("lon")?.toDoubleOrNull() ?: Double.NaN
                    ele = null
                    time = null
                    hr = null
                }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            text.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            if (!inPoint) return
            when (local(qName)) {
                "ele" -> ele = text.toString().trim().toDoubleOrNull()
                "time" -> time = try {
                    Instant.parse(text.toString().trim()).toEpochMilli()
                } catch (e: Exception) {
                    null
                }
                "hr" -> hr = text.toString().trim().toIntOrNull()
                "trkpt" -> {
                    inPoint = false
                    val t = time
                    if (t != null && !lat.isNaN() && !lon.isNaN()) {
                        if (segments.isEmpty()) segments.add(mutableListOf())
                        segments.last().add(TrackPoint(timeMs = t, lat = lat, lon = lon, ele = ele, hr = hr))
                    }
                }
            }
        }

        private fun local(qName: String) = qName.substringAfter(':')
    }
}
