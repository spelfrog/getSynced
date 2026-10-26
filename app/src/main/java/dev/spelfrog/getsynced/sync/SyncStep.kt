package dev.spelfrog.getsynced.sync

import kotlinx.serialization.Serializable

enum class StepState { PENDING, RUNNING, OK, FAILED, SKIPPED }

/** One step of a sync run. Immutable; the engine emits a new SyncRun snapshot on every change. */
@Serializable
data class SyncStep(
    val name: String,
    val state: StepState = StepState.PENDING,
    /** One line, user facing: "3 changed, 1 deleted", "receiving objects 40%", or the full error text. */
    val detail: String = "",
    val startedAt: Long? = null,
    val durationMs: Long? = null,
)

enum class SyncOutcome { RUNNING, OK, FAILED, CONFLICT }

@Serializable
data class SyncRun(
    /** Epoch millis of start; also the id. */
    val startedAt: Long,
    /** What started it: "manual", "open", "close", "intent", "clone". */
    val trigger: String,
    val steps: List<SyncStep> = emptyList(),
    val outcome: SyncOutcome = SyncOutcome.RUNNING,
    /** One line: "pushed 1, pulled 2, 0.9 s" or the headline error. */
    val summary: String = "",
    val finishedAt: Long? = null,
    /** Set on CONFLICT runs: the files and the commits involved, for the Resolve card. */
    val conflict: ConflictInfo? = null,
) {
    val id: Long get() = startedAt
    val isRunning: Boolean get() = outcome == SyncOutcome.RUNNING
}

/** Everything the engine needs for one run. Built by SyncWorker from Settings + Secrets. */
data class SyncConfig(
    val repoDir: java.io.File,
    val remoteUrl: String,
    val username: String,
    val token: String,
    val authorName: String,
    val authorEmail: String,
    /** Branch to sync; null = current branch of the repo. */
    val branch: String? = null,
    /** Used as the commit subject prefix: "<deviceName>: edited note.md". */
    val deviceName: String = "android",
    /** User's per-file choices for a pending conflict; applied only if remoteSha still matches origin. */
    val resolution: Resolution? = null,
)
