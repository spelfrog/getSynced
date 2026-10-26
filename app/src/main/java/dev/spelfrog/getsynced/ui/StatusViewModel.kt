package dev.spelfrog.getsynced.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.spelfrog.getsynced.settings.AppSettings
import dev.spelfrog.getsynced.settings.SettingsRepository
import dev.spelfrog.getsynced.sync.SyncHistory
import dev.spelfrog.getsynced.sync.SyncRun
import dev.spelfrog.getsynced.sync.SyncState
import dev.spelfrog.getsynced.sync.SyncTrigger
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import dev.spelfrog.getsynced.git.CommitInfo
import dev.spelfrog.getsynced.git.GitRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.io.File
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class StatusUiState(
    /** null until the first settings value is loaded. */
    val settings: AppSettings? = null,
    /** Live run of this process (running or just finished), from SyncState. */
    val live: SyncRun? = null,
    /** Newest first. */
    val history: List<SyncRun> = emptyList(),
    val queuedOrRunning: Boolean = false,
    val enqueued: Boolean = false,
    /** Newest first, from the vault's git log. Empty until loaded or when there is no repo. */
    val commits: List<CommitInfo> = emptyList(),
) {
    val isLoaded: Boolean get() = settings != null
    val isConfigured: Boolean get() = settings?.isConfigured == true

    /** The run shown in the header: the live one unless history has something newer. */
    val latest: SyncRun?
        get() {
            val newest = history.firstOrNull()
            return when {
                live == null -> newest
                newest == null || live.startedAt >= newest.startedAt -> live
                else -> newest
            }
        }

    val isRunning: Boolean get() = live?.isRunning == true

    /** Enqueued but the worker has not started: WorkManager waits for the network constraint. */
    val waitingForNetwork: Boolean get() = enqueued && !isRunning

    /** Disables the Sync now button. */
    val busy: Boolean get() = queuedOrRunning || isRunning
}

class StatusViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = SettingsRepository.get(application).settings

    /** Re-read the git log whenever the vault path changes or a run finishes (ids of finished runs). */
    private val commits: Flow<List<CommitInfo>> = combine(
        settings.map { it.vaultPath }.distinctUntilChanged(),
        SyncState.current.map { it?.takeIf { r -> !r.isRunning }?.id ?: 0L }.distinctUntilChanged(),
        SyncHistory.get(application).runs.map { it.firstOrNull()?.id ?: 0L }.distinctUntilChanged(),
    ) { path, _, _ -> path }
        .map { path -> loadCommits(path) }
        .flowOn(Dispatchers.IO)

    val state: StateFlow<StatusUiState> = combine(
        settings,
        SyncState.current,
        SyncHistory.get(application).runs,
        SyncTrigger.isQueuedOrRunning(application),
        SyncTrigger.isWaiting(application),
        commits,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        StatusUiState(
            settings = values[0] as AppSettings,
            live = values[1] as SyncRun?,
            history = values[2] as List<SyncRun>,
            queuedOrRunning = values[3] as Boolean,
            enqueued = values[4] as Boolean,
            commits = values[5] as List<CommitInfo>,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatusUiState())

    private fun loadCommits(path: String): List<CommitInfo> {
        if (path.isBlank()) return emptyList()
        val dir = File(path)
        return runCatching {
            if (!GitRepo.isRepo(dir)) emptyList() else GitRepo(dir).use { it.recentCommits(3) }
        }.getOrDefault(emptyList())
    }
}
