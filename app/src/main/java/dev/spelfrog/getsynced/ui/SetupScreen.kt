package dev.spelfrog.getsynced.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.spelfrog.getsynced.R
import dev.spelfrog.getsynced.settings.Permissions
import dev.spelfrog.getsynced.sync.SyncRun
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Live status of the three permissions in the checklist. */
data class PermissionStatus(val allFiles: Boolean, val notifications: Boolean, val accessibility: Boolean) {
    companion object {
        fun read(context: Context) = PermissionStatus(
            allFiles = Permissions.hasAllFilesAccess(context),
            notifications = Permissions.hasNotificationPermission(context),
            accessibility = Permissions.isAccessibilityServiceEnabled(context),
        )
    }
}

private fun Context.startSafely(intent: Intent, fallback: Intent? = null) {
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        fallback?.let { runCatching { startActivity(it) } }
    }
}

/** Sections the blocker list can scroll to. */
private enum class SetupSection { PERMISSIONS, AUTH, REPOSITORY, FOLDER, TRIGGER, ACTION, ADVANCED }

/** One missing piece before the action button works, and where to fix it. */
private data class Blocker(val text: Int, val section: SetupSection)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(onDone: () -> Unit, viewModel: SetupViewModel = viewModel()) {
    val context = LocalContext.current
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()
    val form by viewModel.form.collectAsStateWithLifecycle()
    val folderState by viewModel.folderState.collectAsStateWithLifecycle()
    val cloneRun by viewModel.cloneRun.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val actionError by viewModel.actionError.collectAsStateWithLifecycle()
    val finished by viewModel.finished.collectAsStateWithLifecycle()
    val setupDone by viewModel.setupDone.collectAsStateWithLifecycle()
    val githubAuth by viewModel.githubAuth.collectAsStateWithLifecycle()
    val repoList by viewModel.repoList.collectAsStateWithLifecycle()
    val existingRemote by viewModel.existingRemote.collectAsStateWithLifecycle()
    val mismatchAccepted by viewModel.remoteMismatchAccepted.collectAsStateWithLifecycle()
    var showRepoPicker by rememberSaveable { mutableStateOf(false) }
    if (showRepoPicker) {
        RepoPickerDialog(
            state = repoList,
            onSelect = { viewModel.selectRepo(it); showRepoPicker = false },
            onRetry = { viewModel.loadRepos(force = true) },
            onDismiss = { showRepoPicker = false },
        )
    }
    var perms by remember { mutableStateOf(PermissionStatus.read(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    perms = PermissionStatus.read(context)
                    viewModel.refreshFolder()
                }
                Lifecycle.Event.ON_STOP -> viewModel.saveNow()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(finished) {
        if (finished) {
            viewModel.onFinishedHandled()
            onDone()
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.setVaultFromTreeUri(uri)
    }

    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    // Top of each section inside the content column, for "N things left" rows.
    val sectionTops = remember { mutableStateMapOf<SetupSection, Int>() }
    fun Modifier.trackSection(section: SetupSection): Modifier =
        onGloballyPositioned { sectionTops[section] = it.positionInParent().y.roundToInt() }
    val scrollTo: (SetupSection) -> Unit = { section ->
        sectionTops[section]?.let { y -> scope.launch { scrollState.animateScrollTo(y) } }
    }
    // Leaving mid-clone would cancel it with the ViewModel, so back waits until the action is over.
    val leave = { viewModel.saveNow(); onDone() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setup_title)) },
                navigationIcon = {
                    IconButton(onClick = leave, enabled = !busy) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.setup_back))
                    }
                },
                actions = {
                    if (setupDone && !busy) {
                        TextButton(onClick = leave) {
                            Text(stringResource(R.string.setup_done))
                        }
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        if (!loaded) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(scrollState),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = ContentMaxWidth)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        stringResource(R.string.setup_autosave),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PermissionsSection(
                        status = perms,
                        modifier = Modifier.trackSection(SetupSection.PERMISSIONS),
                    ) { perms = PermissionStatus.read(context) }
                    AuthSection(
                        form = form,
                        auth = githubAuth,
                        onEdit = viewModel::edit,
                        onSignIn = viewModel::startGitHubSignIn,
                        onCancel = viewModel::cancelGitHubSignIn,
                        onSignOut = viewModel::signOutGitHub,
                        modifier = Modifier.trackSection(SetupSection.AUTH),
                    )
                    RepositorySection(
                        form = form,
                        folderState = folderState,
                        existingRemote = existingRemote,
                        mismatchAccepted = mismatchAccepted,
                        onEdit = viewModel::edit,
                        onPickFromGitHub = if (form.usesGitHub && form.githubLogin.isNotBlank() && form.token.isNotBlank()) {
                            { viewModel.loadRepos(); showRepoPicker = true }
                        } else null,
                        onUseFolderRemote = viewModel::useFolderRemote,
                        onKeepMine = viewModel::acceptRemoteMismatch,
                        modifier = Modifier.trackSection(SetupSection.REPOSITORY),
                    )
                    FolderSection(
                        form = form,
                        folderState = folderState,
                        existingRemote = existingRemote,
                        onEdit = viewModel::edit,
                        onPick = { folderPicker.launch(null) },
                        modifier = Modifier.trackSection(SetupSection.FOLDER),
                    )
                    TriggerSection(form, viewModel::edit, Modifier.trackSection(SetupSection.TRIGGER))
                    ActionSection(
                        form = form,
                        folderState = folderState,
                        hasAllFiles = perms.allFiles,
                        setupDone = setupDone,
                        cloneRun = cloneRun,
                        busy = busy,
                        actionError = actionError,
                        onClone = viewModel::cloneIntoFolder,
                        onUseExisting = viewModel::useExistingRepository,
                        onGoTo = scrollTo,
                        modifier = Modifier.trackSection(SetupSection.ACTION),
                    )
                    SetupAdvancedSection(
                        form = form,
                        auth = githubAuth,
                        onEdit = viewModel::edit,
                        modifier = Modifier.trackSection(SetupSection.ADVANCED),
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionsSection(status: PermissionStatus, modifier: Modifier = Modifier, onChanged: () -> Unit) {
    val context = LocalContext.current
    var notifAsked by rememberSaveable { mutableStateOf(false) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notifAsked = true
        onChanged()
    }
    val storageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onChanged() }
    val allGranted = status.allFiles && status.notifications && status.accessibility
    var showAll by rememberSaveable { mutableStateOf(false) }

    if (allGranted && !showAll) {
        SectionCard(title = "", modifier = modifier) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.sketch.success)
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.setup_perm_all_granted),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { showAll = true }) { Text(stringResource(R.string.setup_perm_show)) }
            }
        }
        return
    }

    SectionCard(stringResource(R.string.setup_section_permissions), modifier) {
        PermissionRow(
            granted = status.allFiles,
            label = stringResource(R.string.setup_perm_files),
            why = stringResource(R.string.setup_perm_files_why),
        ) {
            if (Permissions.storageIsRuntimePermission) {
                storageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                context.startSafely(Permissions.allFilesAccessIntent(context), Permissions.appInfoIntent(context))
            }
        }
        PermissionRow(
            granted = status.notifications,
            label = stringResource(R.string.setup_perm_notifications),
            why = stringResource(R.string.setup_perm_notifications_why),
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notifAsked) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                context.startSafely(Permissions.notificationSettingsIntent(context), Permissions.appInfoIntent(context))
            }
        }
        PermissionRow(
            granted = status.accessibility,
            label = stringResource(R.string.setup_perm_accessibility),
            why = stringResource(R.string.setup_perm_accessibility_why),
        ) {
            context.startSafely(Permissions.accessibilitySettingsIntent())
        }
        if (!status.accessibility) {
            StatusLine(
                Icons.Filled.Info,
                MaterialTheme.colorScheme.onSurfaceVariant,
                stringResource(R.string.setup_perm_restricted_note),
            )
            TextButton(onClick = { context.startSafely(Permissions.appInfoIntent(context)) }) {
                Text(stringResource(R.string.setup_open_app_info))
            }
        }
        if (allGranted) {
            TextButton(onClick = { showAll = false }, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.setup_perm_hide))
            }
        }
    }
}

