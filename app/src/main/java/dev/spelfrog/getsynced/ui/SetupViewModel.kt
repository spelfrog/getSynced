package dev.spelfrog.getsynced.ui

import android.app.Application
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.spelfrog.getsynced.git.Credentials
import kotlinx.coroutines.CancellationException
import dev.spelfrog.getsynced.R
import dev.spelfrog.getsynced.settings.AppSettings
import dev.spelfrog.getsynced.auth.GitHubDeviceFlow
import dev.spelfrog.getsynced.auth.GitHubRepos
import dev.spelfrog.getsynced.auth.GitHubRepo
import dev.spelfrog.getsynced.git.GitRepo
import dev.spelfrog.getsynced.settings.Permissions
import dev.spelfrog.getsynced.settings.Secrets
import dev.spelfrog.getsynced.settings.SettingsRepository
import dev.spelfrog.getsynced.sync.StepState
import dev.spelfrog.getsynced.sync.SyncOutcome
import dev.spelfrog.getsynced.sync.SyncRun
import dev.spelfrog.getsynced.sync.SyncState
import dev.spelfrog.getsynced.sync.SyncStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Editable copy of the settings plus the token. Persisted (debounced) on every change. */
data class SetupForm(
    val remoteUrl: String = "",
    val username: String = "oauth2",
    val token: String = "",
    val vaultPath: String = "",
    val authorName: String = "",
    val authorEmail: String = "",
    val branch: String = "",
    val triggerPackage: String = "",
    val syncOnOpen: Boolean = true,
    val syncOnClose: Boolean = true,
    val authMode: String = AppSettings.AUTH_TOKEN,
    val githubClientId: String = GitHubDeviceFlow.DEFAULT_CLIENT_ID,
    val githubLogin: String = "",
    val githubTokenExpiresAt: Long = 0L,
) {
    val usesGitHub: Boolean get() = authMode == AppSettings.AUTH_GITHUB
    val gitUsername: String get() = if (usesGitHub) GitHubDeviceFlow.GIT_USERNAME else username.trim().ifEmpty { "oauth2" }
}

/** Progress of a "Sign in with GitHub" attempt. "Signed in" is not a state here: it is form.githubLogin. */
sealed class GitHubAuthState {
    object Idle : GitHubAuthState()
    object Requesting : GitHubAuthState()
    data class WaitingForUser(val userCode: String, val verificationUri: String, val expiresAt: Long) : GitHubAuthState()
    data class Error(val text: String) : GitHubAuthState()
}

enum class FolderState {
    /** No path entered yet. */
    UNSET,
    CHECKING,
    /** All files access missing, folder cannot be inspected. */
    NO_ACCESS,
    /** Relative path or an existing file. */
    NOT_A_FOLDER,
    MISSING,
    EMPTY,
    /** Has files but no git repository. */
    HAS_FILES,
    REPO,
}

enum class UrlProblem { NOT_HTTPS, INVALID }

fun validateRemoteUrl(url: String): UrlProblem? {
    val u = url.trim()
    if (u.isEmpty()) return null
    if (!u.startsWith("https://", ignoreCase = true)) return UrlProblem.NOT_HTTPS
    val host = runCatching { Uri.parse(u).host }.getOrNull()
    if (host.isNullOrBlank() || u.any { it.isWhitespace() }) return UrlProblem.INVALID
    return null
}

/** Comparable form of a remote URL: trimmed, no trailing "/" or ".git", lower case. */
fun normalizeRemote(url: String?): String =
    url.orEmpty().trim().trimEnd('/').removeSuffix(".git").trimEnd('/').lowercase()

/** True when both URLs are set and point at different remotes (ignoring case, trailing ".git" and "/"). */
fun remotesDiffer(a: String?, b: String?): Boolean {
    val na = normalizeRemote(a)
    val nb = normalizeRemote(b)
    return na.isNotEmpty() && nb.isNotEmpty() && na != nb
}

/**
 * Converts a SAF tree URI from OpenDocumentTree into a java.io.File path.
 * "primary:Documents/Obsidian" -> /storage/emulated/0/Documents/Obsidian, "1234-ABCD:Notes" -> /storage/1234-ABCD/Notes.
 */
@Suppress("DEPRECATION")
fun treeUriToPath(uri: Uri): String? {
    val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
    if (docId.startsWith("raw:")) return docId.removePrefix("raw:")
    val volume = docId.substringBefore(':')
    val rel = docId.substringAfter(':', "").trim('/')
    val base = when (volume) {
        "primary" -> Environment.getExternalStorageDirectory().path
        "home" -> File(Environment.getExternalStorageDirectory(), Environment.DIRECTORY_DOCUMENTS).path
        "" -> return null
        else -> "/storage/$volume"
    }
    return if (rel.isEmpty()) base else "$base/$rel"
}

