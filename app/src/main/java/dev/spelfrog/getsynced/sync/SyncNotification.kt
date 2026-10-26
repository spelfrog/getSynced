package dev.spelfrog.getsynced.sync

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.spelfrog.getsynced.App
import dev.spelfrog.getsynced.R
import dev.spelfrog.getsynced.ui.MainActivity

/** Progress and result notifications for sync runs. */
object SyncNotification {
    /** Also the foreground notification id of SyncWorker. */
    const val ID_PROGRESS = 1001
    const val ID_RESULT = 1002

    /** Ongoing progress notification; [run] null means "starting". */
    fun progress(context: Context, run: SyncRun?): Notification =
        NotificationCompat.Builder(context, App.CHANNEL_SYNC)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_syncing_title))
            .setContentText(progressText(context, run))
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent(context))
            .build()

    /** Re-posts the progress notification with the current step. */
    fun update(context: Context, run: SyncRun) {
        notify(context, ID_PROGRESS, progress(context, run))
    }

    /** Persistent-until-dismissed result for FAILED / CONFLICT runs. */
    fun showResult(context: Context, run: SyncRun) {
        val title = context.getString(
            if (run.outcome == SyncOutcome.CONFLICT) R.string.notif_conflict_title else R.string.notif_failed_title
        )
        val failed = run.steps.lastOrNull { it.state == StepState.FAILED }
        val summary = run.summary.ifBlank { failed?.detail.orEmpty() }
        val big = buildString {
            append(summary)
            if (failed != null && failed.detail.isNotBlank() && failed.detail != summary) {
                append("\n\n").append(failed.name).append(": ").append(failed.detail)
            }
        }
        val n = NotificationCompat.Builder(context, App.CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(big))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setContentIntent(openAppIntent(context))
            .build()
        notify(context, ID_RESULT, n)
    }

    /** Removes the progress notification. */
    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_PROGRESS)
    }

    /** Removes a stale result notification (called when a new run starts). */
    fun cancelResult(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_RESULT)
    }

    /** "Fetch · receiving objects 40%" for the step that is running (or the last one that ran). */
    fun progressText(context: Context, run: SyncRun?): String {
        val step = run?.steps?.lastOrNull { it.state == StepState.RUNNING }
            ?: run?.steps?.lastOrNull { it.state != StepState.PENDING }
            ?: return context.getString(R.string.notif_starting)
        return if (step.detail.isBlank()) step.name else "${step.name} · ${step.detail}"
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun notify(context: Context, id: Int, notification: Notification) {
        if (!canNotify(context)) return
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // Permission revoked between check and notify.
        }
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
