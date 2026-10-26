package dev.spelfrog.getsynced.sync

import dev.spelfrog.getsynced.git.Credentials
import dev.spelfrog.getsynced.git.GitRepo
import dev.spelfrog.getsynced.git.MergeOutcome
import dev.spelfrog.getsynced.git.PushOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The sync algorithm (see plan): check, commit, fetch, merge, push (with one retry).
 *
 * Conflicts: the git layer detects them in memory, so a CONFLICT run leaves the working tree untouched and
 * carries a [ConflictInfo] payload. When [SyncConfig.resolution] is set and its remoteSha still equals
 * origin/<branch> after the fetch, step "Resolve" replaces "Merge" and applies the user's per-file choices.
 * If the remote moved since the choices were made, they are ignored and the normal Merge step runs.
 * Pure Kotlin + JGit, no Android dependencies, so it is unit-tested on the JVM.
 *
 * [beforePush] / [beforeFetch] are test hooks invoked right before every push / fetch attempt (inside the
 * network retry, so a throw from them counts as that attempt failing). Fetch and Push are retried once after
 * [retryDelayMs] on transient network errors (see [Transient]); [sleep] is injectable so tests do not wait.
 */
class SyncEngine(
    private val beforePush: suspend () -> Unit = {},
    private val beforeFetch: suspend () -> Unit = {},
    private val retryDelayMs: Long = 2000,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {

    /** Thrown inside a step to end it as FAILED with a precise message (no exception class prefix). */
    private class StepFailure(message: String) : Exception(message)

    /** Ends a step as SKIPPED with the given detail. */
    private class Skip(val detail: String)

    /** Ends the run. [stepDetail] is what the failing step shows (defaults to the summary). */
    private class Abort(
        val outcome: SyncOutcome,
        val summary: String,
        val stepDetail: String = summary,
        val conflict: ConflictInfo? = null,
    ) : Exception(summary)

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
        fun finish(outcome: SyncOutcome, summary: String, conflict: ConflictInfo? = null) {
            run = run.copy(
                outcome = outcome,
                summary = summary,
                finishedAt = System.currentTimeMillis(),
                conflict = conflict,
            )
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

        /** Applies [resolution] via mergeResolving; returns the step detail or a Skip. */
        suspend fun resolveStep(r: GitRepo, resolution: Resolution): Any {
            val branch = syncBranch(r)
            val message = resolveMessage(branch, config.deviceName, resolution.choices)
            val m = withContext(Dispatchers.IO) {
                r.mergeResolving(resolution.choices, config.authorName, config.authorEmail, message)
            }
            return when (m) {
                MergeOutcome.AlreadyUpToDate -> Skip("nothing to merge")
                is MergeOutcome.FastForward -> {
                    pulled += m.commits
                    "fast-forward, ${m.commits} ${plural(m.commits, "commit")} (no conflict left)"
                }
                is MergeOutcome.Merged -> {
                    pulled += m.commits
                    describeChoices(resolution.choices) + " (merged ${m.commits} ${plural(m.commits, "commit")})"
                }
                is MergeOutcome.Conflict -> {
                    val why = if (m.remoteSha.isNotEmpty() && m.remoteSha != resolution.remoteSha) {
                        "remote moved, choices discarded"
                    } else {
                        "choices do not cover every conflicting file"
                    }
                    throw conflictAbort(m, "", prefix = "$why; ")
                }
            }
        }

        suspend fun fetchMergePush(suffix: String): PushOutcome {
            val r = repo!!
            step("Fetch$suffix") { progress ->
                val (behind, retried) = withNetworkRetry(progress) {
                    beforeFetch()
                    withContext(Dispatchers.IO) { r.fetch(creds, progress) }
                }
                (if (behind == 0) "up to date" else "$behind new ${plural(behind, "commit")}") + retryNote(retried)
            }
            val resolution = config.resolution
            // Choices apply only in the first round and only against the remote commit they were made for.
            val stale = resolution != null && suffix.isEmpty() &&
                !sameCommit(withContext(Dispatchers.IO) { remoteTip(config.repoDir, syncBranch(r)) }, resolution.remoteSha)
            if (resolution != null && suffix.isEmpty() && !stale) {
                step("Resolve") { resolveStep(r, resolution) }
            } else {
                val note = if (stale) " · earlier choices discarded (remote moved)" else ""
                step("Merge$suffix") {
                    when (val m = withContext(Dispatchers.IO) { r.merge(config.authorName, config.authorEmail) }) {
                        MergeOutcome.AlreadyUpToDate -> Skip("nothing to merge$note")
                        is MergeOutcome.FastForward -> {
                            pulled += m.commits
                            "fast-forward, ${m.commits} ${plural(m.commits, "commit")}$note"
                        }
                        is MergeOutcome.Merged -> {
                            pulled += m.commits
                            "merged ${m.commits} ${plural(m.commits, "commit")}$note"
                        }
                        // The git layer detects conflicts in memory: worktree and index are untouched.
                        is MergeOutcome.Conflict -> throw conflictAbort(m, note)
                    }
                }
            }
            return step("Push$suffix", headline = { "Push failed: $it" }) { progress ->
                val (p, retried) = withNetworkRetry(progress) {
                    beforePush()
                    withContext(Dispatchers.IO) { r.push(creds, progress) }
                }
                val note = retryNote(retried)
                when (p) {
                    PushOutcome.UpToDate -> StepResult<PushOutcome>(StepState.SKIPPED, "nothing to push$note", p)
                    is PushOutcome.Pushed -> {
                        pushed += p.commits
                        StepResult<PushOutcome>(StepState.OK, "pushed ${p.commits} ${plural(p.commits, "commit")}$note", p)
                    }
                    is PushOutcome.Rejected -> StepResult<PushOutcome>(StepState.FAILED, "rejected: ${p.message}$note", p)
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
                val cleaned = withContext(Dispatchers.IO) { r.abortLeftoverMerge() }
                "$branch → $url" + if (cleaned) " · cleaned up an interrupted merge" else ""
            }

            step("Commit") {
                val r = repo!!
                withContext(Dispatchers.IO) {
                    val st = r.status()
                    if (st.isClean) return@withContext Skip("nothing to commit")
                    val sha = r.commitAll(
                        { changes -> CommitMessage.build(config.deviceName, changes) },
                        config.authorName,
                        config.authorEmail,
                    )
                        ?: return@withContext Skip("nothing to commit")
                    "${st.changedCount} changed, ${st.deleted} deleted ($sha) · ${r.lastCommitTimings}"
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
            rec.finish(a.outcome, a.summary, a.conflict)
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

    /**
     * Runs a network call with one automatic retry on transient network errors, showing the retry in the step
     * detail via [progress]. Returns the result and whether a retry was needed.
     */
    private suspend fun <T> withNetworkRetry(progress: (String) -> Unit, block: suspend () -> T): Pair<T, Boolean> {
        var retried = false
        val value = Transient.retryOnce(
            delayMs = retryDelayMs,
            onRetry = { t ->
                retried = true
                progress("network error, retrying… (${Transient.shortMessage(t)})")
            },
            sleep = sleep,
            block = block,
        )
        return value to retried
    }

    private fun retryNote(retried: Boolean): String = if (retried) " (after 1 retry)" else ""

    /** The branch GitRepo syncs: the configured one, else the checked out one. */
    private fun syncBranch(r: GitRepo): String = r.branch?.takeIf { it.isNotBlank() } ?: r.currentBranch()

    /**
     * Ends the run as CONFLICT with the payload for the Resolve card. [note] is appended to the step detail,
     * [prefix] goes before it ("remote moved, choices discarded; ").
     */
    private fun conflictAbort(m: MergeOutcome.Conflict, note: String, prefix: String = ""): Abort {
        val n = m.files.size
        val list = m.paths.joinToString(", ")
        return Abort(
            SyncOutcome.CONFLICT,
            "Merge conflict in $n ${plural(n, "file")}: $list",
            "${prefix}conflict in $n ${plural(n, "file")}: $list (nothing merged, phone commit kept)$note",
            ConflictInfo(m.files, m.headSha, m.remoteSha),
        )
    }

    companion object {
        /** Kept for callers of the old API; see [CommitMessage.build]. */
        fun commitMessage(deviceName: String, changes: ChangeSet, now: LocalDateTime = LocalDateTime.now()): String =
            CommitMessage.build(deviceName, changes, now)

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

        private fun sideLabel(side: Side): String = when (side) {
            Side.PHONE -> "phone"
            Side.REMOTE -> "remote"
            Side.BOTH -> "both"
        }

        /** "kept phone: a.md, c.md · kept remote: b.md · kept both: d.md" (empty groups left out). */
        internal fun describeChoices(choices: Map<String, Side>): String =
            Side.values().mapNotNull { side ->
                val paths = choices.filterValues { it == side }.keys.sorted()
                if (paths.isEmpty()) null else "kept ${sideLabel(side)}: ${paths.joinToString(", ")}"
            }.joinToString(" · ")

        /** "Merge origin/main (resolved on Pixel)\n\nphone a.md\nremote b.md". */
        internal fun resolveMessage(branch: String, deviceName: String, choices: Map<String, Side>): String =
            "Merge ${GitRepo.REMOTE}/$branch (resolved on $deviceName)\n\n" +
                choices.entries.sortedBy { it.key }.joinToString("\n") { (path, side) -> "${sideLabel(side)} $path" }

        /** [tip] is a full sha; [pinned] may be full or abbreviated (at least 7 chars). */
        internal fun sameCommit(tip: String?, pinned: String): Boolean =
            tip != null && pinned.length >= 7 && tip.startsWith(pinned, ignoreCase = true)

        /** Sha of refs/remotes/origin/<branch>, read through a separate short-lived repository handle. */
        internal fun remoteTip(repoDir: File, branch: String): String? =
            FileRepositoryBuilder().setGitDir(File(repoDir, Constants.DOT_GIT)).setMustExist(true).build().use { repo ->
                repo.exactRef(Constants.R_REMOTES + GitRepo.REMOTE + "/" + branch)?.objectId?.name
            }

        private fun closeQuietly(repo: GitRepo?) {
            try { repo?.close() } catch (_: Throwable) {}
        }
    }
}
