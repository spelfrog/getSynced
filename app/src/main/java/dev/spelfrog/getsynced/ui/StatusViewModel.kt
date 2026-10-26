package dev.spelfrog.getsynced.ui

import android.app.Application
import dev.spelfrog.getsynced.R
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.spelfrog.getsynced.settings.AppSettings
import dev.spelfrog.getsynced.settings.SettingsRepository
import dev.spelfrog.getsynced.sync.SyncHistory
import dev.spelfrog.getsynced.sync.StepState
import dev.spelfrog.getsynced.sync.SyncRun
import dev.spelfrog.getsynced.sync.SyncStep
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
    /** Trigger id ("manual", "open", ...) to a sentence for people ("Obsidian opened"). */
    val triggerLabels: Map<String, String> = emptyMap(),
) {
    /** Human words for a [SyncRun.trigger] id; unknown ids are shown as they are. */
    fun triggerLabel(trigger: String): String = triggerLabels[trigger] ?: trigger

    /** History without the run the header already shows. */
    val olderRuns: List<SyncRun>
        get() {
            val latestId = latest?.id
            return if (latestId != null && history.firstOrNull()?.id == latestId) history.drop(1) else history
        }

    /** The step the live run is working on, null when idle. */
    val runningStep: SyncStep?
        get() = live?.takeIf { it.isRunning }?.steps?.lastOrNull { it.state == StepState.RUNNING }

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

    /** Label of the watched app, resolved once per package (PackageManager lookups are not free). */
    private val triggerLabels: Flow<Map<String, String>> = settings
        .map { it.triggerPackage }
        .distinctUntilChanged()
        .map { pkg -> buildTriggerLabels(appLabel(pkg)) }
        .flowOn(Dispatchers.IO)

    val state: StateFlow<StatusUiState> = combine(
        settings,
        SyncState.current,
        SyncHistory.get(application).runs,
        SyncTrigger.isQueuedOrRunning(application),
        SyncTrigger.isWaiting(application),
        commits,
        triggerLabels,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        StatusUiState(
            settings = values[0] as AppSettings,
            live = values[1] as SyncRun?,
            history = values[2] as List<SyncRun>,
            queuedOrRunning = values[3] as Boolean,
            enqueued = values[4] as Boolean,
            commits = values[5] as List<CommitInfo>,
            triggerLabels = values[6] as Map<String, String>,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatusUiState())

    private fun appLabel(pkg: String): String? {
        if (pkg.isBlank()) return null
        val pm = getApplication<Application>().packageManager
        return runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    private fun buildTriggerLabels(appLabel: String?): Map<String, String> {
        val app = getApplication<Application>()
        val name = appLabel ?: app.getString(R.string.status_trigger_app_fallback)
        return mapOf(
            "manual" to app.getString(R.string.status_trigger_manual),
            "open" to app.getString(R.string.status_trigger_open, name),
            "close" to app.getString(R.string.status_trigger_close, name),
            "intent" to app.getString(R.string.status_trigger_intent),
            "clone" to app.getString(R.string.status_trigger_clone),
        )
    }

    private fun loadCommits(path: String): List<CommitInfo> {
        if (path.isBlank()) return emptyList()
        val dir = File(path)
        return runCatching {
            if (!GitRepo.isRepo(dir)) emptyList() else GitRepo(dir).use { it.recentCommits(3) }
        }.getOrDefault(emptyList())
    }
}

/** Counts from an OK run's summary ("pushed 1, pulled 2, 0.9 s"); null when the summary has another shape. */
data class SyncCounts(val pushed: Int, val pulled: Int)

private val CountsRegex = Regex("""^pushed (\d+), pulled (\d+)""")

fun parseSyncCounts(summary: String): SyncCounts? =
    CountsRegex.find(summary)?.let { SyncCounts(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
