package dev.spelfrog.getsynced.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncToastsTest {
    /** Mirrors res/values/strings_toast.xml. */
    private val texts = object : SyncToasts.Texts {
        override fun nothingToSync() = "Synced · nothing to sync"
        override fun counts(pushed: Int, pulled: Int) = "Synced · pushed $pushed, pulled $pulled"
        override fun failed(headline: String) = "Sync failed: $headline"
        override fun conflict(files: Int?) =
            if (files == null) "Merge conflict · open getSynced"
            else "Conflict in $files ${if (files == 1) "file" else "files"} · open getSynced"
    }

    private fun text(outcome: SyncOutcome, summary: String, conflictCount: Int? = null) =
        SyncToasts.buildResultText(outcome, summary, conflictCount, texts)

    @Test
    fun automaticTriggers() {
        assertTrue(SyncToasts.isAutomatic("open"))
        assertTrue(SyncToasts.isAutomatic("close"))
        assertTrue(SyncToasts.isAutomatic("intent"))
        assertFalse(SyncToasts.isAutomatic("manual"))
        assertFalse(SyncToasts.isAutomatic("clone"))
        assertFalse(SyncToasts.isAutomatic("unknown"))
    }

    @Test
    fun okWithCounts() {
        assertEquals("Synced · pushed 1, pulled 2", text(SyncOutcome.OK, "pushed 1, pulled 2, 0.9 s"))
    }

    @Test
    fun okPushOnly() {
        assertEquals("Synced · pushed 3, pulled 0", text(SyncOutcome.OK, "pushed 3, pulled 0, 1.2 s"))
    }

    @Test
    fun okNothing() {
        assertEquals("Synced · nothing to sync", text(SyncOutcome.OK, "pushed 0, pulled 0, 0.4 s"))
    }

    @Test
    fun okUnparseableSummaryIsNothing() {
        assertEquals("Synced · nothing to sync", text(SyncOutcome.OK, "up to date"))
    }

    @Test
    fun failedUsesFirstLine() {
        assertEquals(
            "Sync failed: Push failed: rejected",
            text(SyncOutcome.FAILED, "Push failed: rejected\nstack trace line"),
        )
    }

    @Test
    fun failedSkipsLeadingBlankLines() {
        assertEquals("Sync failed: No network", text(SyncOutcome.FAILED, "\n  No network  \nmore"))
    }

    @Test
    fun failedHeadlineIsTruncated() {
        val long = "x".repeat(200)
        val result = text(SyncOutcome.FAILED, long)
        val headline = result.removePrefix("Sync failed: ")
        assertEquals(SyncToasts.MAX_HEADLINE, headline.length)
        assertTrue(headline.endsWith("…"))
    }

    @Test
    fun failedHeadlineAtLimitIsKept() {
        val exact = "y".repeat(SyncToasts.MAX_HEADLINE)
        assertEquals("Sync failed: $exact", text(SyncOutcome.FAILED, exact))
    }

    @Test
    fun runningTreatedAsFailed() {
        assertEquals("Sync failed: Interrupted", text(SyncOutcome.RUNNING, "Interrupted"))
    }

    @Test
    fun conflictCountFromRun() {
        assertEquals(
            "Conflict in 2 files · open getSynced",
            text(SyncOutcome.CONFLICT, "Merge conflict in 5 files: a, b", conflictCount = 2),
        )
    }

    @Test
    fun conflictCountFromSummary() {
        assertEquals(
            "Conflict in 2 files · open getSynced",
            text(SyncOutcome.CONFLICT, "Merge conflict in 2 files: a.md, b.md"),
        )
    }

    @Test
    fun conflictSingleFile() {
        assertEquals("Conflict in 1 file · open getSynced", text(SyncOutcome.CONFLICT, "Merge conflict in 1 file: a.md"))
    }

    @Test
    fun conflictUnknownCount() {
        assertEquals("Merge conflict · open getSynced", text(SyncOutcome.CONFLICT, "something odd"))
    }
}
