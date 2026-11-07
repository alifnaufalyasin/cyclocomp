package com.aliven.cyclocomp.core

import org.json.JSONObject

/** A release version such as `v0.3.1` or `0.3.1`. Anything after a `-` (e.g. `-dev`) is ignored. */
data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int =
        compareValuesBy(this, other, SemVer::major, SemVer::minor, SemVer::patch)

    override fun toString() = "$major.$minor.$patch"

    /** The Android versionCode used for this version; always increases with the version. */
    fun versionCode(): Int = major * 1_000_000 + minor * 1_000 + patch

    companion object {
        fun parse(text: String?): SemVer? {
            val core = text?.trim()?.removePrefix("v")?.substringBefore('-')?.substringBefore('+') ?: return null
            val parts = core.split('.')
            if (parts.size != 3) return null
            val nums = parts.map { it.toIntOrNull() ?: return null }
            if (nums.any { it < 0 || it >= 1000 }) return null
            return SemVer(nums[0], nums[1], nums[2])
        }
    }
}

/** The newest published release and its APK. */
data class ReleaseInfo(
    val version: SemVer,
    val tag: String,
    val notes: String,
    val assetId: Long,
    val assetName: String,
    val assetBytes: Long,
    /** SHA-256 of the APK as published next to it (`<name>.sha256`), if present. */
    val checksumAssetId: Long?,
)

object ReleaseParser {
    /** Reads GitHub's `releases/latest` answer. Null if it is a draft/prerelease, has no APK, or an odd tag. */
    fun parse(text: String): ReleaseInfo? = try {
        val o = JSONObject(text)
        val tag = o.getString("tag_name")
        val version = SemVer.parse(tag)
        if (version == null || o.optBoolean("draft") || o.optBoolean("prerelease")) null
        else {
            val assets = o.getJSONArray("assets")
            var apk: JSONObject? = null
            var sum: JSONObject? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.getString("name")
                when {
                    name.endsWith(".apk", ignoreCase = true) && apk == null -> apk = a
                    name.endsWith(".apk.sha256", ignoreCase = true) -> sum = a
                }
            }
            if (apk == null) null
            else ReleaseInfo(
                version = version, tag = tag, notes = o.optString("body"),
                assetId = apk.getLong("id"), assetName = apk.getString("name"), assetBytes = apk.optLong("size"),
                checksumAssetId = sum?.getLong("id"),
            )
        }
    } catch (e: Exception) {
        null
    }
}

object UpdateRules {
    /** A release counts as an update only if it is strictly newer than the installed version. */
    fun isNewer(installedVersionName: String?, latest: SemVer): Boolean {
        val installed = SemVer.parse(installedVersionName) ?: return true // unknown/dev build: offer the release
        return latest > installed
    }

    /** First hex SHA-256 in a `.sha256` file (`<hash>  <file>` or just `<hash>`). */
    fun parseChecksum(text: String): String? =
        Regex("""\b[0-9a-fA-F]{64}\b""").find(text)?.value?.lowercase()
}
