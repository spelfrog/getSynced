package dev.spelfrog.getsynced.sync

import dev.spelfrog.getsynced.git.Credentials
import dev.spelfrog.getsynced.git.GitRepo
import dev.spelfrog.getsynced.git.MergeOutcome
import dev.spelfrog.getsynced.git.PushOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The sync algorithm (see plan): check, commit, fetch, merge, push (with one retry).
 * Pure Kotlin + JGit, no Android dependencies, so it is unit-tested on the JVM.
 *
 * [beforePush] is a test hook invoked right before every push attempt.
 */
class SyncEngine(private val beforePush: suspend () -> Unit = {}) {

    /** Thrown inside a step to end it as FAILED with a precise message (no exception class prefix). */
    private class StepFailure(message: String) : Exception(message)

    /** Ends a step as SKIPPED with the given detail. */
    private class Skip(val detail: String)

    /** Ends the run. [stepDetail] is what the failing step shows (defaults to the summary). */
    private class Abort(val outcome: SyncOutcome, val summary: String, val stepDetail: String = summary) :
        Exception(summary)

    private class Recorder(trigger: String, private val onUpdate: (SyncRun) -> Unit) {
        @Volatile var run = SyncRun(startedAt = System.currentTimeMillis(), trigger = trigger)
            private set

        @Synchronized
        fun set(index: Int, step: SyncStep) {
            val steps = run.steps.toMutableList()
            if (index == steps.size) steps.add(step) else steps[index] = step
            run = run.copy(steps = steps)
            emit()
        }

        @Synchronized
        fun finish(outcome: SyncOutcome, summary: String) {
            run = run.copy(outcome = outcome, summary = summary, finishedAt = System.currentTimeMillis())
            emit()
        }

        private fun emit() {
            val snapshot = run
            try { onUpdate(snapshot) } catch (_: Throwable) {}
        }
    }

