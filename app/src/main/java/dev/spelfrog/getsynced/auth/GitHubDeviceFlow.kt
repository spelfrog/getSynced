package dev.spelfrog.getsynced.auth

import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * GitHub OAuth Device Flow for a GitHub App (or OAuth App). Needs only the public Client ID:
 * no secret is stored on the phone. Pure JVM so it is unit-tested with a fake [HttpCall].
 *
 * Flow: [requestCode] -> show user code -> [waitForToken] (polls) -> [fetchLogin].
 */
class GitHubDeviceFlow(
    private val clientId: String,
    private val http: HttpCall = HttpUrlConnectionCall,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    /** One HTTP call; returns the body. Throws IOException on network errors or non-2xx status. */
    fun interface HttpCall {
        fun call(method: String, url: String, form: Map<String, String>, bearer: String?): String
    }

    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val expiresInSec: Int,
        val intervalSec: Int,
    )

    data class Token(
        val accessToken: String,
        /** Present only when the GitHub App has "user-to-server token expiration" enabled. */
        val refreshToken: String?,
        val expiresInSec: Int?,
    )

    data class User(val login: String, val id: Long, val name: String?, val email: String?) {
        /** Commit author name: the profile name, else the login. */
        val authorName: String get() = name?.takeIf { it.isNotBlank() } ?: login
        /** Commit author email: the public profile email, else GitHub's private noreply address. */
        val authorEmail: String get() = email?.takeIf { it.isNotBlank() } ?: "$id+$login@users.noreply.github.com"
    }

    class DeviceFlowException(message: String) : IOException(message)

    suspend fun requestCode(): DeviceCode {
        require(clientId.isNotBlank()) { "GitHub App Client ID is missing" }
        // scope is needed by OAuth Apps (Ov23li... client ids); GitHub Apps (Iv23li.../Iv1.) ignore it.
        val body = withRetry { http.call("POST", DEVICE_CODE_URL, mapOf("client_id" to clientId, "scope" to SCOPE), null) }
        val json = parse(body)
        json.error()?.let { throw DeviceFlowException(it) }
        return DeviceCode(
            deviceCode = json.str("device_code") ?: throw DeviceFlowException("GitHub response had no device_code"),
            userCode = json.str("user_code") ?: throw DeviceFlowException("GitHub response had no user_code"),
            verificationUri = json.str("verification_uri") ?: "https://github.com/login/device",
            expiresInSec = json.int("expires_in") ?: 900,
            intervalSec = json.int("interval") ?: 5,
        )
    }

    /**
     * Polls GitHub until the user approved (returns the token), denied, or the code expired (throws).
     * Honors the minimum interval and GitHub's slow_down responses.
     */
    suspend fun waitForToken(code: DeviceCode): Token {
        var interval = code.intervalSec.coerceAtLeast(1).toLong()
        val form = mapOf(
            "client_id" to clientId,
            "device_code" to code.deviceCode,
            "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
        )
        var networkFailures = 0
        while (true) {
            sleep(interval * 1000)
            val body = try {
                http.call("POST", ACCESS_TOKEN_URL, form, null).also { networkFailures = 0 }
            } catch (e: DeviceFlowException) {
                throw e
            } catch (e: IOException) {
                // Transient network errors ("Software caused connection abort", timeouts) must not throw away
                // a code the user may already have entered. Keep polling for a while.
                if (++networkFailures >= MAX_POLL_NETWORK_FAILURES) throw DeviceFlowException("Network error while waiting for GitHub: ${e.message}")
                continue
            }
            val json = parse(body)
            json.str("access_token")?.let { token ->
                return Token(token, json.str("refresh_token"), json.int("expires_in"))
            }
            when (val err = json.str("error")) {
                "authorization_pending" -> Unit
                "slow_down" -> interval = (json.int("interval")?.toLong() ?: (interval + 5)).coerceAtLeast(interval + 5)
                "expired_token" -> throw DeviceFlowException("The code expired before it was entered on GitHub. Start again.")
                "access_denied" -> throw DeviceFlowException("The request was denied on GitHub.")
                null -> throw DeviceFlowException("Unexpected GitHub response: ${json.toString().take(200)}")
                else -> throw DeviceFlowException(json.str("error_description") ?: err)
            }
        }
    }

    /** The GitHub user that owns [token]. */
    suspend fun fetchUser(token: String): User {
        val json = parse(withRetry { http.call("GET", USER_URL, emptyMap(), token) })
        return User(
            login = json.str("login") ?: throw DeviceFlowException("GitHub /user response had no login"),
            id = json["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
            name = json.str("name"),
            email = json.str("email"),
        )
    }

    /** Retries transient IOExceptions with a short backoff (1 s, 2 s). DeviceFlowExceptions are final. */
    private suspend fun <T> withRetry(attempts: Int = 3, block: () -> T): T {
        var last: IOException? = null
        repeat(attempts) { i ->
            try {
                return block()
            } catch (e: DeviceFlowException) {
                throw e
            } catch (e: IOException) {
                last = e
                if (i < attempts - 1) sleep(1000L * (i + 1))
            }
        }
        throw last ?: IOException("request failed")
    }

    private fun parse(body: String): JsonObject = try {
        Json.parseToJsonElement(body) as JsonObject
    } catch (e: Exception) {
        throw DeviceFlowException("GitHub returned something that is not JSON: ${body.take(200)}")
    }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
    private fun JsonObject.error(): String? = str("error")?.let { str("error_description") ?: it }

    companion object {
        const val DEVICE_CODE_URL = "https://github.com/login/device/code"
        const val ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token"
        const val USER_URL = "https://api.github.com/user"
        const val SCOPE = "repo"
        /** Consecutive failed poll requests tolerated before giving up (at 5 s each: about a minute). */
        const val MAX_POLL_NETWORK_FAILURES = 12

        /** Default Client ID (public, not a secret): Raphael's OAuth App. Users can paste their own. */
        const val DEFAULT_CLIENT_ID = "Ov23liJQSVfKIdFU53Rs"

        /** Username to use for git over HTTPS with a GitHub App user token. */
        const val GIT_USERNAME = "x-access-token"
    }
}

/** Default transport: HttpURLConnection, JSON accept header, 20 s timeouts. */
object HttpUrlConnectionCall : GitHubDeviceFlow.HttpCall {
    override fun call(method: String, url: String, form: Map<String, String>, bearer: String?): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 20_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "getSynced")
            if (bearer != null) conn.setRequestProperty("Authorization", "Bearer $bearer")
            if (method == "POST") {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                val encoded = form.entries.joinToString("&") { (k, v) ->
                    URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
                }
                conn.outputStream.use { it.write(encoded.toByteArray()) }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw IOException("GitHub answered HTTP $status: ${body.take(300)}")
            return body
        } finally {
            conn.disconnect()
        }
    }
}
