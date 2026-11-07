package com.aliven.cyclocomp

import com.aliven.cyclocomp.sync.GitSha
import com.aliven.cyclocomp.sync.Http
import com.aliven.cyclocomp.sync.HttpResponse
import com.aliven.cyclocomp.sync.JwtExpiry
import com.aliven.cyclocomp.sync.Multipart
import com.aliven.cyclocomp.sync.PasswordRules
import com.aliven.cyclocomp.sync.PbSession
import com.aliven.cyclocomp.sync.PocketBaseAuth
import com.aliven.cyclocomp.sync.PocketBaseStore
import com.aliven.cyclocomp.sync.PutResult
import com.aliven.cyclocomp.sync.ServerAddress
import com.aliven.cyclocomp.sync.ServerDefaults
import com.aliven.cyclocomp.sync.StoreException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.util.Base64

/** A tiny in-memory stand-in for the account server, following what the real one answered in the smoke test. */
private class FakeServer : Http {
    class Row(val id: String, val path: String, var sha: String, var fileName: String, var bytes: ByteArray)

    val rows = mutableListOf<Row>()
    val validTokens = mutableSetOf<String>()
    val requests = mutableListOf<String>()
    var nextId = 1
    var refreshReply: (() -> HttpResponse)? = null
    var loginOk = Pair("a@example.org", "right-password")
    var failNetwork = false
    var loginCount = 0
    val resetRequests = mutableListOf<String>()
    var mailBroken = false

    private fun json(code: Int, o: JSONObject) = HttpResponse(code, o.toString().toByteArray())

    override fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
        if (failNetwork) throw IOException("no network")
        requests.add("$method ${url.substringAfter("https://pb.test")}")
        val uri = URI(url)
        val path = uri.path
        val query = (uri.rawQuery ?: "").split("&").filter { it.contains("=") }.associate {
            URLDecoder.decode(it.substringBefore("="), "UTF-8") to URLDecoder.decode(it.substringAfter("="), "UTF-8")
        }

        if (path == "/api/collections/users/auth-with-password") {
            val o = JSONObject(String(body!!))
            return if (o.getString("identity") == loginOk.first && o.getString("password") == loginOk.second) {
                val token = "tok-login-${++loginCount}"
                validTokens.add(token)
                json(200, JSONObject().put("token", token).put("record", JSONObject().put("id", "u1").put("email", loginOk.first)))
            } else json(400, JSONObject().put("message", "Failed to authenticate."))
        }
        if (path == "/api/collections/users/request-password-reset") {
            // Like the real server: answers the same for any address, and needs no sign-in.
            if (headers.containsKey("Authorization")) return json(400, JSONObject().put("message", "unexpected sign-in"))
            resetRequests.add(JSONObject(String(body!!)).getString("email"))
            return if (mailBroken) json(400, JSONObject().put("message", "Failed to send the reset email.")) else HttpResponse(204, ByteArray(0))
        }
        if (headers["Authorization"] !in validTokens) return json(401, JSONObject().put("message", "The request requires valid record authorization token."))

        return when {
            path == "/api/collections/users/auth-refresh" -> refreshReply?.invoke() ?: json(
                200, JSONObject().put("token", "tok-refreshed").put("record", JSONObject().put("id", "u1").put("email", "a@example.org")),
            ).also { validTokens.add("tok-refreshed") }

            path.startsWith("/api/collections/users/records/") && method == "PATCH" -> {
                // Like the real server: the current password is required, and a new one must be 8-72 long.
                val o = JSONObject(String(body!!))
                if (o.optString("oldPassword") != loginOk.second) {
                    json(400, JSONObject().put("message", "Failed to update record.").put(
                        "data", JSONObject().put("oldPassword", JSONObject().put("message", "Missing or invalid old password.")),
                    ))
                } else if (o.optString("password").length !in 8..72) {
                    json(400, JSONObject().put("message", "Failed to update record.").put(
                        "data", JSONObject().put("password", JSONObject().put("message", "The length must be between 8 and 72.")),
                    ))
                } else {
                    loginOk = Pair(loginOk.first, o.getString("password"))
                    validTokens.clear() // every session of the account ends when the password changes
                    json(200, JSONObject().put("id", "u1"))
                }
            }

            path.startsWith("/api/collections/users/records/") && method == "DELETE" -> HttpResponse(204, ByteArray(0))

            path == "/api/files/token" -> json(200, JSONObject().put("token", "file-token"))

            path.startsWith("/api/files/") -> {
                if (query["token"] != "file-token") return HttpResponse(404, ByteArray(0))
                val parts = path.removePrefix("/api/files/").split("/")
                val row = rows.firstOrNull { it.id == parts[1] && it.fileName == parts[2] }
                if (row == null) HttpResponse(404, ByteArray(0)) else HttpResponse(200, row.bytes)
            }

            path == "/api/collections/files/records" && method == "GET" -> list(query["filter"])

            path == "/api/collections/files/records" && method == "POST" -> {
                val form = parseMultipart(headers["Content-Type"]!!, body!!)
                val p = form.fields.getValue("path")
                if (rows.any { it.path == p }) json(400, JSONObject().put("message", "Failed to create record."))
                else {
                    val row = Row("rec${nextId++}", p, form.fields["sha"] ?: "", "f_${form.fileName}", form.file)
                    rows.add(row)
                    json(200, JSONObject().put("id", row.id).put("path", p).put("content", row.fileName))
                }
            }

            path.startsWith("/api/collections/files/records/") && method == "PATCH" -> {
                val id = path.substringAfterLast("/")
                val row = rows.firstOrNull { it.id == id } ?: return HttpResponse(404, ByteArray(0))
                val form = parseMultipart(headers["Content-Type"]!!, body!!)
                form.fields["sha"]?.let { row.sha = it }
                if (form.file.isNotEmpty()) row.bytes = form.file
                json(200, JSONObject().put("id", row.id))
            }

            else -> HttpResponse(404, ByteArray(0))
        }
    }

