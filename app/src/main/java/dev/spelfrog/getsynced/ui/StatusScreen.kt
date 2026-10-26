package dev.spelfrog.getsynced.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.spelfrog.getsynced.update.ReleaseInfo
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.spelfrog.getsynced.R
import dev.spelfrog.getsynced.git.CommitInfo
import dev.spelfrog.getsynced.sync.ConflictFile
import dev.spelfrog.getsynced.sync.ConflictInfo
import dev.spelfrog.getsynced.sync.Side
import dev.spelfrog.getsynced.sync.StepState
import dev.spelfrog.getsynced.sync.SyncOutcome
import dev.spelfrog.getsynced.sync.SyncRun
import dev.spelfrog.getsynced.sync.SyncTrigger
import kotlinx.coroutines.delay

/** Error text longer than this is cut off behind "Show all". */
private const val ErrorMaxLines = 8

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(onOpenSetup: () -> Unit, viewModel: StatusViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Update check only while the app is open (ON_START), never from the background worker.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) viewModel.checkForUpdate()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Notification permission is asked for in the Setup checklist only.
    var diffFile by remember { mutableStateOf<ConflictFile?>(null) }
    val onShowDiff = { info: ConflictInfo, file: ConflictFile ->
        diffFile = file
        viewModel.showDiff(info, file)
    }
    diffFile?.let { file ->
        val diffState by viewModel.diffState.collectAsStateWithLifecycle()
        DiffSheet(file, diffState, onDismiss = {
            diffFile = null
            viewModel.clearDiff()
        })
    }

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
        // Relative times ("3 min ago") refresh twice a minute.
        val now = rememberNow(30_000L)
        val contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 16.dp + padding.calculateBottomPadding())
        val onSync = { SyncTrigger.enqueue(context, "manual") }
        BoxWithConstraints(
            Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (maxWidth >= TwoPaneMinWidth) {
                // Tablets and landscape: status and steps on the left, commits and history on the right.
                Row(
                    Modifier.widthIn(max = ContentMaxWidth * 2).fillMaxWidth().fillMaxHeight(),
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        contentPadding = contentPadding,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) { primaryItems(state, latest, now, onOpenSetup, onSync, viewModel::skipUpdate, viewModel::resolve, onShowDiff) }
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        contentPadding = contentPadding,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) { sideItems(state, now, commitLimit = 9, onCheckNow = viewModel::checkForUpdateNow) }
                }
            } else {
                LazyColumn(
                    // Phones fill the width (16 dp gutters); narrow tablets get a readable column.
                    modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth().fillMaxHeight(),
                    contentPadding = contentPadding,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    primaryItems(state, latest, now, onOpenSetup, onSync, viewModel::skipUpdate, viewModel::resolve, onShowDiff)
                    sideItems(state, now, commitLimit = 3, onCheckNow = viewModel::checkForUpdateNow)
                }
            }
        }
    }
}

/** Width from which the Status page splits into two columns. */
private val TwoPaneMinWidth = 840.dp

/** Status, error, Sync button and the steps of the latest run. */
private fun LazyListScope.primaryItems(
    state: StatusUiState,
    latest: SyncRun?,
    now: Long,
    onOpenSetup: () -> Unit,
    onSync: () -> Unit,
    onSkipUpdate: (ReleaseInfo) -> Unit,
    onResolve: (ConflictInfo, Map<String, Side>) -> Unit,
    onShowDiff: (ConflictInfo, ConflictFile) -> Unit,
) {
    state.update?.let { release ->
        item(key = "update") { UpdateCard(release, onSkip = { onSkipUpdate(release) }) }
    }
    if (!state.isConfigured) {
        item(key = "setup") { NotSetUpCard(onOpenSetup) }
    }
    item(key = "header") { HeaderCard(state, latest, now) }
    val conflict = latest?.takeIf { it.outcome == SyncOutcome.CONFLICT }?.conflict
    if (conflict != null) {
        // Stays visible (disabled) while a resolve run is queued or starting.
        item(key = "conflict") {
            ConflictCard(
                conflict,
                busy = state.busy,
                onResolve = { choices -> onResolve(conflict, choices) },
                onShowDiff = { file -> onShowDiff(conflict, file) },
            )
        }
    } else if (latest != null && (latest.outcome == SyncOutcome.FAILED || latest.outcome == SyncOutcome.CONFLICT)) {
        val failedStep = latest.steps.lastOrNull { it.state == StepState.FAILED }
        val detail = failedStep?.detail?.takeIf { it.isNotBlank() } ?: latest.summary
        if (detail.isNotBlank()) {
            item(key = "error") { ErrorBlock(failedStep?.name, detail) }
        }
    }
    if (state.isConfigured) {
        item(key = "sync") { SyncButton(state, onSync) }
    }
    if (latest != null && latest.steps.isNotEmpty()) {
        item(key = "steps") {
            SketchCard {
                StepList(latest.steps, Modifier.padding(16.dp))
            }
        }
    }
}

