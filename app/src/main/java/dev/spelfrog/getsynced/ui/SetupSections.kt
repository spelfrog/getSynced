package dev.spelfrog.getsynced.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.spelfrog.getsynced.R
import dev.spelfrog.getsynced.sync.StepState
import dev.spelfrog.getsynced.sync.SyncRun

/** Form edits are passed as transforms so each field only knows its own copy() call. */
internal typealias FormEdit = ((SetupForm) -> SetupForm) -> Unit

/** Field-sketch section: outlined box with a thin leader-line border and a reed-blue title. */
@Composable
internal fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.sketch.cardBorder),
        colors = CardDefaults.outlinedCardColors(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (title.isNotEmpty()) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            content()
        }
    }
}

@Composable
internal fun FormField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    supporting: String? = null,
    isError: Boolean = false,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    enabled: Boolean = true,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = if (placeholder != null) { { Text(placeholder) } } else null,
        supportingText = if (supporting != null) { { Text(supporting) } } else null,
        isError = isError,
        singleLine = true,
        enabled = enabled,
        visualTransformation = visualTransformation,
        trailingIcon = trailingIcon,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Next,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Remote URL (GitHub picker first when signed in) plus the folder-remote mismatch warning.
 * The sync's Check step writes the form URL into the folder's origin, so a silent mismatch would
 * re-point the vault at another repository.
 */
@Composable
internal fun RepositorySection(
    form: SetupForm,
    folderState: FolderState,
    existingRemote: String?,
    mismatchAccepted: Boolean,
    onEdit: FormEdit,
    onPickFromGitHub: (() -> Unit)?,
    onUseFolderRemote: () -> Unit,
    onKeepMine: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(stringResource(R.string.setup_section_repository), modifier) {
        if (onPickFromGitHub != null) {
            Button(onClick = onPickFromGitHub, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.List, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.setup_repo_pick_from_github))
            }
        }
        val problem = validateRemoteUrl(form.remoteUrl)
        FormField(
            value = form.remoteUrl,
            onValueChange = { v -> onEdit { it.copy(remoteUrl = v) } },
            label = stringResource(R.string.setup_remote_url),
            placeholder = stringResource(R.string.setup_remote_url_placeholder),
            supporting = when (problem) {
                UrlProblem.NOT_HTTPS -> stringResource(R.string.setup_remote_url_https_only)
                UrlProblem.INVALID -> stringResource(R.string.setup_remote_url_invalid)
                null -> stringResource(R.string.setup_remote_url_help)
            },
            isError = problem != null,
            keyboardType = KeyboardType.Uri,
        )
        val mismatch = folderState == FolderState.REPO &&
            !existingRemote.isNullOrBlank() &&
            remotesDiffer(existingRemote, form.remoteUrl) &&
            !mismatchAccepted
        if (mismatch) {
            StatusLine(
                Icons.Filled.Warning,
                MaterialTheme.sketch.warning,
                stringResource(R.string.setup_remote_mismatch, existingRemote.orEmpty()),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onUseFolderRemote,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.sketch.warning,
                        contentColor = MaterialTheme.sketch.onWarning,
                    ),
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.setup_remote_use_folder)) }
                OutlinedButton(onClick = onKeepMine, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.setup_remote_keep_mine))
                }
            }
        }
    }
}

/** Folder picker as the main action; the raw path is shown as text and only editable on request. */
@Composable
internal fun FolderSection(
    form: SetupForm,
    folderState: FolderState,
    existingRemote: String?,
    onEdit: FormEdit,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by rememberSaveable { mutableStateOf(form.vaultPath.isBlank()) }
    SectionCard(stringResource(R.string.setup_section_folder), modifier) {
        Button(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.FolderOpen, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.setup_folder_choose))
        }
        if (editing || form.vaultPath.isBlank()) {
            FormField(
                value = form.vaultPath,
                onValueChange = { v -> onEdit { it.copy(vaultPath = v) } },
                label = stringResource(R.string.setup_folder_path),
                placeholder = stringResource(R.string.setup_folder_placeholder),
                keyboardType = KeyboardType.Uri,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.setup_folder_path),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SelectionContainer {
                        Text(form.vaultPath, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                TextButton(onClick = { editing = true }) { Text(stringResource(R.string.setup_folder_edit_path)) }
            }
        }
        FolderStatusLine(folderState)
        if (folderState == FolderState.REPO) {
            Text(
                if (existingRemote.isNullOrBlank()) stringResource(R.string.setup_folder_repo_no_remote)
                else stringResource(R.string.setup_folder_repo_remote, existingRemote),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FolderStatusLine(state: FolderState) {
    val scheme = MaterialTheme.colorScheme
    val success = MaterialTheme.sketch.success
    val (icon, color, text) = when (state) {
        FolderState.UNSET -> Triple(Icons.Filled.Info, scheme.onSurfaceVariant, R.string.setup_folder_unset)
        FolderState.CHECKING -> Triple(Icons.Filled.Info, scheme.onSurfaceVariant, R.string.setup_folder_checking)
        FolderState.NO_ACCESS -> Triple(Icons.Filled.Warning, scheme.error, R.string.setup_folder_no_access)
        FolderState.NOT_A_FOLDER -> Triple(Icons.Filled.Error, scheme.error, R.string.setup_folder_not_a_folder)
        FolderState.MISSING -> Triple(Icons.Filled.Folder, scheme.onSurfaceVariant, R.string.setup_folder_missing)
        FolderState.EMPTY -> Triple(Icons.Filled.Folder, success, R.string.setup_folder_empty)
        FolderState.HAS_FILES -> Triple(Icons.Filled.Error, scheme.error, R.string.setup_folder_has_files)
        FolderState.REPO -> Triple(Icons.Filled.CheckCircle, success, R.string.setup_folder_repo)
    }
    StatusLine(icon, color, stringResource(text))
}

/** Icon plus wrapping caption; the icon sits on the first line. */
@Composable
internal fun StatusLine(icon: ImageVector, color: Color, text: String) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.padding(top = 1.dp).size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.weight(1f))
    }
}

/** Live clone steps (shared StepList), failed step shortened to its headline; full text selectable below. */
@Composable
internal fun SetupRunSteps(run: SyncRun) {
    val failed = run.steps.firstOrNull { it.state == StepState.FAILED }
    StepList(
        run.steps.map { s ->
            if (s.state == StepState.FAILED) s.copy(detail = s.detail.lineSequence().firstOrNull().orEmpty()) else s
        },
    )
    failed?.let { SetupErrorBlock(it.detail) }
}

/** Full error text, selectable, with a Copy button. */
@Composable
internal fun SetupErrorBlock(text: String) {
    val context = LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            SelectionContainer {
                Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            TextButton(onClick = { copyErrorText(context, text) }, modifier = Modifier.align(Alignment.End)) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.setup_copy))
            }
        }
    }
}

private fun copyErrorText(context: Context, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return
    cm.setPrimaryClip(ClipData.newPlainText("getSynced error", text))
}
