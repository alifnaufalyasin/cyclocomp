package com.aliven.cyclocomp.sync

import java.net.URI

/** Which account server a sign-in starts from: the one the user chose, else the one this build was made for. */
object ServerDefaults {
    fun effective(saved: String, builtIn: String): String = saved.trim().ifEmpty { builtIn.trim() }
}

/**
 * Address of a server the app talks to (account server, routing server). Only https is accepted:
 * Android blocks cleartext traffic by default, so an http address would just fail at ride time.
 */
object ServerAddress {
    /** The address without a trailing slash, or null if it is not a usable https address. */
    fun normalize(input: String): String? {
        val t = input.trim().trimEnd('/')
        if (!t.startsWith("https://") || t.any { it.isWhitespace() }) return null
        val host = try {
            URI(t).host
        } catch (e: Exception) {
            null
        }
        return if (host.isNullOrBlank()) null else t
    }
}