@Composable
private fun PermissionRow(granted: Boolean, label: String, why: String, onOpen: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(
            if (granted) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
            contentDescription = stringResource(if (granted) R.string.setup_granted else R.string.setup_not_granted),
            tint = if (granted) MaterialTheme.sketch.success else MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!granted) {
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = onOpen) { Text(stringResource(R.string.setup_grant)) }
        }
    }
}

@Composable
private fun ActionSection(
    form: SetupForm,
    folderState: FolderState,
    hasAllFiles: Boolean,
    setupDone: Boolean,
    cloneRun: SyncRun?,
    busy: Boolean,
    actionError: String?,
    onClone: () -> Unit,
    onUseExisting: () -> Unit,
    onGoTo: (SetupSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val blockers = buildList {
        if (!hasAllFiles) add(Blocker(R.string.setup_need_files_access, SetupSection.PERMISSIONS))
        if (form.token.isBlank()) add(Blocker(R.string.setup_need_token, SetupSection.AUTH))
        if (form.remoteUrl.isBlank() || validateRemoteUrl(form.remoteUrl) != null) {
            add(Blocker(R.string.setup_need_url, SetupSection.REPOSITORY))
        }
        if (form.vaultPath.isBlank()) add(Blocker(R.string.setup_need_folder, SetupSection.FOLDER))
        when (folderState) {
            FolderState.HAS_FILES -> add(Blocker(R.string.setup_need_clean_folder, SetupSection.FOLDER))
            FolderState.NOT_A_FOLDER -> add(Blocker(R.string.setup_need_valid_folder, SetupSection.FOLDER))
            else -> Unit
        }
    }
    val isRepo = folderState == FolderState.REPO
    val ready = folderState in setOf(FolderState.MISSING, FolderState.EMPTY, FolderState.REPO)
    val enabled = blockers.isEmpty() && ready && !busy

    SectionCard(stringResource(R.string.setup_section_action), modifier) {
        if (blockers.isNotEmpty()) {
            Text(
                pluralStringResource(R.plurals.setup_things_left, blockers.size, blockers.size),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.sketch.warning,
            )
            Column {
                blockers.forEach { b -> BlockerRow(stringResource(b.text)) { onGoTo(b.section) } }
            }
        }
        val buttonContent: @Composable RowScope.() -> Unit = {
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                stringResource(
                    when {
                        isRepo && setupDone -> R.string.setup_relink
                        isRepo -> R.string.setup_use_existing
                        else -> R.string.setup_clone
                    },
                ),
            )
        }
        if (isRepo && setupDone) {
            OutlinedButton(onClick = onUseExisting, enabled = enabled, modifier = Modifier.fillMaxWidth(), content = buttonContent)
        } else {
            Button(
                onClick = if (isRepo) onUseExisting else onClone,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                content = buttonContent,
            )
        }
        cloneRun?.let { SetupRunSteps(it) }
        actionError?.let { SetupErrorBlock(it) }
    }
}

/** Tappable "what is missing" line; scrolls to the section that fixes it. */
@Composable
private fun BlockerRow(text: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = stringResource(R.string.setup_blocker_go), role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        Icon(
            Icons.Filled.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
