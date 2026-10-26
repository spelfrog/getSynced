package dev.spelfrog.getsynced.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class GitHubReposTest {
    private fun repoJson(name: String, pushed: String, priv: Boolean = false) =
        """{"full_name":"$name","clone_url":"https://github.com/$name.git","default_branch":"main","private":$priv,"pushed_at":"$pushed","description":null}"""

    @Test
    fun paginatesAndSortsByPush() {
        val page1 = (1..100).joinToString(",", "[", "]") { repoJson("me/r$it", "2026-01-%02dT00:00:00Z".format(it % 28 + 1)) }
        val page2 = "[" + repoJson("me/new", "2026-10-01T00:00:00Z", priv = true) + "]"
        val urls = mutableListOf<String>()
        val repos = GitHubRepos { _, url, _, bearer ->
            assertEquals("ghu_x", bearer)
            urls += url
            if (url.endsWith("page=1")) page1 else page2
        }.list("ghu_x")
        assertEquals(101, repos.size)
        assertEquals("me/new", repos.first().fullName)
        assertEquals(true, repos.first().isPrivate)
        assertEquals("", repos.first().description)
        assertEquals(2, urls.size)
    }

    @Test
    fun stopsAfterShortPage() {
        var calls = 0
        val repos = GitHubRepos { _, _, _, _ -> calls++; "[" + repoJson("me/a", "2026-01-01T00:00:00Z") + "]" }.list("t")
        assertEquals(1, repos.size)
        assertEquals(1, calls)
    }

    @Test
    fun nonArrayIsAnError() {
        assertThrows(IOException::class.java) {
            GitHubRepos { _, _, _, _ -> """{"message":"Bad credentials"}""" }.list("t")
        }
    }
}