    private fun list(filter: String?): HttpResponse {
        val literals = Regex("'((?:[^'\\\\]|\\\\.)*)'").findAll(filter ?: "").map {
            it.groupValues[1].replace("\\'", "'").replace("\\\\", "\\")
        }.toList()
        val hits = when {
            filter == null || filter.contains("!=") -> rows
            filter.contains(">=") -> rows.filter { it.path >= literals[0] && it.path < literals[1] }
            else -> rows.filter { it.path == literals[0] }
        }.sortedBy { it.path }
        val items = org.json.JSONArray()
        hits.forEach {
            items.put(JSONObject().put("id", it.id).put("collectionId", "cid").put("path", it.path).put("sha", it.sha).put("content", it.fileName))
        }
        return json(200, JSONObject().put("page", 1).put("totalPages", 1).put("totalItems", hits.size).put("items", items))
    }

    class Form(val fields: Map<String, String>, val fileName: String, val file: ByteArray)

    private fun parseMultipart(contentType: String, body: ByteArray): Form {
        val boundary = "--" + contentType.substringAfter("boundary=")
        val text = String(body, Charsets.ISO_8859_1)
        val fields = mutableMapOf<String, String>()
        var fileName = ""
        var file = ByteArray(0)
        for (part in text.split(boundary).drop(1)) {
            if (part.startsWith("--")) break
            val headEnd = part.indexOf("\r\n\r\n")
            val head = part.substring(0, headEnd)
            val content = part.substring(headEnd + 4).removeSuffix("\r\n")
            val name = Regex("name=\"([^\"]*)\"").find(head)!!.groupValues[1]
            if (head.contains("filename=")) {
                fileName = Regex("filename=\"([^\"]*)\"").find(head)!!.groupValues[1]
                file = content.toByteArray(Charsets.ISO_8859_1)
            } else fields[name] = content
        }
        return Form(fields, fileName, file)
    }
}

class PocketBaseTest {
    private val now = 1_800_000_000_000L // fixed "current time" for the session-renewal rules

    private fun jwt(expMs: Long) = "e30." +
        Base64.getUrlEncoder().withoutPadding().encodeToString("""{"exp":${expMs / 1000}}""".toByteArray()) + ".sig"

    private fun session(token: String) = PbSession("https://pb.test", token, "u1", "a@example.org")

    /** A server that knows a long-lived token, and a store signed in with it. */
    private fun store(server: FakeServer, onSession: (PbSession) -> Unit = {}): PocketBaseStore {
        val token = jwt(now + 14L * 24 * 3600 * 1000)
        server.validTokens.add(token)
        return PocketBaseStore(session(token), server, onSession, nowMs = { now })
    }

