package dev.spelfrog.getsynced.trigger

/** Handle for a scheduled action; [cancel] must be safe to call more than once and after the action ran. */
fun interface Cancellable {
    fun cancel()
}

/**
 * Pure open/close detector for one target package, fed with "this package is now in front" events.
 *
 * - Null, blank and [ignored] packages (system UI, keyboards, permission dialogs) are dropped entirely:
 *   they do not change the remembered foreground app, so a keyboard popping up over the target is not a close.
 * - Target comes to front while it is not considered open -> [Event.Opened].
 * - Target leaves the front for a non-ignored package -> a [debounceMs] timer starts. If the target is still
 *   not in front when it fires -> [Event.Closed]. If the target comes back first, the timer is cancelled and
 *   nothing is emitted (it never stopped being "open").
 *
 * Not thread-safe: call everything (including the scheduler's actions) from one thread.
 */
class ForegroundTracker(
    target: String,
    private val ignored: Set<String>,
    private val debounceMs: Long = 3000,
    private val onEvent: (Event) -> Unit,
    private val scheduler: (delayMs: Long, action: () -> Unit) -> Cancellable,
) {
    enum class Event { Opened, Closed }

    var target: String = target
        private set

    /** Last non-ignored package seen in front. */
    var lastForeground: String? = null
        private set

    /** True between an [Event.Opened] and the matching [Event.Closed]. */
    var targetOpen: Boolean = false
        private set

    private var pendingClose: Cancellable? = null

    val closePending: Boolean get() = pendingClose != null

    @Suppress("UNUSED_PARAMETER")
    fun onForeground(packageName: String?, nowMs: Long) {
        if (packageName.isNullOrBlank() || packageName in ignored) return
        val previous = lastForeground
        lastForeground = packageName
        if (target.isBlank()) return

        if (packageName == target) {
            cancelPendingClose()
            if (!targetOpen) {
                targetOpen = true
                onEvent(Event.Opened)
            }
        } else if (previous == target && pendingClose == null) {
            pendingClose = scheduler(debounceMs) { onCloseTimer() }
        }
    }

    /** Switch to a new target package. No event is emitted; an already-in-front target counts as open. */
    fun updateTarget(newTarget: String) {
        if (newTarget == target) return
        cancelPendingClose()
        target = newTarget
        targetOpen = newTarget.isNotBlank() && lastForeground == newTarget
    }

    /** Forget everything and cancel a pending close. */
    fun reset() {
        cancelPendingClose()
        lastForeground = null
        targetOpen = false
    }

    private fun onCloseTimer() {
        pendingClose = null
        if (lastForeground != target && targetOpen) {
            targetOpen = false
            onEvent(Event.Closed)
        }
    }

    private fun cancelPendingClose() {
        pendingClose?.cancel()
        pendingClose = null
    }
}
