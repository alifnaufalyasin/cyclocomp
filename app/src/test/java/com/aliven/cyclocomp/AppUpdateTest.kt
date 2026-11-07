package com.aliven.cyclocomp

import com.aliven.cyclocomp.core.ReleaseParser
import com.aliven.cyclocomp.core.SemVer
import com.aliven.cyclocomp.core.UpdateRules
import com.aliven.cyclocomp.sync.Http
import com.aliven.cyclocomp.sync.HttpResponse
import com.aliven.cyclocomp.update.AppUpdater
import com.aliven.cyclocomp.update.UpdateException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AppUpdateTest {
    @Test
    fun versionsParseCompareAndMakeIncreasingVersionCodes() {
        assertEquals(SemVer(0, 3, 1), SemVer.parse("v0.3.1"))
        assertEquals(SemVer(0, 3, 1), SemVer.parse("0.3.1-dev"))
        assertEquals(SemVer(1, 0, 0), SemVer.parse(" v1.0.0+build5 "))
        assertNull(SemVer.parse("1.2"))
        assertNull(SemVer.parse("a.b.c"))
        assertNull(SemVer.parse("nightly"))
        assertNull(SemVer.parse("1.2.1000")) // would break the versionCode scheme
        assertNull(SemVer.parse(null))

        assertTrue(SemVer(0, 10, 0) > SemVer(0, 9, 9)) // numeric, not text, comparison
        assertTrue(SemVer(1, 0, 0) > SemVer(0, 999, 999))
        assertEquals(3001, SemVer(0, 3, 1).versionCode())
        assertEquals(1002003, SemVer(1, 2, 3).versionCode())
        assertTrue(SemVer(0, 10, 0).versionCode() > SemVer(0, 9, 999).versionCode())
    }

    private val releaseJson = """
        {"tag_name":"v0.2.0","draft":false,"prerelease":false,"body":"Maps and navigation",
         "assets":[
           {"id":11,"name":"cyclocomp-0.2.0.apk","size":4242},
           {"id":12,"name":"cyclocomp-0.2.0.apk.sha256","size":90}
         ]}
    """.trimIndent()

    @Test
    fun readsTheLatestReleaseWithItsApkAndChecksum() {
        val r = ReleaseParser.parse(releaseJson)!!
        assertEquals(SemVer(0, 2, 0), r.version)
        assertEquals("v0.2.0", r.tag)
        assertEquals("Maps and navigation", r.notes)
        assertEquals(11L, r.assetId)
        assertEquals("cyclocomp-0.2.0.apk", r.assetName)
        assertEquals(4242L, r.assetBytes)
        assertEquals(12L, r.checksumAssetId)
    }

    @Test
    fun draftsPrereleasesAndReleasesWithoutAnApkAreIgnored() {
        assertNull(ReleaseParser.parse(releaseJson.replace("\"draft\":false", "\"draft\":true")))
        assertNull(ReleaseParser.parse(releaseJson.replace("\"prerelease\":false", "\"prerelease\":true")))
        assertNull(ReleaseParser.parse("""{"tag_name":"v0.2.0","assets":[{"id":1,"name":"notes.txt"}]}"""))
        assertNull(ReleaseParser.parse("""{"tag_name":"nightly","assets":[{"id":1,"name":"a.apk"}]}"""))
        assertNull(ReleaseParser.parse("not json"))
        // No checksum file is fine; the APK is still offered.
        val noSum = ReleaseParser.parse("""{"tag_name":"v0.2.0","assets":[{"id":1,"name":"a.apk","size":5}]}""")!!
        assertNull(noSum.checksumAssetId)
    }

    @Test
    fun onlyAStrictlyNewerReleaseCountsAsAnUpdate() {
        val latest = SemVer(0, 2, 0)
        assertTrue(UpdateRules.isNewer("0.1.0", latest))
        assertFalse(UpdateRules.isNewer("0.2.0", latest))
        assertFalse(UpdateRules.isNewer("0.3.0", latest))
        assertTrue(UpdateRules.isNewer("0.0.0-dev", latest)) // a CI debug build is offered the release
        assertTrue(UpdateRules.isNewer(null, latest))
    }

    @Test
    fun checksumFileFormats() {
        val h = "a".repeat(32) + "B".repeat(32)
        assertEquals(h.lowercase(), UpdateRules.parseChecksum("$h  cyclocomp-0.2.0.apk\n"))
        assertEquals(h.lowercase(), UpdateRules.parseChecksum(h))
        assertNull(UpdateRules.parseChecksum("not a checksum"))
        assertNull(UpdateRules.parseChecksum("abc123"))
    }

    private class FakeHttp(private val reply: (String) -> HttpResponse) : Http {
        var lastUrl = ""
        var lastHeaders: Map<String, String> = emptyMap()
        override fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
            lastUrl = url
            lastHeaders = headers
            return reply(url)
        }
    }

    private fun updater(http: Http) = AppUpdater("me/app", "tok", http, "https://api.test")

    @Test
    fun latestAsksTheReleasesEndpointWithTheToken() {
        val http = FakeHttp { HttpResponse(200, releaseJson.toByteArray()) }
        val r = updater(http).latest()!!
        assertEquals("https://api.test/repos/me/app/releases/latest", http.lastUrl)
        assertEquals("Bearer tok", http.lastHeaders["Authorization"])
        assertEquals("v0.2.0", r.tag)
    }

    @Test
    fun noReleaseYetIsNotAnError() {
        assertNull(updater(FakeHttp { HttpResponse(404, ByteArray(0)) }).latest())
    }

    @Test
    fun tokenProblemsGiveReadableErrors() {
        fun message(code: Int): String {
            try {
                updater(FakeHttp { HttpResponse(code, ByteArray(0)) }).latest()
            } catch (e: UpdateException) {
                return e.message!!
            }
            fail("expected UpdateException for $code")
            return ""
        }
        assertTrue(message(401).contains("rejected"))
        assertTrue(message(403).contains("permission"))
        assertTrue(message(500).contains("500"))
    }

    @Test
    fun checksumIsReadFromTheChecksumAsset() {
        val hash = "0123456789abcdef".repeat(4)
        val http = FakeHttp { HttpResponse(200, "$hash  cyclocomp-0.2.0.apk".toByteArray()) }
        val release = ReleaseParser.parse(releaseJson)!!
        assertEquals(hash, updater(http).checksum(release))
        assertEquals("https://api.test/repos/me/app/releases/assets/12", http.lastUrl)
        assertEquals("application/octet-stream", http.lastHeaders["Accept"])

        val none = ReleaseParser.parse("""{"tag_name":"v0.2.0","assets":[{"id":1,"name":"a.apk","size":5}]}""")!!
        assertNull(updater(FakeHttp { HttpResponse(200, ByteArray(0)) }).checksum(none))
    }

    @Test
    fun aBadRepoNameIsRejectedUpFront() {
        try {
            AppUpdater("not a repo", "tok", FakeHttp { HttpResponse(200, ByteArray(0)) })
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("owner/name"))
        }
    }
}