    @Test
    fun loginReturnsASessionAndWrongPasswordsGetAClearMessage() {
        val server = FakeServer()
        val s = PocketBaseAuth("https://pb.test/", server).login(" a@example.org ", "right-password")
        assertEquals("u1", s.userId)
        assertEquals("https://pb.test", s.baseUrl)
        assertEquals("a@example.org", s.email)
        try {
            PocketBaseAuth("https://pb.test", server).login("a@example.org", "nope")
            fail("expected a failure")
        } catch (e: StoreException) {
            assertEquals("Wrong email or password.", e.message)
        }
    }

    @Test
    fun unreachableServerIsReportedNotCrashed() {
        val server = FakeServer().apply { failNetwork = true }
        try {
            PocketBaseAuth("https://pb.test", server).login("a@example.org", "x")
            fail("expected a failure")
        } catch (e: StoreException) {
            assertEquals(0, e.code)
            assertTrue(e.message!!.contains("not reachable"))
        }
    }

    @Test
    fun putNewCreatesAndNeverOverwrites() {
        val server = FakeServer()
        val store = store(server)
        assertEquals(PutResult.CREATED, store.putNew("rides/2026/a.gpx", "first".toByteArray(), "m"))
        assertEquals(PutResult.EXISTS, store.putNew("rides/2026/a.gpx", "second".toByteArray(), "m"))
        assertEquals("first", String(server.rows.single().bytes))
        assertEquals(GitSha.blob("first".toByteArray()), server.rows.single().sha)
    }

    @Test
    fun getRawReturnsTheContentAndNullForMissingPaths() {
        val server = FakeServer()
        val store = store(server)
        store.putNew("rides/2026/a.gpx", "hello".toByteArray(), "m")
        assertEquals("hello", String(store.getRaw("rides/2026/a.gpx")!!))
        assertNull(store.getRaw("rides/2026/none.gpx"))
    }

    @Test
    fun putOrUpdateReplacesExistingContentOrCreates() {
        val server = FakeServer()
        val store = store(server)
        store.putOrUpdate("settings/plan.json", "v1".toByteArray(), "m") // creates
        store.putOrUpdate("settings/plan.json", "v2".toByteArray(), "m") // updates
        assertEquals(1, server.rows.size)
        assertEquals("v2", String(store.getRaw("settings/plan.json")!!))
        assertEquals(GitSha.blob("v2".toByteArray()), server.rows.single().sha)
    }

    @Test
    fun listReturnsDirectChildrenLikeAFolder() {
        val server = FakeServer()
        val store = store(server)
        store.putNew("rides/2025/old.gpx", "a".toByteArray(), "m")
        store.putNew("rides/2026/a.gpx", "b".toByteArray(), "m")
        store.putNew("rides/2026/a.workout.json", "c".toByteArray(), "m")
        store.putNew("inbox/ride-phone/x.gpx", "d".toByteArray(), "m")
        store.putNew("ridesX/other.gpx", "e".toByteArray(), "m") // must not leak into "rides"

        val years = store.list("rides")
        assertEquals(listOf("2025", "2026"), years.map { it.name })
        assertTrue(years.all { it.type == "dir" })
        assertEquals("rides/2026", years[1].path)

        val files = store.list("rides/2026")
        assertEquals(listOf("a.gpx", "a.workout.json"), files.map { it.name })
        assertTrue(files.all { it.type == "file" })
        assertEquals(GitSha.blob("b".toByteArray()), files[0].sha)
        assertEquals("rides/2026/a.gpx", files[0].path)

        assertEquals(listOf("ride-phone"), store.list("inbox").map { it.name })
        assertTrue(store.list("nothing/here").isEmpty())
        assertEquals(listOf("inbox", "rides", "ridesX"), store.list("").map { it.name })
    }

    @Test
    fun pathsWithQuotesDoNotBreakTheFilter() {
        val server = FakeServer()
        val store = store(server)
        store.putNew("inbox/p/it's mine.gpx", "x".toByteArray(), "m")
        assertEquals("x", String(store.getRaw("inbox/p/it's mine.gpx")!!))
    }

    @Test
    fun aRejectedTokenGivesASignInAgainMessage() {
        val server = FakeServer()
        val store = PocketBaseStore(session(jwt(now + 14L * 24 * 3600 * 1000)), server, nowMs = { now }) // not known to the server
        try {
            store.list("rides")
            fail("expected a failure")
        } catch (e: StoreException) {
            assertEquals(401, e.code)
            assertTrue(e.message!!.contains("Sign in again"))
        }
    }

