package dev.spelfrog.getsynced.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.spelfrog.getsynced.R
import dev.spelfrog.getsynced.sync.ConflictFile
import dev.spelfrog.getsynced.sync.DiffLine
import dev.spelfrog.getsynced.sync.ThreeWay

/** What the diff sheet shows for one conflicting file. */
sealed class DiffState {
    object Loading : DiffState()
    object Binary : DiffState()
    /** At least one side was cut at the size cap; the diff covers the start of the file. */
    data class TooLarge(val diff: ThreeWay) : DiffState() {
        val truncated: Boolean get() = true
    }
    data class Ready(val diff: ThreeWay) : DiffState()
    data class Error(val text: String) : DiffState()
}

private enum class DiffTab(val label: Int) {
    LOCAL(R.string.diff_tab_local),
    REMOTE(R.string.diff_tab_remote),
    DIRECT(R.string.diff_tab_direct),
}

private const val TintAlpha = 0.15f

private val DiffTextStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp)

/** Full-height bottom sheet with the line diff of one conflicting file. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiffSheet(file: ConflictFile, state: DiffState, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val name = file.path.substringAfterLast('/')
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column {
                Text(
                    name,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val folder = file.path.substringBeforeLast('/', missingDelimiterValue = "")
                val sub = listOfNotNull(folder.takeIf { it.isNotEmpty() }, stringResource(kindText(file.kind)))
                Text(
                    sub.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (state) {
                DiffState.Loading -> Centered {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.diff_loading), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                DiffState.Binary -> Centered { Note(stringResource(R.string.diff_binary)) }
                is DiffState.Error -> Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.diff_error_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(state.text, style = DiffTextStyle)
                }
                is DiffState.TooLarge -> DiffBody(state.diff, truncated = state.truncated)
                is DiffState.Ready -> DiffBody(state.diff, truncated = false)
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun DiffBody(diff: ThreeWay, truncated: Boolean) {
    val tabs = if (diff.hasBase) DiffTab.entries else listOf(DiffTab.DIRECT)
    var selected by rememberSaveable(diff.hasBase) { mutableStateOf(tabs.first()) }
    if (selected !in tabs) selected = tabs.first()

    if (tabs.size > 1) {
        val colors = MaterialTheme.colorScheme
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            tabs.forEachIndexed { i, tab ->
                val isSelected = selected == tab
                SegmentedButton(
                    selected = isSelected,
                    onClick = { selected = tab },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = tabs.size),
                    border = SegmentedButtonDefaults.borderStroke(
                        if (isSelected) colors.outline else MaterialTheme.sketch.cardBorder,
                    ),
                    icon = {},
                ) {
                    Text(
                        stringResource(tab.label),
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                }
            }
        }
    }

    Legend(selected)
    if (truncated) {
        Text(
            stringResource(R.string.diff_truncated),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.sketch.warning,
        )
    }

    val deleted = when {
        diff.localDeleted && selected != DiffTab.REMOTE -> R.string.diff_deleted_locally
        diff.remoteDeleted && selected != DiffTab.LOCAL -> R.string.diff_deleted_on_remote
        else -> null
    }
    if (deleted != null) {
        Centered { Note(stringResource(deleted)) }
        return
    }
    val lines = when (selected) {
        DiffTab.LOCAL -> diff.localChanges
        DiffTab.REMOTE -> diff.remoteChanges
        DiffTab.DIRECT -> diff.direct
    }
    if (lines.none { it.kind != DiffLine.Kind.SAME }) {
        Centered { Note(stringResource(R.string.diff_no_changes)) }
        return
    }
    LazyColumn(
        Modifier.fillMaxWidth().fillMaxHeight(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        itemsIndexed(lines, key = { i, _ -> "${selected.name}-$i" }) { _, line -> DiffRow(line) }
    }
}

@Composable
private fun Legend(tab: DiffTab) {
    val added = MaterialTheme.sketch.success.copy(alpha = TintAlpha)
    val removed = MaterialTheme.colorScheme.error.copy(alpha = TintAlpha)
    val addedLabel = stringResource(R.string.diff_legend_added)
    val removedLabel = stringResource(R.string.diff_legend_removed)
    val explain = stringResource(
        when (tab) {
            DiffTab.LOCAL -> R.string.diff_legend_local
            DiffTab.REMOTE -> R.string.diff_legend_remote
            DiffTab.DIRECT -> R.string.diff_legend_direct
        },
    )
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = added)) { append(addedLabel) }
            append("  ")
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = removed)) { append(removedLabel) }
            append("  ")
            append(explain)
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DiffRow(line: DiffLine) {
    val colors = MaterialTheme.colorScheme
    val tint = when (line.kind) {
        DiffLine.Kind.ADDED -> MaterialTheme.sketch.success.copy(alpha = TintAlpha)
        DiffLine.Kind.REMOVED -> colors.error.copy(alpha = TintAlpha)
        DiffLine.Kind.SAME -> null
    }
    val (marker, markerCd) = when (line.kind) {
        DiffLine.Kind.ADDED -> "+" to stringResource(R.string.diff_added_line)
        DiffLine.Kind.REMOVED -> "−" to stringResource(R.string.diff_removed_line)
        DiffLine.Kind.SAME -> " " to null
    }
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (tint != null) Modifier.background(tint) else Modifier)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 4.dp),
    ) {
        Text(
            marker,
            style = DiffTextStyle,
            color = colors.onSurfaceVariant,
            modifier = Modifier
                .width(14.dp)
                .then(if (markerCd != null) Modifier.semantics { contentDescription = markerCd } else Modifier),
        )
        if (line.isCollapsed) {
            Text(
                pluralStringResource(R.plurals.diff_collapsed, line.hidden, line.hidden),
                style = DiffTextStyle.copy(fontStyle = FontStyle.Italic),
                color = colors.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(vertical = 2.dp),
            )
        } else {
            Text(
                line.text,
                style = DiffTextStyle,
                color = colors.onSurface,
                softWrap = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
