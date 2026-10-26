package dev.spelfrog.getsynced.update

import dev.spelfrog.getsynced.auth.GitHubDeviceFlow
import dev.spelfrog.getsynced.auth.HttpUrlConnectionCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException

data class ReleaseInfo(
    /** "0.1.2" (tag without the leading v). */
    val version: String,
    val tag: String,
    val htmlUrl: String,
    /** Direct link to getSynced.apk when the release has one, else the release page. */
    val downloadUrl: String,
    /** First paragraph of the release notes, may be empty. */
    val notes: String,
)

/**
 * Looks up the newest GitHub release. Only called while the app is in the foreground (see StatusViewModel);
 * never from the worker or a background job.
 */
class UpdateChecker(private val http: GitHubDeviceFlow.HttpCall = HttpUrlConnectionCall) {

    fun fetchLatest(): ReleaseInfo {
        val body = http.call("GET", LATEST_URL, emptyMap(), null)
        val json = try {
            Json.parseToJsonElement(body) as JsonObject
        } catch (e: Exception) {
            throw IOException("Unexpected answer from GitHub: ${body.take(120)}")
        }
        return parse(json)
    }

    companion object {
        const val LATEST_URL = "https://api.github.com/repos/spelfrog/getSynced/releases/latest"
        const val STABLE_APK_NAME = "getSynced.apk"

        fun parse(json: JsonObject): ReleaseInfo {
            val tag = json.str("tag_name") ?: throw IOException("Release has no tag_name")
            if (json["draft"]?.jsonPrimitive?.booleanOrNull == true) throw IOException("Latest release is a draft")
            val html = json.str("html_url") ?: "https://github.com/spelfrog/getSynced/releases"
            val apk = (json["assets"] as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                ?.firstOrNull { it.str("name") == STABLE_APK_NAME }
                ?.str("browser_download_url")
            val notes = json.str("body").orEmpty()
                .lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }.orEmpty()
                .removePrefix("* ").removePrefix("- ")
            return ReleaseInfo(
                version = tag.removePrefix("v").removePrefix("V"),
                tag = tag,
                htmlUrl = html,
                downloadUrl = apk ?: html,
                notes = notes,
            )
        }

        /** True when [latest] is a higher version than [current]; "0.1.2" > "0.1.1", "0.2" > "0.1.9". */
        fun isNewer(latest: String, current: String): Boolean {
            val a = parts(latest)
            val b = parts(current)
            val n = maxOf(a.size, b.size)
            for (i in 0 until n) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }

        private fun parts(v: String): List<Int> =
            v.trim().removePrefix("v").substringBefore('-').substringBefore('+')
                .split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }

        private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    }
}