/** Recent commits and older runs: below the steps on a phone, the right column on a tablet. */
private fun LazyListScope.sideItems(state: StatusUiState, now: Long, commitLimit: Int, onCheckNow: () -> Unit) {
    if (state.commits.isNotEmpty()) {
        item(key = "commits") { CommitsCard(state.commits.take(commitLimit), now) }
    }
    val older = state.olderRuns
    if (older.isNotEmpty()) {
        item(key = "history") { HistoryCard(older, state, now) }
    }
    item(key = "about") { AboutFooter(state.updateInfo, now, onCheckNow) }
}

/** Current time in millis, refreshed every [intervalMs]. */
@Composable
private fun rememberNow(intervalMs: Long): Long {
    val now by produceState(System.currentTimeMillis(), intervalMs) {
        while (true) {
            value = System.currentTimeMillis()
            delay(intervalMs)
        }
    }
    return now
}

/** Card with the thin leader-line border of the field sketch. */
@Composable
internal fun SketchCard(
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Unspecified,
    borderColor: Color = MaterialTheme.sketch.cardBorder,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = if (containerColor == Color.Unspecified) CardDefaults.cardColors()
        else CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.dp, borderColor),
        content = content,
    )
}

@Composable
private fun NotSetUpCard(onOpenSetup: () -> Unit) {
    SketchCard(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.status_not_set_up), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.status_not_set_up_text), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onOpenSetup, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.status_open_setup))
            }
        }
    }
}

/**
 * The whole status at a glance: outcome; when and how long; what happened in plain words; on failure the
 * error headline and what to do; while running the current step and the elapsed time.
 */
@Composable
private fun HeaderCard(state: StatusUiState, run: SyncRun?, now: Long) {
    val colors = MaterialTheme.colorScheme
    val (title, color) = when (run) {
        null -> stringResource(R.string.status_never_synced) to colors.onSurfaceVariant
        else -> outcomeLabel(run.outcome) to outcomeColor(run.outcome)
    }
    val captionStyle = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic)
    SketchCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                color = color,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (run != null) {
                Text(relativeWithClock(now, run.finishedAt ?: run.startedAt), style = captionStyle, color = colors.onSurfaceVariant)
                when (run.outcome) {
                    SyncOutcome.RUNNING -> RunningLine(state, run)
                    SyncOutcome.OK -> Text(runSummary(run), style = MaterialTheme.typography.bodyLarge)
                    SyncOutcome.FAILED, SyncOutcome.CONFLICT -> {
                        val headline = firstLine(run.summary)
                        if (headline.isNotBlank()) {
                            Text(headline, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        }
                        WhatToDo(run)
                    }
                }
                Text(
                    state.triggerLabel(run.trigger),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                )
            }
            if (state.waitingForNetwork) {
                Text(stringResource(R.string.status_waiting_network_hint), style = captionStyle, color = colors.onSurfaceVariant)
            }
        }
    }
}

