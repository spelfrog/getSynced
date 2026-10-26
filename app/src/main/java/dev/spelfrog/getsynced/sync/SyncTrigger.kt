package dev.spelfrog.getsynced.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Entry point for every trigger (button, accessibility, intent). Enqueues SyncWorker as unique expedited work. */
object SyncTrigger {
    const val WORK_NAME = "sync"

    /** Trigger label of the run started by "Resolve and sync" (the choices themselves are in [ConflictChoices]). */
    const val TRIGGER_RESOLVE = "resolve"

    /**
     * Enqueues one sync. If a sync is still waiting to start, this call is dropped (it will cover the
     * new trigger anyway). If one is already running, exactly one follow-up run is appended so edits
     * made during the run are not missed. Further calls while both exist are dropped.
     */
    fun enqueue(context: Context, trigger: String) {
        val wm = WorkManager.getInstance(context.applicationContext)
        val policy = runCatching {
            val states = wm.getWorkInfosForUniqueWork(WORK_NAME).get().map { it.state }
            when {
                states.any { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.BLOCKED } -> ExistingWorkPolicy.KEEP
                states.any { it == WorkInfo.State.RUNNING } -> ExistingWorkPolicy.APPEND_OR_REPLACE
                else -> ExistingWorkPolicy.KEEP
            }
        }.getOrDefault(ExistingWorkPolicy.KEEP)
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf(SyncWorker.KEY_TRIGGER to trigger))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag(WORK_NAME)
            .build()
        wm.enqueueUniqueWork(WORK_NAME, policy, request)
    }

    private fun states(context: Context): Flow<List<WorkInfo.State>> =
        WorkManager.getInstance(context.applicationContext)
            .getWorkInfosForUniqueWorkFlow(WORK_NAME)
            .map { infos -> infos.map { it.state } }

    /** True while a sync is enqueued (e.g. waiting for network) or running. */
    fun isQueuedOrRunning(context: Context): Flow<Boolean> =
        states(context).map { s -> s.any { !it.isFinished } }.distinctUntilChanged()

    /** True while a sync is enqueued but not yet running; usually means "waiting for network". */
    fun isWaiting(context: Context): Flow<Boolean> =
        states(context)
            .map { s -> s.any { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.BLOCKED } }
            .distinctUntilChanged()
}
