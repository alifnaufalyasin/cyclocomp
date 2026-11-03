package com.aliven.cyclocomp.core

import java.time.Instant

object GpxWriter {
    fun write(name: String, segments: List<List<TrackPoint>>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<gpx version="1.1" creator="cyclocomp" xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
        sb.append("  <trk>\n    <name>").append(escape(name)).append("</name>\n")
        for (seg in segments) {
            if (seg.isEmpty()) continue
            sb.append("    <trkseg>\n")
            for (p in seg) {
                sb.append("      <trkpt lat=\"").append(p.lat).append("\" lon=\"").append(p.lon).append("\">")
                p.ele?.let { sb.append("<ele>").append(it).append("</ele>") }
                sb.append("<time>").append(Instant.ofEpochMilli(p.timeMs)).append("</time>")
                sb.append("</trkpt>\n")
            }
            sb.append("    </trkseg>\n")
        }
        sb.append("  </trk>\n</gpx>\n")
        return sb.toString()
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