/**
 * Folder names may legitimately end with a space, so only line breaks and a trailing "/" are removed.
 * Leading whitespace is dropped because a path must start with "/".
 */
fun cleanPath(path: String): String {
    val p = path.replace("\n", "").replace("\r", "").trimStart()
    return if (p.length > 1) p.trimEnd('/') else p
}

/** Full exception text including the cause chain, for selectable error blocks. */
fun errorText(e: Throwable): String = buildString {
    val seen = HashSet<Throwable>()
    var cur: Throwable? = e
    while (cur != null && seen.add(cur)) {
        if (isNotEmpty()) append("\ncaused by: ")
        append(cur.javaClass.simpleName)
        cur.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
        cur = cur.cause
    }
}

/** Survives the ViewModel so the last edit is still written when the screen is left. */
private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

@OptIn(FlowPreview::class)
class SetupViewModel(app: Application) : AndroidViewModel(app) {
    companion object {
        const val TRIGGER_CLONE = "clone"
        const val STEP_PREPARE = "Prepare"
        const val STEP_CLONE = "Clone"
        const val STEP_CONFIGURE = "Configure"
        const val DEFAULT_TRIGGER_PACKAGE = "md.obsidian"
    }

    private val repo = SettingsRepository.get(app)
    private var secrets: Secrets? = null
    private var savedToken: String? = null

    private val _form = MutableStateFlow(SetupForm())
    val form: StateFlow<SetupForm> = _form.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _folderState = MutableStateFlow(FolderState.UNSET)
    val folderState: StateFlow<FolderState> = _folderState.asStateFlow()

    /** Live clone run (the last one in this process), null if none. */
    val cloneRun: StateFlow<SyncRun?> = SyncState.current
        .map { run -> run?.takeIf { it.trigger == TRIGGER_CLONE } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncState.current.value?.takeIf { it.trigger == TRIGGER_CLONE })

    /** Setup finished earlier; the screen then offers a plain "Done". */
    val setupDone: StateFlow<Boolean> = repo.settings
        .map { it.setupDone }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _repoList = MutableStateFlow<RepoListState>(RepoListState.Idle)
    val repoList: StateFlow<RepoListState> = _repoList.asStateFlow()

    /** Remote URL found in the .git of the chosen folder (FolderState.REPO), else null. */
    private val _existingRemote = MutableStateFlow<String?>(null)
    val existingRemote: StateFlow<String?> = _existingRemote.asStateFlow()

    /** (folder remote, form remote) pair the user confirmed with "Keep mine"; normalized. */
    private val _acceptedMismatch = MutableStateFlow<Pair<String, String>?>(null)

