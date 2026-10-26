package dev.spelfrog.getsynced.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.spelfrog.getsynced.R
import dev.spelfrog.getsynced.sync.StepState
import dev.spelfrog.getsynced.sync.SyncRun

/** Form edits are passed as transforms so each field only knows its own copy() call. */
internal typealias FormEdit = ((SetupForm) -> SetupForm) -> Unit

@Composable
internal fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun FormField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    supporting: String? = null,
    isError: Boolean = false,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
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

@Composable
internal fun RepositorySection(form: SetupForm, onEdit: FormEdit, onPickFromGitHub: (() -> Unit)? = null) {
    SectionCard(stringResource(R.string.setup_section_repository)) {
        if (onPickFromGitHub != null) {
            OutlinedButton(onClick = onPickFromGitHub, modifier = Modifier.fillMaxWidth()) {
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
        FormField(
            value = form.branch,
            onValueChange = { v -> onEdit { it.copy(branch = v) } },
            label = stringResource(R.string.setup_branch),
            supporting = stringResource(R.string.setup_branch_help),
        )
    }
}

@Composable
internal fun FolderSection(form: SetupForm, folderState: FolderState, existingRemote: String?, onEdit: FormEdit, onPick: () -> Unit) {
    SectionCard(stringResource(R.string.setup_section_folder)) {
        FormField(
            value = form.vaultPath,
            onValueChange = { v -> onEdit { it.copy(vaultPath = v) } },
            label = stringResource(R.string.setup_folder_path),
            placeholder = stringResource(R.string.setup_folder_placeholder),
            keyboardType = KeyboardType.Uri,
        )
        OutlinedButton(onClick = onPick) {
            Icon(Icons.Filled.FolderOpen, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.setup_folder_choose))
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
    val (icon, color, text) = when (state) {
        FolderState.UNSET -> Triple(Icons.Filled.Info, scheme.onSurfaceVariant, R.string.setup_folder_unset)
        FolderState.CHECKING -> Triple(Icons.Filled.Info, scheme.onSurfaceVariant, R.string.setup_folder_checking)
        FolderState.NO_ACCESS -> Triple(Icons.Filled.Warning, scheme.error, R.string.setup_folder_no_access)
        FolderState.NOT_A_FOLDER -> Triple(Icons.Filled.Error, scheme.error, R.string.setup_folder_not_a_folder)
        FolderState.MISSING -> Triple(Icons.Filled.Folder, scheme.onSurfaceVariant, R.string.setup_folder_missing)
        FolderState.EMPTY -> Triple(Icons.Filled.Folder, scheme.primary, R.string.setup_folder_empty)
        FolderState.HAS_FILES -> Triple(Icons.Filled.Error, scheme.error, R.string.setup_folder_has_files)
        FolderState.REPO -> Triple(Icons.Filled.CheckCircle, scheme.primary, R.string.setup_folder_repo)
    }
    StatusLine(icon, color, stringResource(text))
}

@Composable
internal fun StatusLine(icon: ImageVector, color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
internal fun AuthorSection(form: SetupForm, onEdit: FormEdit) {
    SectionCard(stringResource(R.string.setup_section_author)) {
        FormField(
            value = form.authorName,
            onValueChange = { v -> onEdit { it.copy(authorName = v) } },
            label = stringResource(R.string.setup_author_name),
        )
        FormField(
            value = form.authorEmail,
            onValueChange = { v -> onEdit { it.copy(authorEmail = v) } },
            label = stringResource(R.string.setup_author_email),
            supporting = stringResource(R.string.setup_author_help),
            keyboardType = KeyboardType.Email,
        )
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
