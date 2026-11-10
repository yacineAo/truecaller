package com.saascom.caller.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ApiClientTest {
    private lateinit var server: MockWebServer
    private var token: String? = "7|secret"
    private var unauthorizedCalls = 0

    private val api by lazy {
        ApiClient(
            baseUrl = { server.url("/").toString() },
            token = { token },
            appVersion = 3,
            onUnauthorized = { unauthorizedCalls++ },
        )
    }

    @Before
    fun start() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun stop() {
        server.shutdown()
    }

    @Test
    fun loginSendsTheContractBodyWithoutToken() = runTest {
        server.enqueue(MockResponse().setBody("""{"token":"9|new","user":{"id":3,"name":"كريم","role":"agent"}}"""))

        val result = api.login("karim@test.dz", "pw", "Samsung A54")

        val request = server.takeRequest()
        assertEquals("/api/mobile/login", request.path)
        assertEquals("POST", request.method)
        assertNull(request.getHeader("Authorization"))
        assertEquals("application/json", request.getHeader("Accept"))
        assertEquals("""{"email":"karim@test.dz","password":"pw","device_name":"Samsung A54"}""", request.body.readUtf8())
        assertEquals("9|new", result.token)
        assertEquals("كريم", result.user.name)
    }

    @Test
    fun wrongPasswordGivesTheCrmMessageAndDoesNotLogOut() = runTest {
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"message":"بيانات الدخول غير صحيحة","errors":{"email":["…"]}}"""))

        try {
            api.login("karim@test.dz", "bad", "Phone")
            fail("expected an error")
        } catch (e: ApiException.Http) {
            assertEquals(422, e.code)
            assertEquals("بيانات الدخول غير صحيحة", e.message)
        }
        assertEquals(0, unauthorizedCalls)
    }

    @Test
    fun lookupSendsHeadersAndEncodesThePhone() = runTest {
        server.enqueue(MockResponse().setBody("""{"found":false,"phone":"021234567"}"""))

        val response = api.lookup("+21321234567")

        val request = server.takeRequest()
        assertEquals("/api/mobile/lookup?phone=%2B21321234567", request.path)
        assertEquals("Bearer 7|secret", request.getHeader("Authorization"))
        assertEquals("3", request.getHeader("X-App-Version"))
        assertFalse(response.found)
    }

    @Test
    fun a401LogsTheUserOut() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"الحساب معطّل"}"""))

        try {
            api.lookup("0550123456")
            fail("expected an error")
        } catch (e: ApiException.Http) {
            assertEquals(401, e.code)
        }
        assertEquals(1, unauthorizedCalls)
    }

    @Test
    fun directoryUsesTheEtag() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("ETag", "\"abc\"")
                .setBody("""{"statuses":[],"entries":[{"lead_id":1,"phone":"0550123456","stage":"new","status":"جديد"}]}"""),
        )
        server.enqueue(MockResponse().setResponseCode(304))

        val first = api.directory(etag = null)
        assertTrue(first is DirectoryResult.Updated)
        assertEquals("\"abc\"", (first as DirectoryResult.Updated).etag)
        assertEquals(1, first.body.entries.size)
        assertNull(server.takeRequest().getHeader("If-None-Match"))

        assertEquals(DirectoryResult.NotModified, api.directory(etag = "\"abc\""))
        assertEquals("\"abc\"", server.takeRequest().getHeader("If-None-Match"))
    }

    @Test
    fun logCallPostsTheBodyToTheLeadPath() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"call_id":5521,"lead":{"id":12,"status":{"id":2,"label":"مهتم","stage":"interested"}}}"""))

        val response = api.logCall(12, logCallBody("available", "تم", null, ReminderChoice.InBusinessDays(3)))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/mobile/leads/12/calls", request.path)
        assertEquals("""{"result":"available","notes":"تم","reminder_in_business_days":3}""", request.body.readUtf8())
        assertEquals(5521, response.callId)
        assertEquals("interested", response.lead?.status?.stage)
    }

    @Test
    fun createLeadTurns409IntoTheExistingLead() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"lead_id":2600}"""))
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"message":"الرقم موجود مسبقاً","lead_id":1234}"""))

        assertEquals(CreateLeadResult.Created(2600), api.createLead(CreateLeadRequest("0661987654", notes = "يريد تجربة")))
        assertEquals(CreateLeadResult.Exists(1234, "الرقم موجود مسبقاً"), api.createLead(CreateLeadRequest("0550123456", notes = ".")))
        assertEquals("""{"phone":"0661987654","notes":"يريد تجربة"}""", server.takeRequest().body.readUtf8())
    }

    @Test
    fun createLeadValidationErrorStaysAnError() = runTest {
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"message":"رقم الهاتف غير صالح","errors":{"phone":["…"]}}"""))

        try {
            api.createLead(CreateLeadRequest("abc", notes = "."))
            fail("expected an error")
        } catch (e: ApiException.Http) {
            assertEquals(422, e.code)
            assertEquals("رقم الهاتف غير صالح", e.message)
        }
    }

    @Test
    fun createRequestReturnsItsId() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"request_id":31}"""))

        assertEquals(31L, api.createRequest(12, CreateRequestRequest("مشكل في الطباعة", "لا تُطبع", "urgent")))
        val request = server.takeRequest()
        assertEquals("/api/mobile/leads/12/requests", request.path)
        assertEquals("""{"title":"مشكل في الطباعة","content":"لا تُطبع","priority":"urgent"}""", request.body.readUtf8())
    }

    @Test
    fun noAnswerIsANetworkError() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        try {
            api.lookup("0550123456")
            fail("expected an error")
        } catch (e: ApiException.Network) {
            // expected: the card falls back to the local list
        }
        assertEquals(0, unauthorizedCalls)
    }
}