    @Test
    fun sessionIsRenewedOnlyWhenLessThanAWeekIsLeft() {
        // Plenty left: no refresh request is made.
        val server = FakeServer()
        store(server).list("rides")
        assertTrue(server.requests.none { it.contains("auth-refresh") })

        // Under a week left: refreshed once, the new token is reported and used from then on.
        val soon = jwt(now + 2L * 24 * 3600 * 1000)
        val server2 = FakeServer().apply { validTokens.add(soon) }
        var saved: PbSession? = null
        val store2 = PocketBaseStore(session(soon), server2, { saved = it }, nowMs = { now })
        store2.list("rides")
        store2.list("rides")
        assertEquals(1, server2.requests.count { it.contains("auth-refresh") })
        assertEquals("tok-refreshed", saved?.token)
    }

    @Test
    fun anExpiredSessionThatCannotBeRenewedAsksForSignIn() {
        val old = jwt(now + 1000)
        val server = FakeServer().apply {
            validTokens.add(old)
            refreshReply = { HttpResponse(401, "{}".toByteArray()) }
        }
        try {
            PocketBaseStore(session(old), server, nowMs = { now }).list("rides")
            fail("expected a failure")
        } catch (e: StoreException) {
            assertEquals(401, e.code)
            assertTrue(e.message!!.contains("expired"))
        }
    }

    @Test
    fun noNetworkDuringRenewalDoesNotBlockUse() {
        val soon = jwt(now + 1000)
        val server = FakeServer().apply {
            validTokens.add(soon)
            refreshReply = { throw IOException("offline") }
        }
        // The renewal fails quietly; the request itself still goes ahead with the current token.
        assertTrue(PocketBaseStore(session(soon), server, nowMs = { now }).list("rides").isEmpty())
    }

    @Test
    fun filesOverTheServerLimitAreRefusedBeforeUploading() {
        val server = FakeServer()
        val store = store(server)
        try {
            store.putNew("inbox/p/big.jpg", ByteArray(PocketBaseStore.MAX_FILE_BYTES + 1), "m")
            fail("expected a failure")
        } catch (e: StoreException) {
            assertEquals(413, e.code)
        }
        assertTrue(server.rows.isEmpty())
    }

    @Test
    fun deletingTheAccountCallsTheUserRecord() {
        val server = FakeServer()
        val s = PocketBaseAuth("https://pb.test", server).login("a@example.org", "right-password")
        PocketBaseAuth("https://pb.test", server).deleteAccount(s)
        assertTrue(server.requests.any { it == "DELETE /api/collections/users/records/u1" })
    }

    @Test
    fun changingThePasswordSignsInAgainWithTheNewOne() {
        val server = FakeServer()
        val auth = PocketBaseAuth("https://pb.test", server)
        val old = auth.login("a@example.org", "right-password")
        val fresh = auth.changePassword(old, "right-password", "brand-new-pass")
        assertTrue(fresh.token != old.token)
        assertTrue(old.token !in server.validTokens) // the old session ended with the old password
        assertTrue(fresh.token in server.validTokens)
        assertEquals("a@example.org", fresh.email)
        auth.login("a@example.org", "brand-new-pass") // the new password works
        try {
            auth.login("a@example.org", "right-password") // the old one no longer does
            fail("expected a failure")
        } catch (e: StoreException) {
            assertEquals("Wrong email or password.", e.message)
        }
    }

    @Test
    fun aWrongCurrentPasswordIsExplainedAndNothingChanges() {
        val server = FakeServer()
        val auth = PocketBaseAuth("https://pb.test", server)
        val s = auth.login("a@example.org", "right-password")
        try {
            auth.changePassword(s, "guess", "brand-new-pass")
            fail("expected a failure")
        } catch (e: StoreException) {
            assertEquals("Your current password is wrong.", e.message)
        }
        auth.login("a@example.org", "right-password") // still the old password
        assertTrue(s.token in server.validTokens) // and the session was not ended
    }

    @Test
    fun aNewPasswordTheServerRefusesIsExplained() {
        val auth = PocketBaseAuth("https://pb.test", FakeServer())
        val s = auth.login("a@example.org", "right-password")
        try {
            auth.changePassword(s, "right-password", "short")
            fail("expected a failure")
        } catch (e: StoreException) {
            assertTrue(e.message!!, e.message!!.contains("did not accept the new password"))
            assertTrue(e.message!!.contains("between 8 and 72"))
        }
    }

    @Test
    fun forgotPasswordAsksForAnEmailWithoutSigningIn() {
        val server = FakeServer()
        PocketBaseAuth("https://pb.test", server).requestPasswordReset("  friend@example.org ")
        assertEquals(listOf("friend@example.org"), server.resetRequests)
        // An address with no account gets exactly the same answer, so nothing is revealed.
        PocketBaseAuth("https://pb.test", server).requestPasswordReset("nobody@example.org")
        assertEquals(2, server.resetRequests.size)
    }