    /**
     * Runs one sync. onUpdate receives an immutable SyncRun snapshot after every step change.
     * Never throws: every failure ends in a SyncRun with outcome FAILED or CONFLICT.
     */
    suspend fun run(config: SyncConfig, trigger: String, onUpdate: (SyncRun) -> Unit = {}): SyncRun {
        val rec = Recorder(trigger, onUpdate)
        val creds = Credentials(config.username, config.token)
        var repo: GitRepo? = null
        var pushed = 0
        var pulled = 0

        /**
         * Runs one step. [block] returns the OK detail, or a [Skip]. Failures become FAILED and abort the run
         * via [Abort] with the headline produced by [headline].
         */
        suspend fun <T> step(
            name: String,
            headline: (String) -> String = { "$name failed: $it" },
            block: suspend (progress: (String) -> Unit) -> T,
        ): T {
            val index = rec.run.steps.size
            val started = System.currentTimeMillis()
            val running = SyncStep(name, StepState.RUNNING, startedAt = started)
            rec.set(index, running)
            val progress: (String) -> Unit = { line -> rec.set(index, running.copy(detail = line)) }
            try {
                val value = block(progress)
                val (state, detail) = when (value) {
                    is Skip -> StepState.SKIPPED to value.detail
                    is StepResult<*> -> value.state to value.detail
                    else -> StepState.OK to (value?.toString() ?: "")
                }
                rec.set(index, running.copy(state = state, detail = detail, durationMs = System.currentTimeMillis() - started))
                return value
            } catch (e: CancellationException) {
                rec.set(index, running.copy(state = StepState.FAILED, detail = "cancelled", durationMs = System.currentTimeMillis() - started))
                throw e
            } catch (a: Abort) {
                rec.set(index, running.copy(state = StepState.FAILED, detail = a.stepDetail, durationMs = System.currentTimeMillis() - started))
                throw a
            } catch (t: Throwable) {
                val detail = describe(t)
                rec.set(index, running.copy(state = StepState.FAILED, detail = detail, durationMs = System.currentTimeMillis() - started))
                throw Abort(SyncOutcome.FAILED, if (isAuthError(detail)) "Auth failed: $detail" else headline(detail))
            }
        }

        suspend fun fetchMergePush(suffix: String): PushOutcome {
            val r = repo!!
            step("Fetch$suffix") { progress ->
                val behind = withContext(Dispatchers.IO) { r.fetch(creds, progress) }
                if (behind == 0) "up to date" else "$behind new ${plural(behind, "commit")}"
            }
            step("Merge$suffix") {
                when (val m = withContext(Dispatchers.IO) { r.merge(config.authorName, config.authorEmail) }) {
                    MergeOutcome.AlreadyUpToDate -> Skip("nothing to merge")
                    is MergeOutcome.FastForward -> {
                        pulled += m.commits
                        "fast-forward, ${m.commits} ${plural(m.commits, "commit")}"
                    }
                    is MergeOutcome.Merged -> {
                        pulled += m.commits
                        "merged ${m.commits} ${plural(m.commits, "commit")}"
                    }
                    is MergeOutcome.Conflict -> {
                        withContext(Dispatchers.IO) { r.resetHardToHead() }
                        val n = m.files.size
                        val text = "conflict in $n ${plural(n, "file")}: ${m.files.joinToString(", ")}" +
                            " (merge aborted, local commit kept)"
                        throw Abort(
                            SyncOutcome.CONFLICT,
                            "Merge conflict in $n ${plural(n, "file")}: ${m.files.joinToString(", ")}",
                            text,
                        )
                    }
                }
            }
            return step("Push$suffix", headline = { "Push failed: $it" }) { progress ->
                beforePush()
                when (val p = withContext(Dispatchers.IO) { r.push(creds, progress) }) {
                    PushOutcome.UpToDate -> StepResult<PushOutcome>(StepState.SKIPPED, "nothing to push", p)
                    is PushOutcome.Pushed -> {
                        pushed += p.commits
                        StepResult<PushOutcome>(StepState.OK, "pushed ${p.commits} ${plural(p.commits, "commit")}", p)
                    }
                    is PushOutcome.Rejected -> StepResult<PushOutcome>(StepState.FAILED, "rejected: ${p.message}", p)
                }
            }.value
        }

        try {
            step("Check") {
                val problems = mutableListOf<String>()
                val dir = config.repoDir
                if (!dir.isDirectory) problems += "folder ${dir.path} does not exist"
                else if (!dir.canWrite()) problems += "folder ${dir.path} is not writable"
                else if (!GitRepo.isRepo(dir)) problems += "${dir.path} is not a git repository"
                if (config.token.isBlank()) problems += "token missing"
                if (problems.isNotEmpty()) throw StepFailure(problems.joinToString("; "))
                val r = withContext(Dispatchers.IO) {
                    GitRepo(dir, config.branch?.takeIf { it.isNotBlank() }).also { opened ->
                        try {
                            opened.ensureAndroidConfig(config.remoteUrl.takeIf { it.isNotBlank() })
                        } catch (t: Throwable) {
                            opened.close(); throw t
                        }
                    }
                }
                repo = r
                val url = r.remoteUrl()
                if (url.isNullOrBlank()) throw StepFailure("no remote 'origin' configured")
                val branch = withContext(Dispatchers.IO) { r.currentBranch() }
                "$branch → $url"
            }

            step("Commit") {
                val r = repo!!
                withContext(Dispatchers.IO) {
                    val st = r.status()
                    if (st.isClean) return@withContext Skip("nothing to commit")
                    val sha = r.commitAll(commitMessage(config.deviceName), config.authorName, config.authorEmail)
                        ?: return@withContext Skip("nothing to commit")
                    "${st.changedCount} changed, ${st.deleted} deleted ($sha)"
                }
            }

            var push = fetchMergePush("")
            if (push is PushOutcome.Rejected) {
                push = fetchMergePush(" (retry)")
                if (push is PushOutcome.Rejected) {
                    throw Abort(SyncOutcome.FAILED, "Push rejected: ${push.message}")
                }
            }
            val secs = (System.currentTimeMillis() - rec.run.startedAt) / 1000.0
            rec.finish(SyncOutcome.OK, "pushed $pushed, pulled $pulled, ${String.format(Locale.US, "%.1f", secs)} s")
        } catch (a: Abort) {
            rec.finish(a.outcome, a.summary)
        } catch (e: CancellationException) {
            rec.finish(SyncOutcome.FAILED, "Cancelled")
            throw e
        } catch (t: Throwable) {
            rec.finish(SyncOutcome.FAILED, describe(t))
        } finally {
            closeQuietly(repo)
        }
        return rec.run
    }

    /** A step result whose state is chosen by the step itself (Push: OK / SKIPPED / FAILED-but-retry). */
    private class StepResult<T>(val state: StepState, val detail: String, val value: T)

    companion object {
        fun commitMessage(deviceName: String, now: LocalDateTime = LocalDateTime.now()): String =
            "sync from $deviceName ${now.truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)}"

        internal fun describe(t: Throwable): String {
            val parts = mutableListOf<String>()
            var cur: Throwable? = t
            val seen = HashSet<Throwable>()
            while (cur != null && seen.add(cur)) {
                val name = cur.javaClass.simpleName.ifEmpty { cur.javaClass.name }
                val msg = cur.message?.trim().orEmpty()
                val text = if (cur is StepFailure) msg else if (msg.isEmpty()) name else "$name: $msg"
                if (parts.none { it.contains(msg) && msg.isNotEmpty() }) parts += text
                cur = cur.cause
            }
            return parts.joinToString(" ← ")
        }

        internal fun isAuthError(detail: String): Boolean {
            val d = detail.lowercase(Locale.ROOT)
            return "not authorized" in d || "authentication is required" in d ||
                "401" in d || "403" in d || "auth fail" in d
        }

        private fun plural(n: Int, word: String) = if (n == 1) word else "${word}s"

        private fun closeQuietly(repo: GitRepo?) {
            try { repo?.close() } catch (_: Throwable) {}
        }
    }
}
