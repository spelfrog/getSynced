package dev.spelfrog.getsynced.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun versionComparison() {
        assertTrue(UpdateChecker.isNewer("0.1.2", "0.1.1"))
        assertTrue(UpdateChecker.isNewer("v0.2", "0.1.9"))
        assertTrue(UpdateChecker.isNewer("1.0.0", "0.9.9"))
        assertFalse(UpdateChecker.isNewer("0.1.1", "0.1.1"))
        assertFalse(UpdateChecker.isNewer("0.1.0", "0.1.1"))
        assertFalse(UpdateChecker.isNewer("0.1.1", "0.1.1-debug"))
    }

    @Test
    fun parsesReleaseWithStableApk() {
        val json = Json.parseToJsonElement(
            """{"tag_name":"v0.1.2","html_url":"https://github.com/spelfrog/getSynced/releases/tag/v0.1.2","draft":false,
                "body":"## What's Changed\n\n* Fix trailing spaces in paths by @spelfrog\n",
                "assets":[{"name":"getSynced-v0.1.2.apk","browser_download_url":"https://x/a.apk"},
                          {"name":"getSynced.apk","browser_download_url":"https://x/getSynced.apk"}]}""",
        ) as JsonObject
        val r = UpdateChecker.parse(json)
        assertEquals("0.1.2", r.version)
        assertEquals("https://x/getSynced.apk", r.downloadUrl)
        assertEquals("Fix trailing spaces in paths by @spelfrog", r.notes)
    }

    @Test
    fun fallsBackToReleasePageWithoutStableApk() {
        val json = Json.parseToJsonElement("""{"tag_name":"v0.1.2","html_url":"https://rel","assets":[]}""") as JsonObject
        assertEquals("https://rel", UpdateChecker.parse(json).downloadUrl)
    }

    @Test
    fun fetchUsesLatestEndpoint() {
        var url = ""
        val r = UpdateChecker { _, u, _, _ -> url = u; """{"tag_name":"v9.9.9","html_url":"h","assets":[]}""" }.fetchLatest()
        assertEquals(UpdateChecker.LATEST_URL, url)
        assertEquals("9.9.9", r.version)
    }
}