/** "Fetch… · 4.2 s", ticking. Elapsed time is that of the whole run. */
@Composable
private fun RunningLine(state: StatusUiState, run: SyncRun) {
    val now = rememberNow(250L)
    val elapsed = formatDuration((now - run.startedAt).coerceAtLeast(0))
    val step = state.runningStep
    Text(
        if (step != null) stringResource(R.string.status_running_step, step.name, elapsed)
        else stringResource(R.string.status_starting),
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun WhatToDo(run: SyncRun) {
    val text = stringResource(
        when {
            run.outcome == SyncOutcome.CONFLICT -> R.string.status_todo_conflict
            run.summary.startsWith("Auth failed") -> R.string.status_todo_auth
            else -> R.string.status_todo_failed
        }
    )
    Column(Modifier.padding(top = 4.dp)) {
        Text(
            stringResource(R.string.status_what_to_do),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** One line for a finished run: "Up to date · 3.0 s", "Sent 1 commit, received 2 commits · 1.2 s", or the error headline. */
@Composable
private fun runSummary(run: SyncRun): String {
    if (run.outcome != SyncOutcome.OK) return firstLine(run.summary)
    val counts = parseSyncCounts(run.summary) ?: return run.summary
    val parts = buildList {
        if (counts.pushed > 0) add(pluralStringResource(R.plurals.status_sent_commits, counts.pushed, counts.pushed))
        if (counts.pulled > 0) add(pluralStringResource(R.plurals.status_received_commits, counts.pulled, counts.pulled))
    }
    val what = if (parts.isEmpty()) stringResource(R.string.status_up_to_date)
    else parts.joinToString(", ").replaceFirstChar { it.uppercase() }
    val duration = run.finishedAt?.let { formatDuration((it - run.startedAt).coerceAtLeast(0)) }
    return listOfNotNull(what, duration).joinToString(" · ")
}

private fun firstLine(text: String): String = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

/** Never disappears: while busy it says why it is disabled. */
@Composable
private fun SyncButton(state: StatusUiState, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        colors = ButtonDefaults.buttonColors(
            disabledContainerColor = colors.surfaceContainerHighest,
            disabledContentColor = colors.onSurface,
        ),
    ) {
        if (state.busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = LocalContentColor.current,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(10.dp))
        }
        val step = state.runningStep
        val stepStart = step?.startedAt
        val label = when {
            state.isRunning && step != null && stepStart != null -> {
                val now = rememberNow(250L)
                stringResource(R.string.status_syncing_step, step.name, formatDuration((now - stepStart).coerceAtLeast(0)))
            }
            state.isRunning -> stringResource(R.string.status_syncing)
            state.waitingForNetwork -> stringResource(R.string.status_waiting_network)
            state.busy -> stringResource(R.string.status_starting)
            else -> stringResource(R.string.status_sync_now)
        }
        Text(label, textAlign = TextAlign.Center)
    }
}

/** The vault's last few commits: short sha, message, author and relative time. */
@Composable
private fun CommitsCard(commits: List<CommitInfo>, now: Long) {
    val colors = MaterialTheme.colorScheme
    SketchCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.status_recent_commits), style = MaterialTheme.typography.titleMedium, color = colors.primary)
            commits.forEach { c ->
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        c.sha,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontStyle = FontStyle.Normal,
                        ),
                        color = colors.secondary,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.message, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            stringResource(R.string.status_commit_by, c.author, relativeTime(now, c.timeMs)),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** Full error text: bold headline, monospace body capped at [ErrorMaxLines] with "Show all", Copy. */
@Composable
private fun ErrorBlock(stepName: String?, detail: String) {
    val context = LocalContext.current
    val onColor = MaterialTheme.colorScheme.onErrorContainer
    var expanded by rememberSaveable(detail) { mutableStateOf(false) }
    var overflows by remember(detail) { mutableStateOf(false) }
    SketchCard(
        containerColor = MaterialTheme.colorScheme.errorContainer,
        borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.5f),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (stepName != null) stringResource(R.string.status_step_failed, stepName)
                else stringResource(R.string.status_error_details),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = onColor,
            )
            Spacer(Modifier.height(6.dp))
            SelectionContainer {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                    ),
                    color = onColor,
                    maxLines = if (expanded) Int.MAX_VALUE else ErrorMaxLines,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (overflows || expanded) {
                    TextButton(
                        onClick = { expanded = !expanded },
                        colors = ButtonDefaults.textButtonColors(contentColor = onColor),
                    ) { Text(stringResource(if (expanded) R.string.status_show_less else R.string.status_show_more)) }
                }
                OutlinedButton(
                    onClick = { copyToClipboard(context, detail) },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = onColor),
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.status_copy))
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(runs: List<SyncRun>, state: StatusUiState, now: Long) {
    SketchCard {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
            Text(
                stringResource(R.string.status_history),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            runs.forEachIndexed { i, run ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.sketch.cardBorder)
                HistoryRow(run, state.triggerLabel(run.trigger), now)
            }
        }
    }
}

@Composable
private fun HistoryRow(run: SyncRun, triggerLabel: String, now: Long) {
    var expanded by rememberSaveable(run.id) { mutableStateOf(false) }
    val hasSteps = run.steps.isNotEmpty()
    val stateText = stringResource(if (expanded) R.string.status_expanded else R.string.status_collapsed)
    val actionLabel = stringResource(if (expanded) R.string.status_collapse else R.string.status_expand)
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (hasSteps) {
                        Modifier
                            .semantics { stateDescription = stateText }
                            .clickable(role = Role.Button, onClickLabel = actionLabel) { expanded = !expanded }
                    } else Modifier
                )
                .heightIn(min = 48.dp)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutcomeBadge(run.outcome)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        relativeWithClock(now, run.startedAt) + " · " + triggerLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
                val summary = runSummary(run)
                if (summary.isNotBlank()) {
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = if (expanded) Int.MAX_VALUE else 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            if (hasSteps) {
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (expanded) StepList(run.steps, Modifier.padding(start = 8.dp, bottom = 8.dp))
    }
}

@Composable
private fun OutcomeBadge(outcome: SyncOutcome) {
    val colors = MaterialTheme.colorScheme
    val sketch = MaterialTheme.sketch
    val (container, content) = when (outcome) {
        SyncOutcome.OK -> colors.primaryContainer to colors.onPrimaryContainer
        SyncOutcome.FAILED -> colors.errorContainer to colors.onErrorContainer
        SyncOutcome.CONFLICT -> sketch.warning to sketch.onWarning
        SyncOutcome.RUNNING -> colors.secondaryContainer to colors.onSecondaryContainer
    }
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small) {
        Text(
            outcomeLabel(outcome),
            style = MaterialTheme.typography.labelSmall.copy(fontStyle = FontStyle.Normal),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
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
    SyncOutcome.OK -> MaterialTheme.sketch.success
    SyncOutcome.FAILED -> MaterialTheme.colorScheme.error
    SyncOutcome.CONFLICT -> MaterialTheme.sketch.warning
    SyncOutcome.RUNNING -> MaterialTheme.colorScheme.onSurface
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("getSynced error", text))
    // Android 13+ shows its own clipboard confirmation.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, R.string.status_copied, Toast.LENGTH_SHORT).show()
    }
}
