package dev.spelfrog.getsynced.ui

import android.app.Application
import dev.spelfrog.getsynced.R
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.spelfrog.getsynced.settings.AppSettings
import dev.spelfrog.getsynced.settings.SettingsRepository
import dev.spelfrog.getsynced.sync.ConflictChoices
import dev.spelfrog.getsynced.sync.ConflictDiff
import dev.spelfrog.getsynced.sync.ConflictFile
import dev.spelfrog.getsynced.sync.ConflictInfo
import dev.spelfrog.getsynced.sync.Resolution
import dev.spelfrog.getsynced.sync.Side
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
import dev.spelfrog.getsynced.update.ReleaseInfo
import dev.spelfrog.getsynced.update.UpdateInfo
import dev.spelfrog.getsynced.update.UpdateState
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
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
    /** Update-check status for the footer; [UpdateInfo.available] is the release to offer, if any. */
    val updateInfo: UpdateInfo = UpdateInfo(),
) {
    val update: ReleaseInfo? get() = updateInfo.available

    /** Human words for a [SyncRun.trigger] id; unknown ids are shown as they are. */
    fun triggerLabel(trigger: String): String = triggerLabels[trigger] ?: trigger

    /** History without the run the header already shows. */
    /** The History card shows the last few runs after the one in the header; the file keeps more for later. */
    val olderRuns: List<SyncRun>
        get() {
            val latestId = latest?.id
            val older = if (latestId != null && history.firstOrNull()?.id == latestId) history.drop(1) else history
            return older.take(HISTORY_SHOWN)
        }

    companion object {
        const val HISTORY_SHOWN = 5
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
        UpdateState.info,
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
            updateInfo = values[7] as UpdateInfo,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatusUiState())

    /** Foreground-only: called from the screen on ON_START. Throttled to one request per 6 h. */
    fun checkForUpdate() {
        viewModelScope.launch { UpdateState.checkIfDue(getApplication()) }
    }

    fun skipUpdate(release: ReleaseInfo) = UpdateState.skip(getApplication(), release)

    /** "Check now" in the footer: ignores the throttle and the skipped version. */
    fun checkForUpdateNow() {
        viewModelScope.launch {
            UpdateState.unskip(getApplication())
            UpdateState.checkIfDue(getApplication(), force = true)
        }
    }

    /** "Resolve and sync": store the choices for the next worker run (pinned to the remote commit), then trigger it. */
    fun resolve(info: ConflictInfo, choices: Map<String, Side>) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            ConflictChoices.save(app, Resolution(info.remoteSha, choices))
            SyncTrigger.enqueue(app, "resolve")
        }
    }

    private val _diffState = MutableStateFlow<DiffState>(DiffState.Loading)

    /** Diff of the file passed to [showDiff]; Loading until it is read. */
    val diffState: StateFlow<DiffState> = _diffState.asStateFlow()
    private var diffJob: Job? = null

    /** "Show changes": reads the three versions of [file] (object reads only) and diffs them. */
    fun showDiff(info: ConflictInfo, file: ConflictFile) {
        diffJob?.cancel()
        _diffState.value = DiffState.Loading
        diffJob = viewModelScope.launch {
            val path = settings.first().vaultPath
            _diffState.value = withContext(Dispatchers.IO) { loadDiff(path, info, file) }
        }
    }

    fun clearDiff() {
        diffJob?.cancel()
        diffJob = null
        _diffState.value = DiffState.Loading
    }

    private fun loadDiff(vaultPath: String, info: ConflictInfo, file: ConflictFile): DiffState = try {
        val dir = File(vaultPath)
        if (vaultPath.isBlank() || !GitRepo.isRepo(dir)) {
            DiffState.Error(getApplication<Application>().getString(R.string.diff_error_no_repo))
        } else {
            val versions = GitRepo(dir).use { it.conflictVersions(file.path, info.headSha, info.remoteSha) }
            when {
                versions.binary -> DiffState.Binary
                versions.truncated -> DiffState.TooLarge(ConflictDiff.threeWay(versions))
                else -> DiffState.Ready(ConflictDiff.threeWay(versions))
            }
        }
    } catch (e: Exception) {
        DiffState.Error(e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
    }

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
            "resolve" to app.getString(R.string.status_trigger_resolve),
        )
    }

    private fun loadCommits(path: String): List<CommitInfo> {
        if (path.isBlank()) return emptyList()
        val dir = File(path)
        return runCatching {
            if (!GitRepo.isRepo(dir)) emptyList() else GitRepo(dir).use { it.recentCommits(10) }
        }.getOrDefault(emptyList())
    }
}

/** Counts from an OK run's summary ("pushed 1, pulled 2, 0.9 s"); null when the summary has another shape. */
data class SyncCounts(val pushed: Int, val pulled: Int)

private val CountsRegex = Regex("""^pushed (\d+), pulled (\d+)""")

fun parseSyncCounts(summary: String): SyncCounts? =
    CountsRegex.find(summary)?.let { SyncCounts(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
