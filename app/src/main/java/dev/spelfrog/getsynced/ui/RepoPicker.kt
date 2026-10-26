package dev.spelfrog.getsynced.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.spelfrog.getsynced.R
import dev.spelfrog.getsynced.auth.GitHubRepo

sealed class RepoListState {
    object Idle : RepoListState()
    object Loading : RepoListState()
    data class Loaded(val repos: List<GitHubRepo>) : RepoListState()
    data class Error(val text: String) : RepoListState()
}

/** Dialog listing the signed-in account's repositories; tapping one fills the remote URL. */
@Composable
internal fun RepoPickerDialog(
    state: RepoListState,
    onSelect: (GitHubRepo) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.setup_repo_picker_close)) } },
        title = { Text(stringResource(R.string.setup_repo_picker_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state) {
                    RepoListState.Idle, RepoListState.Loading -> Box(
                        Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                    is RepoListState.Error -> {
                        Text(state.text, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onRetry) { Text(stringResource(R.string.setup_repo_picker_retry)) }
                    }
                    is RepoListState.Loaded -> {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text(stringResource(R.string.setup_repo_picker_search)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        val shown = state.repos.filter { query.isBlank() || it.fullName.contains(query, ignoreCase = true) }
                        if (shown.isEmpty()) {
                            Text(stringResource(R.string.setup_repo_picker_empty), style = MaterialTheme.typography.bodyMedium)
                        }
                        LazyColumn(Modifier.heightIn(max = 420.dp)) {
                            items(shown, key = { it.fullName }) { repo ->
                                RepoRow(repo) { onSelect(repo) }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun RepoRow(repo: GitHubRepo, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            if (repo.isPrivate) Icons.Filled.Lock else Icons.Filled.Public,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Column {
            Text(repo.fullName, style = MaterialTheme.typography.bodyLarge)
            val sub = listOf(repo.defaultBranch, repo.description).filter { it.isNotBlank() }.joinToString(" · ")
            if (sub.isNotBlank()) {
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}