    /** True while the user chose "Keep mine" for exactly the current folder remote / form remote pair. */
    val remoteMismatchAccepted: StateFlow<Boolean> =
        combine(_acceptedMismatch, _existingRemote, _form) { accepted, existing, f ->
            accepted != null && accepted == (normalizeRemote(existing) to normalizeRemote(f.remoteUrl))
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _githubAuth = MutableStateFlow<GitHubAuthState>(GitHubAuthState.Idle)
    val githubAuth: StateFlow<GitHubAuthState> = _githubAuth.asStateFlow()
    private var githubJob: Job? = null

    /** Error from "Use existing repository" (clone errors live in [cloneRun]). */
    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError.asStateFlow()

    /** True once clone / use-existing succeeded; the screen calls onDone and then [onFinishedHandled]. */
    private val _finished = MutableStateFlow(false)
    val finished: StateFlow<Boolean> = _finished.asStateFlow()

    private var folderJob: Job? = null

    init {
        viewModelScope.launch {
            val loadedForm = withContext(Dispatchers.IO) {
                val s = repo.current()
                val token = runCatching { secretsOrCreate().getToken() }.getOrDefault("")
                savedToken = token
                val trigger = s.triggerPackage.ifBlank {
                    val pm = getApplication<Application>().packageManager
                    if (pm.getLaunchIntentForPackage(DEFAULT_TRIGGER_PACKAGE) != null) DEFAULT_TRIGGER_PACKAGE else ""
                }
                SetupForm(
                    remoteUrl = s.remoteUrl,
                    username = s.username,
                    token = token,
                    vaultPath = s.vaultPath,
                    authorName = s.authorName,
                    authorEmail = s.authorEmail,
                    branch = s.branch,
                    triggerPackage = trigger,
                    syncOnOpen = s.syncOnOpen,
                    syncOnClose = s.syncOnClose,
                    authMode = s.authMode,
                    githubClientId = s.githubClientId,
                    githubLogin = s.githubLogin,
                    githubTokenExpiresAt = s.githubTokenExpiresAt,
                )
            }
            _form.value = loadedForm
            _loaded.value = true
            refreshFolder(delayMs = 0)
            launch {
                _form.debounce(400).distinctUntilChanged().collect { persist(it) }
            }
            launch {
                _form.map { it.vaultPath }.distinctUntilChanged().collect { refreshFolder(delayMs = 300) }
            }
        }
    }

    private fun secretsOrCreate(): Secrets =
        secrets ?: Secrets(getApplication()).also { secrets = it }

    fun edit(transform: (SetupForm) -> SetupForm) {
        if (!_loaded.value) return
        _form.update(transform)
    }

    fun setVaultFromTreeUri(uri: Uri) {
        val path = treeUriToPath(uri) ?: return
        edit { it.copy(vaultPath = path) }
    }

    /** Writes the form right away (call on ON_STOP). */
    fun saveNow() {
        if (!_loaded.value) return
        val f = _form.value
        saveScope.launch { persist(f) }
    }

    override fun onCleared() {
        saveNow()
    }

    private suspend fun persist(f: SetupForm): Unit = withContext(Dispatchers.IO) {
        repo.update {
            it.copy(
                remoteUrl = f.remoteUrl.trim(),
                username = f.username.trim(),
                vaultPath = cleanPath(f.vaultPath),
                authorName = f.authorName.trim(),
                authorEmail = f.authorEmail.trim(),
                branch = f.branch.trim(),
                triggerPackage = f.triggerPackage,
                syncOnOpen = f.syncOnOpen,
                syncOnClose = f.syncOnClose,
                authMode = f.authMode,
                githubClientId = f.githubClientId.trim(),
                githubLogin = f.githubLogin,
                githubTokenExpiresAt = f.githubTokenExpiresAt,
            )
        }
        val token = f.token.trim()
        if (token != savedToken) {
            runCatching { secretsOrCreate().setToken(token) }.onSuccess { savedToken = token }
        }
    }

    /** Re-inspects the vault folder (path change, permission change, after clone). */
    fun refreshFolder(delayMs: Long = 0) {
        folderJob?.cancel()
        folderJob = viewModelScope.launch {
            if (delayMs > 0) delay(delayMs)
            _folderState.value = FolderState.CHECKING
            val path = cleanPath(_form.value.vaultPath)
            val state = withContext(Dispatchers.IO) { inspectFolder(path) }
            _existingRemote.value = if (state == FolderState.REPO) {
                withContext(Dispatchers.IO) { runCatching { GitRepo(File(path)).use { it.remoteUrl() } }.getOrNull() }
            } else null
            _folderState.value = state
            // An existing clone already knows its remote: take it over when the field is still empty.
            _existingRemote.value?.takeIf { it.isNotBlank() }?.let { url ->
                if (_form.value.remoteUrl.isBlank()) _form.update { it.copy(remoteUrl = url) }
            }
        }
    }

    /** Starts the GitHub device flow; progress is in [githubAuth], the result lands in the form (token + login). */
    fun startGitHubSignIn() {
        if (!_loaded.value || githubJob?.isActive == true) return
        val clientId = _form.value.githubClientId.trim()
        if (clientId.isEmpty()) {
            _githubAuth.value = GitHubAuthState.Error(getApplication<Application>().getString(R.string.setup_github_client_id_missing))
            return
        }
        githubJob = viewModelScope.launch(Dispatchers.IO) {
            _githubAuth.value = GitHubAuthState.Requesting
            try {
                val flow = GitHubDeviceFlow(clientId)
                val code = flow.requestCode()
                _githubAuth.value = GitHubAuthState.WaitingForUser(
                    userCode = code.userCode,
                    verificationUri = code.verificationUri,
                    expiresAt = System.currentTimeMillis() + code.expiresInSec * 1000L,
                )
                val token = flow.waitForToken(code)
                val user = runCatching { flow.fetchUser(token.accessToken) }.getOrNull()
                val expiresAt = token.expiresInSec?.let { System.currentTimeMillis() + it * 1000L } ?: 0L
                secretsOrCreate().setToken(token.accessToken)
                savedToken = token.accessToken
                _form.update {
                    it.copy(
                        token = token.accessToken,
                        authMode = AppSettings.AUTH_GITHUB,
                        githubLogin = user?.login ?: "GitHub user",
                        githubTokenExpiresAt = expiresAt,
                        // Commit author from the GitHub profile unless the user typed something already.
                        authorName = it.authorName.ifBlank { user?.authorName ?: "" },
                        authorEmail = it.authorEmail.ifBlank { user?.authorEmail ?: "" },
                    )
                }
                persist(_form.value)
                _repoList.value = RepoListState.Idle
                _githubAuth.value = GitHubAuthState.Idle
            } catch (e: CancellationException) {
                _githubAuth.value = GitHubAuthState.Idle
                throw e
            } catch (e: Throwable) {
                _githubAuth.value = GitHubAuthState.Error(e.message ?: errorText(e))
            }
        }
    }

    /** Loads the signed-in account's repositories for the picker (cached until sign-out / sign-in). */
    fun loadRepos(force: Boolean = false) {
        if (!force && _repoList.value is RepoListState.Loaded) return
        if (_repoList.value is RepoListState.Loading) return
        val token = _form.value.token.trim()
        if (token.isEmpty()) {
            _repoList.value = RepoListState.Error(getApplication<Application>().getString(R.string.setup_repo_picker_not_signed_in))
            return
        }
        _repoList.value = RepoListState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            _repoList.value = try {
                RepoListState.Loaded(GitHubRepos().list(token))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                RepoListState.Error(e.message ?: errorText(e))
            }
        }
    }

