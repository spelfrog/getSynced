package dev.spelfrog.getsynced.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException

data class GitHubRepo(
    val fullName: String,
    val cloneUrl: String,
    val defaultBranch: String,
    val isPrivate: Boolean,
    /** ISO-8601 from GitHub, used only for ordering. */
    val pushedAt: String,
    val description: String,
)

/** Lists the repositories the signed-in token can see (owner, collaborator, org member), newest push first. */
class GitHubRepos(private val http: GitHubDeviceFlow.HttpCall = HttpUrlConnectionCall) {

    fun list(token: String, maxPages: Int = MAX_PAGES): List<GitHubRepo> {
        val all = mutableListOf<GitHubRepo>()
        for (page in 1..maxPages) {
            val url = "$REPOS_URL?per_page=$PER_PAGE&sort=pushed&direction=desc&page=$page"
            val body = http.call("GET", url, emptyMap(), token)
            val arr = try {
                Json.parseToJsonElement(body) as JsonArray
            } catch (e: Exception) {
                throw IOException("GitHub returned an unexpected answer for the repository list: ${body.take(200)}")
            }
            arr.mapNotNullTo(all) { (it as? JsonObject)?.toRepo() }
            if (arr.size < PER_PAGE) break
        }
        return all.sortedByDescending { it.pushedAt }
    }

    private fun JsonObject.toRepo(): GitHubRepo? {
        val fullName = str("full_name") ?: return null
        val cloneUrl = str("clone_url") ?: return null
        return GitHubRepo(
            fullName = fullName,
            cloneUrl = cloneUrl,
            defaultBranch = str("default_branch") ?: "main",
            isPrivate = this["private"]?.jsonPrimitive?.booleanOrNull ?: false,
            pushedAt = str("pushed_at") ?: "",
            description = str("description") ?: "",
        )
    }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    companion object {
        const val REPOS_URL = "https://api.github.com/user/repos"
        const val PER_PAGE = 100
        const val MAX_PAGES = 5
    }
}
