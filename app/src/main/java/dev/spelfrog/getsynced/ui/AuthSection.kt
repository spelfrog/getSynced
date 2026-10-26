package dev.spelfrog.getsynced.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
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
    modifier: Modifier = Modifier,
) {
    SectionCard(stringResource(R.string.setup_section_auth), modifier) {
        val modes = listOf(
            AppSettings.AUTH_GITHUB to stringResource(R.string.setup_auth_github),
            AppSettings.AUTH_TOKEN to stringResource(R.string.setup_auth_token),
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            modes.forEachIndexed { i, (mode, label) ->
                val selected = form.authMode == mode
                SegmentedButton(
                    selected = selected,
                    onClick = { if (auth is GitHubAuthState.Idle) onEdit { it.copy(authMode = mode) } },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = modes.size),
                    border = SegmentedButtonDefaults.borderStroke(
                        if (selected) MaterialTheme.colorScheme.outline else MaterialTheme.sketch.cardBorder,
                    ),
                ) { Text(label) }
            }
        }
        if (form.usesGitHub) {
            GitHubAuth(form, auth, onSignIn, onCancel, onSignOut)
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
    onSignIn: () -> Unit,
    onCancel: () -> Unit,
    onSignOut: () -> Unit,
) {
    val context = LocalContext.current
    val signedIn = form.githubLogin.isNotBlank() && form.token.isNotBlank()
    when (auth) {
        is GitHubAuthState.Idle, is GitHubAuthState.Error -> {
            if (auth is GitHubAuthState.Error) {
                StatusLine(Icons.Filled.Warning, MaterialTheme.colorScheme.error, auth.text)
            }
            if (signedIn) {
                StatusLine(
                    Icons.Filled.CheckCircle,
                    MaterialTheme.sketch.success,
                    stringResource(R.string.setup_github_signed_in, form.githubLogin),
                )
                if (form.githubTokenExpiresAt > 0L) {
                    val left = form.githubTokenExpiresAt - System.currentTimeMillis()
                    Text(
                        stringResource(R.string.setup_github_token_expires, expiresInText(left)),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (left < DAY_MS) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalButton(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.setup_github_sign_in_again))
                }
                TextButton(
                    onClick = onSignOut,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.setup_github_sign_out)) }
            } else {
                Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.setup_github_sign_in))
                }
                Text(
                    stringResource(R.string.setup_github_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        GitHubAuthState.Requesting -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.setup_github_requesting), style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = onCancel) { Text(stringResource(R.string.setup_github_cancel)) }
        }
        is GitHubAuthState.WaitingForUser -> {
            var copied by remember { mutableStateOf(false) }
            LaunchedEffect(copied) {
                if (copied) {
                    delay(2_000)
                    copied = false
                }
            }
            Text(stringResource(R.string.setup_github_enter_code), style = MaterialTheme.typography.bodyMedium)
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                ) {
                    SelectionContainer(Modifier.weight(1f)) {
                        Text(
                            auth.userCode,
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 4.sp,
                            ),
                            softWrap = true,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    IconButton(onClick = { copyCode(context, auth.userCode); copied = true }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.setup_github_copy_code))
                    }
                }
            }
            if (copied) {
                Text(
                    stringResource(R.string.setup_github_code_copied),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.sketch.success,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Button(
                onClick = {
                    copyCode(context, auth.userCode)
                    copied = true
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(auth.verificationUri))) }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.setup_github_open)) }
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.setup_github_waiting, auth.verificationUri), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onCancel) { Text(stringResource(R.string.setup_github_cancel)) }
            }
        }
    }
}

private const val DAY_MS = 24L * 60 * 60 * 1000

/** English, locale-independent "in 25 min" / "in 7 h" / "in 3 days" (DateUtils would follow the device language). */
private fun expiresInText(leftMs: Long): String {
    if (leftMs <= 0L) return "now (expired)"
    val min = leftMs / 60_000L
    val hours = min / 60
    val days = hours / 24
    return when {
        min < 1 -> "in less than a minute"
        min < 60 -> "in $min min"
        hours < 48 -> "in $hours h"
        else -> "in $days days"
    }
}

private fun copyCode(context: Context, code: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("GitHub code", code))
}
