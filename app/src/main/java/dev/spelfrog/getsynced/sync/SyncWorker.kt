package dev.spelfrog.getsynced.sync

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dev.spelfrog.getsynced.R
import dev.spelfrog.getsynced.settings.Secrets
import dev.spelfrog.getsynced.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Runs one SyncEngine pass as expedited work in the foreground (dataSync).
 * Publishes every step to [SyncState] and the progress notification, appends the result to [SyncHistory].
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        const val KEY_TRIGGER = "trigger"
        private const val TAG = "SyncWorker"
        /** Minimum gap between notification updates that only change the detail text. */
        private const val NOTIFY_INTERVAL_MS = 1000L
    }

    private val lock = Any()
    private var lastSeen: SyncRun? = null
    private var lastNotifyKey: String? = null
    private var lastNotifyAt = 0L

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(null)

    private fun foregroundInfo(run: SyncRun?): ForegroundInfo {
        val notification = SyncNotification.progress(applicationContext, run)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(SyncNotification.ID_PROGRESS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(SyncNotification.ID_PROGRESS, notification)
        }
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val trigger = inputData.getString(KEY_TRIGGER) ?: "unknown"
        val startedAt = System.currentTimeMillis()
        SyncNotification.cancelResult(ctx)

        try {
            setForeground(foregroundInfo(null))
        } catch (e: IllegalStateException) {
            // Includes ForegroundServiceStartNotAllowedException (API 31+). Run without foreground.
            Log.w(TAG, "running without foreground service", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "running without foreground service", e)
        }

        val config = try {
            buildConfig()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return finish(failedRun(startedAt, trigger, e.message ?: e.javaClass.simpleName))
        }
        if (config == null) {
            return finish(failedRun(startedAt, trigger, ctx.getString(R.string.sync_not_configured)))
        }

        val run = try {
            SyncEngine().run(config, trigger) { r -> onUpdate(r) }
        } catch (e: CancellationException) {
            // Stopped by the system (e.g. network lost); WorkManager may retry it.
            val interrupted = interrupted(synchronized(lock) { lastSeen } ?: SyncRun(startedAt, trigger))
            SyncState.update(interrupted)
            withContext(NonCancellable) { SyncHistory.get(ctx).append(interrupted) }
            SyncNotification.cancel(ctx)
            throw e
        }
        return finish(run)
    }

    private suspend fun buildConfig(): SyncConfig? {
        val settings = SettingsRepository.get(applicationContext).current()
        if (!settings.isConfigured) return null
        val token = Secrets(applicationContext).getToken()
        return SyncConfig(
            repoDir = File(settings.vaultPath),
            remoteUrl = settings.remoteUrl,
            username = settings.gitUsername,
            token = token,
            authorName = settings.authorName,
            authorEmail = settings.authorEmail,
            branch = settings.branch.ifBlank { null },
            deviceName = deviceName(),
        )
    }

    /** The user-visible device name ("Raphaels S22") from system settings, else the model ("SM-S908B"). */
    private fun deviceName(): String {
        val userName = try {
            Settings.Global.getString(applicationContext.contentResolver, "device_name")
        } catch (_: Throwable) {
            null
        }
        return userName?.trim()?.takeIf { it.isNotEmpty() } ?: Build.MODEL ?: "android"
    }

    private fun onUpdate(run: SyncRun) {
        SyncState.update(run)
        val now = SystemClock.elapsedRealtime()
        val shouldNotify = synchronized(lock) {
            lastSeen = run
            // Step state changes always notify; detail-only changes (fetch percentages) are throttled.
            val key = run.steps.joinToString(",") { it.state.name }
            val due = key != lastNotifyKey || now - lastNotifyAt >= NOTIFY_INTERVAL_MS
            if (due) {
                lastNotifyKey = key
                lastNotifyAt = now
            }
            due
        }
        if (shouldNotify && run.isRunning) SyncNotification.update(applicationContext, run)
    }

    private suspend fun finish(run: SyncRun): Result {
        val ctx = applicationContext
        val final = if (run.isRunning) {
            // Defensive: the engine must never return a RUNNING run.
            run.copy(outcome = SyncOutcome.FAILED, finishedAt = run.finishedAt ?: System.currentTimeMillis())
        } else run
        SyncState.update(final)
        withContext(NonCancellable) { SyncHistory.get(ctx).append(final) }
        SyncNotification.cancel(ctx)
        if (final.outcome != SyncOutcome.OK) SyncNotification.showResult(ctx, final)
        // No retry on failure: the next open/close/manual trigger runs it again.
        return if (final.outcome == SyncOutcome.FAILED) Result.failure() else Result.success()
    }

    private fun failedRun(startedAt: Long, trigger: String, message: String): SyncRun {
        val now = System.currentTimeMillis()
        return SyncRun(
            startedAt = startedAt,
            trigger = trigger,
            steps = listOf(
                SyncStep(
                    name = applicationContext.getString(R.string.step_check),
                    state = StepState.FAILED,
                    detail = message,
                    startedAt = startedAt,
                    durationMs = now - startedAt,
                )
            ),
            outcome = SyncOutcome.FAILED,
            summary = message,
            finishedAt = now,
        )
    }

    private fun interrupted(run: SyncRun): SyncRun {
        val now = System.currentTimeMillis()
        val message = applicationContext.getString(R.string.sync_interrupted)
        return run.copy(
            steps = run.steps.map { step ->
                if (step.state == StepState.RUNNING) {
                    step.copy(
                        state = StepState.FAILED,
                        detail = message,
                        durationMs = step.startedAt?.let { now - it },
                    )
                } else step
            },
            outcome = SyncOutcome.FAILED,
            summary = message,
            finishedAt = now,
        )
    }
}
