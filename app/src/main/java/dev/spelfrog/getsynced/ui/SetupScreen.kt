package dev.spelfrog.getsynced.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.spelfrog.getsynced.R
import dev.spelfrog.getsynced.settings.Permissions
import dev.spelfrog.getsynced.sync.SyncRun

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setup_title)) },
                actions = {
                    if (setupDone && !busy) {
                        TextButton(onClick = { viewModel.saveNow(); onDone() }) {
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
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                PermissionsSection(perms) { perms = PermissionStatus.read(context) }
                RepositorySection(
                    form = form,
                    onEdit = viewModel::edit,
                    onPickFromGitHub = if (form.usesGitHub && form.githubLogin.isNotBlank() && form.token.isNotBlank()) {
                        { viewModel.loadRepos(); showRepoPicker = true }
                    } else null,
                )
                AuthSection(
                    form = form,
                    auth = githubAuth,
                    onEdit = viewModel::edit,
                    onSignIn = viewModel::startGitHubSignIn,
                    onCancel = viewModel::cancelGitHubSignIn,
                    onSignOut = viewModel::signOutGitHub,
                )
                FolderSection(form, folderState, existingRemote, viewModel::edit) { folderPicker.launch(null) }
                AuthorSection(form, viewModel::edit)
                TriggerSection(form, viewModel::edit)
                ActionSection(
                    form = form,
                    folderState = folderState,
                    hasAllFiles = perms.allFiles,
                    cloneRun = cloneRun,
                    busy = busy,
                    actionError = actionError,
                    onClone = viewModel::cloneIntoFolder,
                    onUseExisting = viewModel::useExistingRepository,
                )
            }
        }
    }
}

@Composable
private fun PermissionsSection(status: PermissionStatus, onChanged: () -> Unit) {
    val context = LocalContext.current
    var notifAsked by rememberSaveable { mutableStateOf(false) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notifAsked = true
        onChanged()
    }
    val storageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onChanged() }
    SectionCard(stringResource(R.string.setup_section_permissions)) {
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
    }
}

@Composable
private fun PermissionRow(granted: Boolean, label: String, why: String, onOpen: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(
            if (granted) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
            contentDescription = stringResource(if (granted) R.string.setup_granted else R.string.setup_not_granted),
            tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
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
    cloneRun: SyncRun?,
    busy: Boolean,
    actionError: String?,
    onClone: () -> Unit,
    onUseExisting: () -> Unit,
) {
    val blockers = buildList {
        if (form.remoteUrl.isBlank() || validateRemoteUrl(form.remoteUrl) != null) add(R.string.setup_need_url)
        if (form.token.isBlank()) add(R.string.setup_need_token)
        if (form.vaultPath.isBlank()) add(R.string.setup_need_folder)
        if (!hasAllFiles) add(R.string.setup_need_files_access)
        when (folderState) {
            FolderState.HAS_FILES -> add(R.string.setup_need_clean_folder)
            FolderState.NOT_A_FOLDER -> add(R.string.setup_need_valid_folder)
            else -> Unit
        }
    }
    val isRepo = folderState == FolderState.REPO
    val ready = folderState in setOf(FolderState.MISSING, FolderState.EMPTY, FolderState.REPO)

    SectionCard(stringResource(R.string.setup_section_action)) {
        Button(
            onClick = if (isRepo) onUseExisting else onClone,
            enabled = blockers.isEmpty() && ready && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(stringResource(if (isRepo) R.string.setup_use_existing else R.string.setup_clone))
        }
        blockers.forEach { res ->
            StatusLine(Icons.Filled.Info, MaterialTheme.colorScheme.onSurfaceVariant, stringResource(res))
        }
        cloneRun?.let { SetupRunSteps(it) }
        actionError?.let { SetupErrorBlock(it) }
    }
}
