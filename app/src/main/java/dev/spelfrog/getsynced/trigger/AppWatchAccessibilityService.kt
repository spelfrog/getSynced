package dev.spelfrog.getsynced.trigger

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import dev.spelfrog.getsynced.settings.AppSettings
import dev.spelfrog.getsynced.settings.SettingsRepository
import dev.spelfrog.getsynced.sync.SyncTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Watches which app is in front (window state changes only, never screen content) and enqueues a sync when
 * the configured app is opened or closed. Purely event-driven: no timers run unless a close is being debounced.
 */
class AppWatchAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null
    private var tracker: ForegroundTracker? = null

    @Volatile
    private var settings: AppSettings? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            flags = AccessibilityServiceInfo.DEFAULT
        }

        tracker?.reset()
        val ignored = ignoredPackages()
        tracker = ForegroundTracker(
            target = settings?.triggerPackage.orEmpty(),
            ignored = ignored,
            onEvent = ::onTrackerEvent,
            scheduler = { delayMs, action ->
                val token = Runnable { action() }
                handler.postDelayed(token, delayMs)
                Cancellable { handler.removeCallbacks(token) }
            },
        )
        Log.d(TAG, "connected, ignoring ${ignored.size} packages")

        scope?.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { s ->
            s.launch {
                SettingsRepository.get(this@AppWatchAccessibilityService).settings.collect { new ->
                    settings = new
                    tracker?.updateTarget(new.triggerPackage)
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        tracker?.onForeground(event.packageName?.toString(), SystemClock.elapsedRealtime())
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        tracker?.reset()
        tracker = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun onTrackerEvent(event: ForegroundTracker.Event) {
        val s = settings
        val target = s?.triggerPackage.orEmpty()
        when (event) {
            ForegroundTracker.Event.Opened -> {
                Log.d(TAG, "opened $target")
                if (s != null && s.isConfigured && s.syncOnOpen) SyncTrigger.enqueue(this, "open")
            }
            ForegroundTracker.Event.Closed -> {
                Log.d(TAG, "closed $target")
                if (s != null && s.isConfigured && s.syncOnClose) SyncTrigger.enqueue(this, "close")
            }
        }
    }

    /** Packages that briefly cover the target app without meaning it was left. Settings is deliberately not here. */
    private fun ignoredPackages(): Set<String> {
        val keyboards = runCatching {
            getSystemService(InputMethodManager::class.java)
                ?.enabledInputMethodList
                ?.map { it.packageName }
                .orEmpty()
        }.getOrDefault(emptyList())
        return buildSet {
            add(packageName)
            addAll(STATIC_IGNORED)
            addAll(keyboards)
        }
    }

    private companion object {
        const val TAG = "AppWatch"
        val STATIC_IGNORED = setOf(
            "android",
            "com.android.systemui",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
        )
    }
}
