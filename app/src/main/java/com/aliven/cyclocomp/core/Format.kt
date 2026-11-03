package com.aliven.cyclocomp.core

import java.util.Locale

object Format {
    fun duration(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%d:%02d", m, sec)
    }

    fun kmh(mps: Double): String = String.format(Locale.US, "%.1f", mps * 3.6)

    fun km(meters: Double): String = String.format(Locale.US, "%.2f", meters / 1000.0)

    fun meters(m: Double): String = String.format(Locale.US, "%.0f", m)
}