    @Test
    fun forgotPasswordExplainsWhenTheServerCannotSendMail() {
        val server = FakeServer().apply { mailBroken = true }
        try {
            PocketBaseAuth("https://pb.test", server).requestPasswordReset("friend@example.org")
            fail("expected a failure")
        } catch (e: StoreException) {
            assertTrue(e.message!!, e.message!!.contains("set up email"))
        }
    }

    @Test
    fun forgotPasswordChecksTheAddressBeforeAskingTheServer() {
        val server = FakeServer()
        try {
            PocketBaseAuth("https://pb.test", server).requestPasswordReset("not-an-email")
            fail("expected a failure")
        } catch (e: StoreException) {
            assertTrue(e.message!!.contains("email address"))
        }
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun forgotPasswordReportsAnUnreachableServer() {
        val server = FakeServer().apply { failNetwork = true }
        try {
            PocketBaseAuth("https://pb.test", server).requestPasswordReset("friend@example.org")
            fail("expected a failure")
        } catch (e: StoreException) {
            assertTrue(e.message!!.contains("not reachable"))
        }
    }

    @Test
    fun passwordRulesCatchProblemsBeforeAnythingIsSent() {
        assertNull(PasswordRules.check("old-pass", "new-password", "new-password"))
        assertTrue(PasswordRules.check("", "new-password", "new-password")!!.contains("current password"))
        assertTrue(PasswordRules.check("old-pass", "short", "short")!!.contains("at least 8"))
        assertTrue(PasswordRules.check("old-pass", "x".repeat(73), "x".repeat(73))!!.contains("at most 72"))
        assertTrue(PasswordRules.check("old-pass", "new-password", "different1")!!.contains("do not match"))
        assertTrue(PasswordRules.check("same-password", "same-password", "same-password")!!.contains("different"))
    }

    @Test
    fun checkAccessSucceedsWhenSignedInAndFailsOtherwise() {
        store(FakeServer()).checkAccess()
        try {
            PocketBaseStore(session("unknown"), FakeServer(), nowMs = { now }).checkAccess()
            fail("expected a failure")
        } catch (e: StoreException) {
            assertEquals(401, e.code)
        }
    }

    @Test
    fun multipartBodyHasFieldsFileAndTerminator() {
        val mp = Multipart.build(mapOf("path" to "a/b"), "content", "x\"y.gpx", "DATA".toByteArray(), boundary = "BND")
        val text = String(mp.bytes)
        assertEquals("multipart/form-data; boundary=BND", mp.contentType)
        assertTrue(text.contains("--BND\r\nContent-Disposition: form-data; name=\"path\"\r\n\r\na/b\r\n"))
        assertTrue(text.contains("name=\"content\"; filename=\"x%22y.gpx\"")) // quote cannot end the header early
        assertTrue(text.contains("\r\n\r\nDATA\r\n"))
        assertTrue(text.endsWith("--BND--\r\n"))
    }

    @Test
    fun sessionExpiryIsReadFromTheToken() {
        assertEquals(1_900_000_000_000L, JwtExpiry.expiresAtMs(jwt(1_900_000_000_000L)))
        assertNull(JwtExpiry.expiresAtMs("not-a-jwt"))
        assertNull(JwtExpiry.expiresAtMs("a.b.c"))
    }

    @Test
    fun aSignInStartsFromTheChosenServerElseTheOneBuiltIn() {
        assertEquals("https://mine.example.org", ServerDefaults.effective("https://mine.example.org", "https://built-in.example.org"))
        assertEquals("https://built-in.example.org", ServerDefaults.effective("", "https://built-in.example.org"))
        assertEquals("https://built-in.example.org", ServerDefaults.effective("   ", " https://built-in.example.org "))
        assertEquals("", ServerDefaults.effective("", "")) // a build without a server: the field stays empty
    }

    @Test
    fun serverAddressMustBeHttps() {
        assertEquals("https://fit.example.org", ServerAddress.normalize(" https://fit.example.org/ "))
        assertNull(ServerAddress.normalize("http://fit.example.org"))
        assertNull(ServerAddress.normalize("fit.example.org"))
        assertNull(ServerAddress.normalize("https://"))
        assertNull(ServerAddress.normalize("https://a b"))
    }
}
