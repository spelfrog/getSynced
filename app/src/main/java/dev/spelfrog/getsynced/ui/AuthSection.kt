package dev.spelfrog.getsynced.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.spelfrog.getsynced.R
import dev.spelfrog.getsynced.settings.AppSettings

/** Authentication: personal access token, or "Sign in with GitHub" (device flow, no secret on the phone). */
@Composable
internal fun AuthSection(
    form: SetupForm,
    auth: GitHubAuthState,
    onEdit: FormEdit,
    onSignIn: () -> Unit,
    onCancel: () -> Unit,
    onSignOut: () -> Unit,
) {
    SectionCard(stringResource(R.string.setup_section_auth)) {
        val modes = listOf(
            AppSettings.AUTH_GITHUB to stringResource(R.string.setup_auth_github),
            AppSettings.AUTH_TOKEN to stringResource(R.string.setup_auth_token),
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            modes.forEachIndexed { i, (mode, label) ->
                SegmentedButton(
                    selected = form.authMode == mode,
                    onClick = { if (auth is GitHubAuthState.Idle) onEdit { it.copy(authMode = mode) } },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = modes.size),
                ) { Text(label) }
            }
        }
        if (form.usesGitHub) {
            GitHubAuth(form, auth, onEdit, onSignIn, onCancel, onSignOut)
        } else {
            TokenAuth(form, onEdit)
        }
    }
}

@Composable
private fun TokenAuth(form: SetupForm, onEdit: FormEdit) {
    OutlinedTextField(
        value = form.username,
        onValueChange = { v -> onEdit { it.copy(username = v) } },
        label = { Text(stringResource(R.string.setup_username)) },
        supportingText = { Text(stringResource(R.string.setup_username_help)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    var showToken by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = form.token,
        onValueChange = { v -> onEdit { it.copy(token = v) } },
        label = { Text(stringResource(R.string.setup_token)) },
        supportingText = { Text(stringResource(R.string.setup_token_help)) },
        singleLine = true,
        visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { showToken = !showToken }) {
                Icon(
                    if (showToken) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = stringResource(if (showToken) R.string.setup_token_hide else R.string.setup_token_show),
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun GitHubAuth(
    form: SetupForm,
    auth: GitHubAuthState,
    onEdit: FormEdit,
    onSignIn: () -> Unit,
    onCancel: () -> Unit,
    onSignOut: () -> Unit,
) {
    val context = LocalContext.current
    val signedIn = form.githubLogin.isNotBlank() && form.token.isNotBlank()
    OutlinedTextField(
        value = form.githubClientId,
        onValueChange = { v -> onEdit { it.copy(githubClientId = v) } },
        label = { Text(stringResource(R.string.setup_github_client_id)) },
        supportingText = { Text(stringResource(R.string.setup_github_client_id_help)) },
        singleLine = true,
        enabled = auth is GitHubAuthState.Idle || auth is GitHubAuthState.Error,
        modifier = Modifier.fillMaxWidth(),
    )
    when (auth) {
        is GitHubAuthState.Idle, is GitHubAuthState.Error -> {
            if (auth is GitHubAuthState.Error) {
                StatusLine(Icons.Filled.Warning, MaterialTheme.colorScheme.error, auth.text)
            }
            if (signedIn) {
                StatusLine(
                    Icons.Filled.CheckCircle,
                    MaterialTheme.colorScheme.primary,
                    stringResource(R.string.setup_github_signed_in, form.githubLogin),
                )
                if (form.githubTokenExpiresAt > 0L) {
                    val rel = DateUtils.getRelativeTimeSpanString(form.githubTokenExpiresAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                    Text(
                        stringResource(R.string.setup_github_token_expires, rel.toString()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onSignIn) { Text(stringResource(R.string.setup_github_sign_in_again)) }
                    TextButton(onClick = onSignOut) { Text(stringResource(R.string.setup_github_sign_out)) }
                }
            } else {
                Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.setup_github_sign_in))
                }
                Text(stringResource(R.string.setup_github_help), style = MaterialTheme.typography.bodySmall)
            }
        }
        GitHubAuthState.Requesting -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.setup_github_requesting))
            }
            TextButton(onClick = onCancel) { Text(stringResource(R.string.setup_github_cancel)) }
        }
        is GitHubAuthState.WaitingForUser -> {
            Text(stringResource(R.string.setup_github_enter_code))
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                SelectionContainer {
                    Text(
                        auth.userCode,
                        style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
            Button(
                onClick = {
                    copyCode(context, auth.userCode)
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(auth.verificationUri))) }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.setup_github_open)) }
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.setup_github_waiting, auth.verificationUri), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onCancel) { Text(stringResource(R.string.setup_github_cancel)) }
            }
        }
    }
}

private fun copyCode(context: Context, code: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("GitHub code", code))
}
