package dev.spelfrog.getsynced.auth

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class GitHubDeviceFlowTest {

    private class FakeHttp(private val responses: ArrayDeque<String>) : GitHubDeviceFlow.HttpCall {
        val calls = mutableListOf<Triple<String, String, Map<String, String>>>()
        var lastBearer: String? = null
        override fun call(method: String, url: String, form: Map<String, String>, bearer: String?): String {
            calls += Triple(method, url, form)
            lastBearer = bearer
            val r = responses.removeFirst()
            if (r.startsWith("IOERR")) throw java.io.IOException("Software caused connection abort")
            return r
        }
    }

    private fun flow(http: FakeHttp, sleeps: MutableList<Long>) =
        GitHubDeviceFlow("Iv1.abc", http) { sleeps += it }

    @Test
    fun requestCodeParsesResponse() = runBlocking {
        val http = FakeHttp(ArrayDeque(listOf(
            """{"device_code":"dc","user_code":"ABCD-1234","verification_uri":"https://github.com/login/device","expires_in":899,"interval":5}""",
        )))
        val code = flow(http, mutableListOf()).requestCode()
        assertEquals("dc", code.deviceCode)
        assertEquals("ABCD-1234", code.userCode)
        assertEquals(5, code.intervalSec)
        assertEquals("POST", http.calls[0].first)
        assertEquals(GitHubDeviceFlow.DEVICE_CODE_URL, http.calls[0].second)
        assertEquals("Iv1.abc", http.calls[0].third["client_id"])
        assertEquals("repo", http.calls[0].third["scope"])
    }

    @Test
    fun requestCodeRetriesTransientNetworkErrors() = runBlocking {
        val http = FakeHttp(ArrayDeque(listOf(
            "IOERR",
            """{"device_code":"dc","user_code":"ABCD-1234","verification_uri":"v","expires_in":899,"interval":5}""",
        )))
        val sleeps = mutableListOf<Long>()
        val code = flow(http, sleeps).requestCode()
        assertEquals("dc", code.deviceCode)
        assertEquals(listOf(1000L), sleeps)
    }

    @Test
    fun pollingSurvivesNetworkErrors() = runBlocking {
        val http = FakeHttp(ArrayDeque(listOf(
            """{"error":"authorization_pending"}""",
            "IOERR",
            "IOERR",
            """{"access_token":"ghu_x"}""",
        )))
        val token = flow(http, mutableListOf()).waitForToken(GitHubDeviceFlow.DeviceCode("dc", "u", "v", 900, 5))
        assertEquals("ghu_x", token.accessToken)
        assertEquals(4, http.calls.size)
    }

    @Test
    fun pollingGivesUpAfterManyNetworkErrors() {
        val http = FakeHttp(ArrayDeque(List(GitHubDeviceFlow.MAX_POLL_NETWORK_FAILURES) { "IOERR" }))
        val e = assertThrows(GitHubDeviceFlow.DeviceFlowException::class.java) {
            runBlocking { flow(http, mutableListOf()).waitForToken(GitHubDeviceFlow.DeviceCode("dc", "u", "v", 900, 1)) }
        }
        assertEquals(true, e.message!!.startsWith("Network error"))
    }

    @Test
    fun pollingHonorsPendingAndSlowDown() = runBlocking {
        val http = FakeHttp(ArrayDeque(listOf(
            """{"error":"authorization_pending"}""",
            """{"error":"slow_down","interval":10}""",
            """{"error":"authorization_pending"}""",
            """{"access_token":"ghu_x","token_type":"bearer","scope":""}""",
        )))
        val sleeps = mutableListOf<Long>()
        val token = flow(http, sleeps).waitForToken(GitHubDeviceFlow.DeviceCode("dc", "u", "v", 900, 5))
        assertEquals("ghu_x", token.accessToken)
        assertNull(token.refreshToken)
        assertEquals(listOf(5000L, 5000L, 10000L, 10000L), sleeps)
        assertEquals("urn:ietf:params:oauth:grant-type:device_code", http.calls.last().third["grant_type"])
    }

    @Test
    fun expiringTokenKeepsRefreshData() = runBlocking {
        val http = FakeHttp(ArrayDeque(listOf(
            """{"access_token":"ghu_x","expires_in":28800,"refresh_token":"ghr_y","refresh_token_expires_in":15811200}""",
        )))
        val token = flow(http, mutableListOf()).waitForToken(GitHubDeviceFlow.DeviceCode("dc", "u", "v", 900, 1))
        assertEquals("ghr_y", token.refreshToken)
        assertEquals(28800, token.expiresInSec)
    }

    @Test
    fun deniedAndExpiredThrowReadableErrors() {
        val denied = FakeHttp(ArrayDeque(listOf("""{"error":"access_denied"}""")))
        val e1 = assertThrows(GitHubDeviceFlow.DeviceFlowException::class.java) {
            runBlocking { flow(denied, mutableListOf()).waitForToken(GitHubDeviceFlow.DeviceCode("dc", "u", "v", 900, 1)) }
        }
        assertEquals("The request was denied on GitHub.", e1.message)
        val expired = FakeHttp(ArrayDeque(listOf("""{"error":"expired_token"}""")))
        assertThrows(GitHubDeviceFlow.DeviceFlowException::class.java) {
            runBlocking { flow(expired, mutableListOf()).waitForToken(GitHubDeviceFlow.DeviceCode("dc", "u", "v", 900, 1)) }
        }
    }

    @Test
    fun fetchUserSendsBearerAndDerivesAuthor() = runBlocking {
        val http = FakeHttp(ArrayDeque(listOf("""{"login":"octocat","id":583231,"name":null,"email":null}""")))
        val user = flow(http, mutableListOf()).fetchUser("ghu_x")
        assertEquals("octocat", user.login)
        assertEquals("octocat", user.authorName)
        assertEquals("583231+octocat@users.noreply.github.com", user.authorEmail)
        assertEquals("ghu_x", http.lastBearer)
        assertEquals("GET", http.calls[0].first)

        val http2 = FakeHttp(ArrayDeque(listOf("""{"login":"octocat","id":1,"name":"The Octocat","email":"octo@example.com"}""")))
        val user2 = flow(http2, mutableListOf()).fetchUser("t")
        assertEquals("The Octocat", user2.authorName)
        assertEquals("octo@example.com", user2.authorEmail)
    }

    @Test
    fun nonJsonBodyIsReported() {
        val http = FakeHttp(ArrayDeque(listOf("<html>oops</html>")))
        assertThrows(GitHubDeviceFlow.DeviceFlowException::class.java) {
            runBlocking { flow(http, mutableListOf()).requestCode() }
        }
    }
}
