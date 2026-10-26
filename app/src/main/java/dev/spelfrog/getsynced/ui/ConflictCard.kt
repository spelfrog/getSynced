package dev.spelfrog.getsynced.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.spelfrog.getsynced.R
import dev.spelfrog.getsynced.sync.ConflictFile
import dev.spelfrog.getsynced.sync.ConflictInfo
import dev.spelfrog.getsynced.sync.ConflictKind
import dev.spelfrog.getsynced.sync.Side
import java.util.Locale

/** Saves the per-file choices as a flat [path, side, path, side, ...] list (Bundle-friendly). */
private val ChoicesSaver = Saver<Map<String, Side>, ArrayList<String>>(
    save = { map -> ArrayList(map.flatMap { (path, side) -> listOf(path, side.name) }) },
    restore = { list ->
        list.chunked(2)
            .mapNotNull { pair -> runCatching { pair[0] to Side.valueOf(pair[1]) }.getOrNull() }
            .toMap()
    },
)

/**
 * Light conflict resolution: one row per conflicting file with Phone | Remote | Both, then
 * "Resolve and sync". Choices live in the card, keyed by the remote commit they were made against.
 */
@Composable
fun ConflictCard(
    info: ConflictInfo,
    busy: Boolean,
    onResolve: (Map<String, Side>) -> Unit,
    onShowDiff: (ConflictFile) -> Unit,
) {
    val warning = MaterialTheme.sketch.warning
    var picked by rememberSaveable(info.remoteSha, stateSaver = ChoicesSaver) {
        mutableStateOf(info.files.associate { it.path to Side.LOCAL })
    }
    // Only choices that are valid for the current file list count (Both is not offered for delete kinds).
    val choices = info.files.mapNotNull { f ->
        picked[f.path]?.takeIf { it != Side.BOTH || f.canKeepBoth }?.let { f.path to it }
    }.toMap()
    val complete = info.files.isNotEmpty() && choices.size == info.files.size
    val enabled = !busy

    SketchCard(borderColor = warning) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.conflict_title),
                style = MaterialTheme.typography.titleMedium,
                color = warning,
            )
            Text(
                stringResource(R.string.conflict_history_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(
                    onClick = { picked = info.files.associate { it.path to Side.LOCAL } },
                    enabled = enabled,
                ) { Text(stringResource(R.string.conflict_keep_all_local)) }
                TextButton(
                    onClick = { picked = info.files.associate { it.path to Side.REMOTE } },
                    enabled = enabled,
                ) { Text(stringResource(R.string.conflict_keep_all_remote)) }
            }
            info.files.forEachIndexed { i, file ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.sketch.cardBorder)
                ConflictFileRow(
                    file = file,
                    selected = choices[file.path],
                    enabled = enabled,
                    onSelect = { side -> picked = picked + (file.path to side) },
                    onShowDiff = { onShowDiff(file) },
                )
            }
            Button(
                onClick = { onResolve(choices) },
                enabled = enabled && complete,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 4.dp),
            ) { Text(stringResource(R.string.conflict_resolve)) }
        }
    }
}

@Composable
private fun ConflictFileRow(
    file: ConflictFile,
    selected: Side?,
    enabled: Boolean,
    onSelect: (Side) -> Unit,
    onShowDiff: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val name = file.path.substringAfterLast('/')
    val folder = file.path.substringBeforeLast('/', missingDelimiterValue = "")
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (folder.isNotEmpty()) {
            Text(
                folder,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val details = buildList {
            add(stringResource(kindText(file.kind)))
            if (file.binary) add(stringResource(R.string.conflict_binary))
            val sizes = listOfNotNull(
                file.localSize?.let { stringResource(R.string.conflict_size_local, formatBytes(it)) },
                file.remoteSize?.let { stringResource(R.string.conflict_size_remote, formatBytes(it)) },
            )
            if (sizes.isNotEmpty()) add(sizes.joinToString(" · "))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(details.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (!file.binary) {
                val showCd = stringResource(R.string.diff_show_changes_cd, name)
                TextButton(
                    onClick = onShowDiff,
                    modifier = Modifier.semantics { contentDescription = showCd },
                ) { Text(stringResource(R.string.diff_show_changes)) }
            }
        }

        val options = buildList {
            add(Triple(Side.LOCAL, R.string.conflict_side_local, R.string.conflict_side_local_cd))
            add(Triple(Side.REMOTE, R.string.conflict_side_remote, R.string.conflict_side_remote_cd))
            if (file.canKeepBoth) add(Triple(Side.BOTH, R.string.conflict_side_both, R.string.conflict_side_both_cd))
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            options.forEachIndexed { i, (side, label, cd) ->
                val isSelected = selected == side
                val description = stringResource(cd, name)
                SegmentedButton(
                    selected = isSelected,
                    onClick = { onSelect(side) },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
                    border = SegmentedButtonDefaults.borderStroke(
                        if (isSelected) colors.outline else MaterialTheme.sketch.cardBorder,
                    ),
                    modifier = Modifier.semantics { contentDescription = description },
                ) { Text(stringResource(label), maxLines = 1) }
            }
        }
    }
}

internal fun kindText(kind: ConflictKind): Int = when (kind) {
    ConflictKind.BOTH_MODIFIED -> R.string.conflict_kind_both_modified
    ConflictKind.BOTH_ADDED -> R.string.conflict_kind_both_added
    ConflictKind.DELETED_LOCALLY -> R.string.conflict_kind_deleted_locally
    ConflictKind.DELETED_ON_REMOTE -> R.string.conflict_kind_deleted_on_remote
}

/** "812 B", "4.1 KB", "2.3 MB" (1024-based, one decimal). */
private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
}