    fun selectRepo(repo: GitHubRepo) {
        edit { it.copy(remoteUrl = repo.cloneUrl, branch = it.branch.ifBlank { repo.defaultBranch }) }
    }

    /** "Use folder's remote": copy the remote of the folder's .git into the form. */
    fun useFolderRemote() {
        val url = _existingRemote.value?.trim().orEmpty()
        if (url.isNotEmpty()) edit { it.copy(remoteUrl = url) }
    }

    /** "Keep mine": hide the mismatch warning until the folder remote or the form URL changes. */
    fun acceptRemoteMismatch() {
        _acceptedMismatch.value = normalizeRemote(_existingRemote.value) to normalizeRemote(_form.value.remoteUrl)
    }

    fun cancelGitHubSignIn() {
        githubJob?.cancel()
        githubJob = null
        _githubAuth.value = GitHubAuthState.Idle
    }

    /** Forgets the GitHub token locally. Revoking it on GitHub happens in the user's GitHub settings. */
    fun signOutGitHub() {
        cancelGitHubSignIn()
        _repoList.value = RepoListState.Idle
        _form.update { it.copy(token = "", githubLogin = "", githubTokenExpiresAt = 0L) }
    }

    private fun inspectFolder(path: String): FolderState {
        if (path.isEmpty()) return FolderState.UNSET
        if (!path.startsWith("/")) return FolderState.NOT_A_FOLDER
        if (!Permissions.hasAllFilesAccess(getApplication())) return FolderState.NO_ACCESS
        val dir = File(path)
        if (!dir.exists()) return FolderState.MISSING
        if (!dir.isDirectory) return FolderState.NOT_A_FOLDER
        val isRepo = runCatching { GitRepo.isRepo(dir) }.getOrElse { File(dir, ".git").isDirectory }
        if (isRepo) return FolderState.REPO
        val children = dir.list() ?: return FolderState.NO_ACCESS
        return if (children.isEmpty()) FolderState.EMPTY else FolderState.HAS_FILES
    }

    fun onFinishedHandled() {
        _finished.value = false
    }

    fun clearActionError() {
        _actionError.value = null
    }

