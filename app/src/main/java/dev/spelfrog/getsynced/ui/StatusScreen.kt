package dev.spelfrog.getsynced.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.spelfrog.getsynced.git.CommitInfo
import dev.spelfrog.getsynced.R
import dev.spelfrog.getsynced.sync.StepState
import dev.spelfrog.getsynced.sync.SyncOutcome
import dev.spelfrog.getsynced.sync.SyncRun
import dev.spelfrog.getsynced.sync.SyncTrigger
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(onOpenSetup: () -> Unit, viewModel: StatusViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    NotificationPermissionRequest()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onOpenSetup) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.status_open_settings))
                    }
                },
            )
        },
    ) { padding ->
        if (!state.isLoaded) return@Scaffold
        val latest = state.latest
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 16.dp + padding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!state.isConfigured) {
                item(key = "setup") { NotSetUpCard(onOpenSetup) }
            }
            item(key = "header") { HeaderCard(state, latest) }
            if (state.isConfigured) {
                item(key = "sync") {
                    Button(
                        onClick = { SyncTrigger.enqueue(context, "manual") },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.status_sync_now)) }
                }
            }
            if (latest != null && latest.steps.isNotEmpty()) {
                item(key = "steps") {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        StepList(latest.steps, Modifier.padding(16.dp))
                    }
                }
            }
            if (latest != null && (latest.outcome == SyncOutcome.FAILED || latest.outcome == SyncOutcome.CONFLICT)) {
                val detail = latest.steps.lastOrNull { it.state == StepState.FAILED }?.detail
                    ?.takeIf { it.isNotBlank() } ?: latest.summary
                if (detail.isNotBlank()) item(key = "error") { ErrorBlock(detail) }
            }
            if (state.commits.isNotEmpty()) {
                item(key = "commits") { CommitsCard(state.commits) }
            }
            if (state.history.isNotEmpty()) {
                item(key = "history-title") {
                    Text(
                        stringResource(R.string.status_history),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(state.history, key = { "run-${it.id}" }) { run -> HistoryRow(run) }
            }
        }
    }
}

@Composable
private fun NotificationPermissionRequest() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted && !asked) {
            asked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun NotSetUpCard(onOpenSetup: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.status_not_set_up), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.status_not_set_up_text), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onOpenSetup) { Text(stringResource(R.string.status_open_setup)) }
        }
    }
}

@Composable
private fun HeaderCard(state: StatusUiState, run: SyncRun?) {
    val colors = MaterialTheme.colorScheme
    val (title, color) = when {
        state.waitingForNetwork -> stringResource(R.string.status_waiting_network) to colors.onSurfaceVariant
        run == null -> stringResource(R.string.status_never_synced) to colors.onSurfaceVariant
        else -> outcomeLabel(run.outcome) to outcomeColor(run.outcome)
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = color)
            if (run != null) {
                Text(
                    relativeTime(run.finishedAt ?: run.startedAt),
                    style = MaterialTheme.typography.bodyMedium.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                    color = colors.onSurfaceVariant,
                )
                if (run.summary.isNotBlank()) Text(run.summary, style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.status_trigger, run.trigger),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

/** The vault's last few commits: short sha, message, author and relative time. */
@Composable
private fun CommitsCard(commits: List<CommitInfo>) {
    val colors = MaterialTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.status_recent_commits), style = MaterialTheme.typography.titleMedium, color = colors.primary)
            commits.forEach { c ->
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        c.sha,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Normal,
                        ),
                        color = colors.secondary,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.message, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            stringResource(R.string.status_commit_by, c.author, relativeTime(c.timeMs)),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorBlock(detail: String) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            SelectionContainer {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { copyToClipboard(context, detail) },
                modifier = Modifier.align(Alignment.End),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer),
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.status_copy))
            }
        }
    }
}

@Composable
private fun HistoryRow(run: SyncRun) {
    var expanded by rememberSaveable(run.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = run.steps.isNotEmpty()) { expanded = !expanded }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutcomeBadge(run.outcome)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        relativeTime(run.startedAt) + " · " + run.trigger,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (run.summary.isNotBlank()) {
                    Text(
                        run.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = if (expanded) Int.MAX_VALUE else 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            if (run.steps.isNotEmpty()) {
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = stringResource(if (expanded) R.string.status_collapse else R.string.status_expand),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (expanded) StepList(run.steps, Modifier.padding(start = 8.dp, bottom = 8.dp))
        HorizontalDivider()
    }
}

@Composable
private fun OutcomeBadge(outcome: SyncOutcome) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (outcome) {
        SyncOutcome.OK -> colors.primaryContainer to colors.onPrimaryContainer
        SyncOutcome.FAILED -> colors.errorContainer to colors.onErrorContainer
        SyncOutcome.CONFLICT -> colors.tertiaryContainer to colors.onTertiaryContainer
        SyncOutcome.RUNNING -> colors.secondaryContainer to colors.onSecondaryContainer
    }
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small) {
        Text(
            outcomeLabel(outcome),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun outcomeLabel(outcome: SyncOutcome): String = stringResource(
    when (outcome) {
        SyncOutcome.OK -> R.string.status_synced
        SyncOutcome.FAILED -> R.string.status_failed
        SyncOutcome.CONFLICT -> R.string.status_conflict
        SyncOutcome.RUNNING -> R.string.status_syncing
    }
)

@Composable
private fun outcomeColor(outcome: SyncOutcome): Color = when (outcome) {
    SyncOutcome.OK -> MaterialTheme.colorScheme.primary
    SyncOutcome.FAILED -> MaterialTheme.colorScheme.error
    SyncOutcome.CONFLICT -> MaterialTheme.colorScheme.tertiary
    SyncOutcome.RUNNING -> MaterialTheme.colorScheme.onSurface
}

/** "2 min ago", refreshed every 30 s. */
@Composable
private fun relativeTime(time: Long): String {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    return if (now - time < DateUtils.MINUTE_IN_MILLIS) stringResource(R.string.status_just_now)
    else DateUtils.getRelativeTimeSpanString(time, now, DateUtils.MINUTE_IN_MILLIS).toString()
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("getSynced error", text))
    // Android 13+ shows its own clipboard confirmation.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, R.string.status_copied, Toast.LENGTH_SHORT).show()
    }
}
