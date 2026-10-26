package dev.spelfrog.getsynced.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.spelfrog.getsynced.R

/** Collapsed card for rarely changed settings: commit author, branch, GitHub App Client ID. */
@Composable
internal fun SetupAdvancedSection(
    form: SetupForm,
    auth: GitHubAuthState,
    onEdit: FormEdit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val stateText = stringResource(if (expanded) R.string.setup_expanded else R.string.setup_collapsed)
    val clickLabel = stringResource(if (expanded) R.string.setup_collapse else R.string.setup_expand)
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.sketch.cardBorder),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(onClickLabel = clickLabel, role = Role.Button) { expanded = !expanded }
                .semantics { stateDescription = stateText }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.setup_section_advanced),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (!expanded) {
                    Text(
                        stringResource(R.string.setup_advanced_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
        }
        if (expanded) {
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SubHeader(stringResource(R.string.setup_section_author))
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
                HorizontalDivider(color = MaterialTheme.sketch.cardBorder)
                FormField(
                    value = form.branch,
                    onValueChange = { v -> onEdit { it.copy(branch = v) } },
                    label = stringResource(R.string.setup_branch),
                    supporting = stringResource(R.string.setup_branch_help),
                )
                if (form.usesGitHub) {
                    HorizontalDivider(color = MaterialTheme.sketch.cardBorder)
                    FormField(
                        value = form.githubClientId,
                        onValueChange = { v -> onEdit { it.copy(githubClientId = v) } },
                        label = stringResource(R.string.setup_github_client_id),
                        supporting = stringResource(R.string.setup_github_client_id_help),
                        enabled = auth is GitHubAuthState.Idle || auth is GitHubAuthState.Error,
                    )
                }
            }
        }
    }
}

@Composable
private fun SubHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
}
