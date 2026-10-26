package dev.spelfrog.getsynced.sync

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import dev.spelfrog.getsynced.R
import dev.spelfrog.getsynced.settings.AppSettings

/**
 * Short toasts for syncs the user did not start from the app (watched app opened/closed, broadcast intent).
 * Manual syncs get none: the status screen already shows them.
 */
object SyncToasts {
    private val AUTOMATIC = setOf("open", "close", "intent")

    /** Max length of the error headline in a "Sync failed: …" toast. */
    const val MAX_HEADLINE = 80

    /** The user-facing texts, so [buildResultText] stays free of Android. */
    interface Texts {
        fun nothingToSync(): String
        fun counts(pushed: Int, pulled: Int): String
        fun failed(headline: String): String
        /** [files] is null when the count is unknown. */
        fun conflict(files: Int?): String
    }

    fun isAutomatic(trigger: String): Boolean = trigger in AUTOMATIC

    fun startText(context: Context): String = context.getString(R.string.toast_sync_start)

    fun resultText(context: Context, run: SyncRun): String =
        buildResultText(run.outcome, run.summary, run.conflict?.files?.size, ResourceTexts(context))

    /** Text for a finished run. A RUNNING outcome (should not happen) is treated like FAILED. */
    fun buildResultText(outcome: SyncOutcome, summary: String, conflictCount: Int?, texts: Texts): String =
        when (outcome) {
            SyncOutcome.OK -> {
                val counts = parseCounts(summary)
                if (counts == null || (counts.first == 0 && counts.second == 0)) texts.nothingToSync()
                else texts.counts(counts.first, counts.second)
            }
            SyncOutcome.CONFLICT -> texts.conflict(conflictCount ?: parseConflictCount(summary))
            SyncOutcome.FAILED, SyncOutcome.RUNNING -> texts.failed(headline(summary))
        }

    fun showStart(context: Context, trigger: String, settings: AppSettings) {
        if (!settings.toastOnAutoSync || !isAutomatic(trigger)) return
        post(context, startText(context), Toast.LENGTH_SHORT)
    }

    fun showResult(context: Context, run: SyncRun, settings: AppSettings) {
        if (!settings.toastOnAutoSync || !isAutomatic(run.trigger)) return
        val length = if (run.outcome == SyncOutcome.OK) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
        post(context, resultText(context, run), length)
    }

    private fun post(context: Context, text: String, length: Int) {
        val app = context.applicationContext
        Handler(Looper.getMainLooper()).post { Toast.makeText(app, text, length).show() }
    }

    // Same parsing as ui/StatusViewModel.parseSyncCounts (copied: sync must not depend on ui).
    private val CountsRegex = Regex("""^pushed (\d+), pulled (\d+)""")
    private val ConflictRegex = Regex("""conflict in (\d+) file""", RegexOption.IGNORE_CASE)

    internal fun parseCounts(summary: String): Pair<Int, Int>? =
        CountsRegex.find(summary)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }

    internal fun parseConflictCount(summary: String): Int? =
        ConflictRegex.find(summary)?.groupValues?.get(1)?.toIntOrNull()

    /** First non-blank line of [summary], cut to [MAX_HEADLINE] chars with an ellipsis. */
    internal fun headline(summary: String): String {
        val line = summary.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        return if (line.length <= MAX_HEADLINE) line else line.take(MAX_HEADLINE - 1).trimEnd() + "…"
    }

    private class ResourceTexts(private val context: Context) : Texts {
        override fun nothingToSync() = context.getString(R.string.toast_sync_nothing)
        override fun counts(pushed: Int, pulled: Int) = context.getString(R.string.toast_sync_counts, pushed, pulled)
        override fun failed(headline: String) = context.getString(R.string.toast_sync_failed, headline)
        override fun conflict(files: Int?) =
            if (files == null || files <= 0) context.getString(R.string.toast_sync_conflict_unknown)
            else context.resources.getQuantityString(R.plurals.toast_sync_conflict, files, files)
    }
}