    /** Clone the remote into the (missing or empty) vault folder, publishing steps via SyncState. */
    fun cloneIntoFolder() {
        if (_busy.value || !_loaded.value) return
        _busy.value = true
        _actionError.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                runClone(_form.value)
            } finally {
                _busy.value = false
                refreshFolder()
            }
        }
    }

    private suspend fun runClone(f: SetupForm) {
        persist(f)
        val url = f.remoteUrl.trim()
        val dir = File(cleanPath(f.vaultPath))
        val tracker = StepTracker(TRIGGER_CLONE, listOf(STEP_PREPARE, STEP_CLONE, STEP_CONFIGURE))
        var step = 0
        var wasEmpty = false
        try {
            tracker.start(0)
            if (dir.exists()) {
                if (!dir.isDirectory) throw IOException("Not a folder: ${dir.path}")
                val children = dir.list() ?: throw IOException("Cannot read ${dir.path} (all files access granted?)")
                if (children.isNotEmpty()) throw IOException("Folder is not empty: ${dir.path}")
                wasEmpty = true
                tracker.ok(0, "using empty folder ${dir.path}")
            } else {
                if (!dir.mkdirs()) throw IOException("Could not create ${dir.path} (all files access granted?)")
                wasEmpty = true
                tracker.ok(0, "created ${dir.path}")
            }

            step = 1
            tracker.start(1)
            val creds = Credentials(f.gitUsername, f.token.trim())
            val branchWanted = f.branch.trim().ifEmpty { null }
            val branch = GitRepo.clone(dir, url, branchWanted, creds) { tracker.detail(1, it) }.use { git ->
                tracker.ok(1, "cloned $url")
                step = 2
                tracker.start(2)
                git.ensureAndroidConfig(url)
                git.currentBranch()
            }
            repo.update { it.copy(branch = it.branch.ifBlank { branch }, setupDone = true) }
            _form.update { it.copy(branch = it.branch.ifBlank { branch }) }
            tracker.ok(2, "branch $branch, filemode=false, symlinks=false")
            tracker.finish(SyncOutcome.OK, "cloned into ${dir.path}")
            _finished.value = true
        } catch (e: Throwable) {
            if (step == 1 && wasEmpty) {
                // Remove the partial clone so the folder is empty again and a retry is possible.
                runCatching { dir.listFiles()?.forEach { it.deleteRecursively() } }
            }
            tracker.fail(step, errorText(e))
            tracker.finish(SyncOutcome.FAILED, e.message?.lineSequence()?.firstOrNull() ?: e.javaClass.simpleName)
        }
    }

    /** Adopt an existing repository in the vault folder. */
    fun useExistingRepository() {
        if (_busy.value || !_loaded.value) return
        _busy.value = true
        _actionError.value = null
        viewModelScope.launch(Dispatchers.IO) {
            val f = _form.value
            try {
                persist(f)
                val url = f.remoteUrl.trim()
                val branch = GitRepo(File(cleanPath(f.vaultPath))).use { git ->
                    git.ensureAndroidConfig(url)
                    git.currentBranch()
                }
                repo.update { it.copy(branch = it.branch.ifBlank { branch }, setupDone = true) }
                _form.update { it.copy(branch = it.branch.ifBlank { branch }) }
                _finished.value = true
            } catch (e: Throwable) {
                _actionError.value = errorText(e)
            } finally {
                _busy.value = false
                refreshFolder()
            }
        }
    }
}

/** Builds SyncRun snapshots for a fixed list of steps and publishes each one to [SyncState]. */
private class StepTracker(trigger: String, names: List<String>) {
    private var run = SyncRun(
        startedAt = System.currentTimeMillis(),
        trigger = trigger,
        steps = names.map { SyncStep(it) },
    )
    private var lastDetailAt = 0L

    init {
        SyncState.update(run)
    }

    @Synchronized
    private fun set(index: Int, transform: (SyncStep) -> SyncStep) {
        val steps = run.steps.toMutableList()
        steps[index] = transform(steps[index])
        run = run.copy(steps = steps)
        SyncState.update(run)
    }

    fun start(index: Int) = set(index) {
        it.copy(state = StepState.RUNNING, startedAt = System.currentTimeMillis())
    }

    /** Throttled progress line (JGit reports very often). */
    fun detail(index: Int, text: String) {
        val now = System.currentTimeMillis()
        if (now - lastDetailAt < 150) return
        lastDetailAt = now
        set(index) { it.copy(detail = text) }
    }

    fun ok(index: Int, detail: String) = set(index) { it.finished(StepState.OK, detail) }

    @Synchronized
    fun fail(index: Int, detail: String) {
        val steps = run.steps.mapIndexed { i, s ->
            when {
                i == index -> s.finished(StepState.FAILED, detail)
                i > index && s.state == StepState.PENDING -> s.copy(state = StepState.SKIPPED)
                else -> s
            }
        }
        run = run.copy(steps = steps)
        SyncState.update(run)
    }

    @Synchronized
    fun finish(outcome: SyncOutcome, summary: String) {
        run = run.copy(outcome = outcome, summary = summary, finishedAt = System.currentTimeMillis())
        SyncState.update(run)
    }

    private fun SyncStep.finished(state: StepState, detail: String): SyncStep {
        val now = System.currentTimeMillis()
        return copy(state = state, detail = detail, durationMs = startedAt?.let { now - it } ?: 0L)
    }
}
