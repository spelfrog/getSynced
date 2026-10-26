package dev.spelfrog.getsynced.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.spelfrog.getsynced.R
import dev.spelfrog.getsynced.update.UpdateInfo

/** Version line and update-check status at the very bottom of the Status page. */
@Composable
fun AboutFooter(info: UpdateInfo, now: Long, onCheckNow: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        HorizontalDivider(color = colors.outlineVariant)
        Text(
            stringResource(R.string.about_version, info.installedVersion.ifBlank { "?" }),
            style = MaterialTheme.typography.labelLarge,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        val status = when {
            info.checking -> stringResource(R.string.about_checking)
            info.lastCheckAt == 0L && info.lastError != null -> stringResource(R.string.about_check_failed, info.lastError)
            info.lastCheckAt == 0L -> stringResource(R.string.about_never_checked)
            else -> {
                val when_ = relativeWithClock(now, info.lastCheckAt)
                val result = when {
                    info.lastError != null -> stringResource(R.string.about_check_failed, info.lastError)
                    info.available != null -> stringResource(R.string.about_update_available, info.available.tag)
                    info.skippedVersion != null && info.skippedVersion == info.latestVersion ->
                        stringResource(R.string.about_skipped, info.latestVersion)
                    info.latestVersion.isNotBlank() -> stringResource(R.string.about_up_to_date, info.latestVersion)
                    else -> ""
                }
                stringResource(R.string.about_last_check, when_) + (if (result.isNotBlank()) " · $result" else "")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (info.checking) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
            Text(
                status,
                style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        TextButton(onClick = onCheckNow, enabled = !info.checking) {
            Text(stringResource(R.string.about_check_now))
        }
    }
}
