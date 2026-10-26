package dev.spelfrog.getsynced.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide live view of the sync run in progress (or the last one finished in this process).
 * Written by SyncWorker and by the clone flow in Setup; read by the UI and the notification.
 */
object SyncState {
    private val _current = MutableStateFlow<SyncRun?>(null)
    val current: StateFlow<SyncRun?> = _current

    fun update(run: SyncRun) { _current.value = run }
}
