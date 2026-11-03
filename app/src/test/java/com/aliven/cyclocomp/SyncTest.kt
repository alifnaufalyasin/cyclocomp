package com.aliven.cyclocomp

import com.aliven.cyclocomp.core.RideSummary
import com.aliven.cyclocomp.sync.GitHubDataRepo
import com.aliven.cyclocomp.sync.GitHubException
import com.aliven.cyclocomp.sync.Http
import com.aliven.cyclocomp.sync.HttpResponse
import com.aliven.cyclocomp.sync.PutResult
import com.aliven.cyclocomp.sync.RepoPaths
import com.aliven.cyclocomp.sync.RideSummaryJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

class SyncTest {
    private class Call(val method: String, val url: String, val headers: Map<String, String>, val body: String?)

    private class FakeHttp(private val reply: (Call) -> HttpResponse) : Http {
        val calls = mutableListOf<Call>()
        override fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
            val c = Call(method, url, headers, body?.toString(Charsets.UTF_8))
            calls.add(c)
            return reply(c)
        }
    }

    private fun resp(code: Int, body: String = "") = HttpResponse(code, body.toByteArray())
    private fun repo(http: Http) = GitHubDataRepo("me/data", "tok123", http, "https://api.test")

    @Test
    fun putNewSendsAuthenticatedBase64Body() {
        val http = FakeHttp { resp(201) }
        assertEquals(PutResult.CREATED, repo(http).putNew("inbox/ride phone/a b.jpg", byteArrayOf(1, 2, 3), "msg"))
        val c = http.calls.single()
        assertEquals("PUT", c.method)
        assertEquals("https://api.test/repos/me/data/contents/inbox/ride%20phone/a%20b.jpg", c.url)
        assertEquals("Bearer tok123", c.headers["Authorization"])
        val json = JSONObject(c.body!!)
        assertEquals("msg", json.getString("message"))
        assertEquals(listOf<Byte>(1, 2, 3), Base64.getDecoder().decode(json.getString("content")).toList())
        assertFalse(json.has("sha")) // never overwrites an existing file
    }

    @Test
    fun existingFileIsReportedNotOverwritten() {
        val http = FakeHttp { resp(422, """{"message":"Invalid request.\n\n\"sha\" wasn't supplied."}""") }
        assertEquals(PutResult.EXISTS, repo(http).putNew("a.txt", byteArrayOf(), "m"))
    }

    @Test
    fun otherUnprocessableAndAuthErrorsThrowReadableMessages() {
        try {
            repo(FakeHttp { resp(422, """{"message":"something else"}""") }).putNew("a", byteArrayOf(), "m")
            fail("expected exception")
        } catch (e: GitHubException) {
            assertEquals(422, e.code)
        }
        try {
            repo(FakeHttp { resp(401) }).checkAccess()
            fail("expected exception")
        } catch (e: GitHubException) {
            assertTrue(e.message!!.contains("token"))
        }
        try {
            repo(FakeHttp { resp(403) }).putNew("a", byteArrayOf(), "m")
            fail("expected exception")
        } catch (e: GitHubException) {
            assertTrue(e.message!!.contains("Contents"))
        }
    }

    @Test
    fun listParsesDirectoryAndTreatsMissingAsEmpty() {
        val dir = """[{"name":"a.jpg","type":"file","path":"inbox/x/a.jpg"},{"name":"sub","type":"dir","path":"inbox/x/sub"}]"""
        val entries = repo(FakeHttp { resp(200, dir) }).list("inbox/x")
        assertEquals(listOf("a.jpg", "sub"), entries.map { it.name })
        assertEquals("dir", entries[1].type)
        assertTrue(repo(FakeHttp { resp(404) }).list("inbox/none").isEmpty())
        assertTrue(repo(FakeHttp { resp(200, """{"name":"file"}""") }).list("a.txt").isEmpty())
    }

    @Test
    fun getRawAsksForRawContentAndReturnsNullWhenMissing() {
        val http = FakeHttp { resp(200, "hello") }
        assertEquals("hello", String(repo(http).getRaw("a.txt")!!))
        assertTrue(http.calls.single().headers["Accept"]!!.contains("raw"))
        assertEquals(null, repo(FakeHttp { resp(404) }).getRaw("nope"))
    }

    @Test
    fun repoPathsValidateAndSanitize() {
        assertTrue(RepoPaths.isValidSlug("me/cyclocomp-data"))
        assertFalse(RepoPaths.isValidSlug("me"))
        assertFalse(RepoPaths.isValidSlug("me/data/extra"))
        assertFalse(RepoPaths.isValidSlug("me/da ta"))
        assertEquals("ride-phone", RepoPaths.safeSegment("ride-phone"))
        assertEquals("a_b_c.jpg", RepoPaths.safeSegment("a b/c.jpg"))
        assertEquals("file", RepoPaths.safeSegment("../.."))
        assertFalse(RepoPaths.safeSegment("../../etc/passwd").contains("/"))
    }

    @Test
    fun summaryJsonHasExpectedFields() {
        val s = RideSummary(
            startMs = 0, distanceM = 18_630.4, movingMs = 3_586_000, elapsedMs = 3_600_000,
            avgSpeedMps = 5.19, maxSpeedMps = 12.1, ascentM = 40.4, descentM = 38.0, avgHr = 143, maxHr = 170,
        )
        val j = JSONObject(RideSummaryJson.toJson("20261103_064238.gpx", s))
        assertEquals("20261103_064238.gpx", j.getString("file"))
        assertEquals("1970-01-01T00:00:00Z", j.getString("start"))
        assertEquals(18_630, j.getInt("distance_m"))
        assertEquals(3586, j.getInt("moving_s"))
        assertEquals(143, j.getInt("avg_hr"))
        val noHr = JSONObject(RideSummaryJson.toJson("x.gpx", s.copy(avgHr = null, maxHr = null)))
        assertFalse(noHr.has("avg_hr"))
    }
}
