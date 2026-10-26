package dev.spelfrog.getsynced.ui

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import dev.spelfrog.getsynced.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LaunchableApp(val packageName: String, val label: String, val icon: ImageBitmap?)

private fun Drawable.toImage(px: Int): ImageBitmap? =
    runCatching { toBitmap(px, px).asImageBitmap() }.getOrNull()

/** Launchable apps (MAIN/LAUNCHER, visible through the manifest <queries>), sorted by label. Blocking. */
@Suppress("DEPRECATION")
fun loadLaunchableApps(context: Context, iconPx: Int): List<LaunchableApp> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .distinctBy { it.activityInfo.packageName }
        .filter { it.activityInfo.packageName != context.packageName }
        .map { ri ->
            LaunchableApp(
                packageName = ri.activityInfo.packageName,
                label = ri.loadLabel(pm).toString(),
                icon = ri.loadIcon(pm)?.toImage(iconPx),
            )
        }
        .sortedBy { it.label.lowercase() }
}

/** Label and icon of one installed package, or null if it is not installed/visible. Blocking. */
@Suppress("DEPRECATION")
fun loadAppInfo(context: Context, packageName: String, iconPx: Int): LaunchableApp? = runCatching {
    val pm = context.packageManager
    val ai = pm.getApplicationInfo(packageName, 0)
    LaunchableApp(packageName, ai.loadLabel(pm).toString(), ai.loadIcon(pm)?.toImage(iconPx))
}.getOrNull()

@Composable
private fun iconPx(): Int = with(LocalDensity.current) { 40.dp.roundToPx() }

@Composable
private fun AppIcon(icon: ImageBitmap?) {
    if (icon != null) {
        Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(40.dp))
    } else {
        Icon(Icons.Filled.Android, contentDescription = null, modifier = Modifier.size(40.dp))
    }
}

/** Dialog listing launchable apps with a search field. */
@Composable
fun AppPickerDialog(selected: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val px = iconPx()
    val apps by produceState<List<LaunchableApp>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { loadLaunchableApps(context, px) }
    }
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(apps, query) {
        val q = query.trim()
        apps.orEmpty().filter {
            q.isEmpty() || it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true)
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.85f),
        ) {
            Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.setup_app_picker_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(R.string.setup_app_picker_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (apps == null) {
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(filtered, key = { it.packageName }) { app ->
                                AppListItem(app, app.packageName == selected) { onSelect(app.packageName) }
                            }
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).padding(horizontal = 16.dp)) {
                    Text(stringResource(R.string.setup_cancel))
                }
            }
        }
    }
}

@Composable
private fun AppListItem(app: LaunchableApp, isSelected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(app.label) },
        supportingContent = { Text(app.packageName, style = MaterialTheme.typography.bodySmall) },
        leadingContent = { AppIcon(app.icon) },
        trailingContent = if (isSelected) {
            { Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
        } else {
            null
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** "App to watch" row (opens the picker) plus the open/close switches. */
@Composable
internal fun TriggerSection(form: SetupForm, onEdit: FormEdit, modifier: Modifier = Modifier) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val px = iconPx()
    val app by produceState<LaunchableApp?>(initialValue = null, form.triggerPackage) {
        value = if (form.triggerPackage.isBlank()) null
        else withContext(Dispatchers.IO) { loadAppInfo(context, form.triggerPackage, px) }
    }

    SectionCard(stringResource(R.string.setup_section_trigger), modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(
                    onClickLabel = stringResource(R.string.setup_trigger_change_app),
                    role = Role.Button,
                ) { picking = true }
                .padding(vertical = 4.dp),
        ) {
            AppIcon(app?.icon)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.setup_trigger_app), style = MaterialTheme.typography.labelMedium)
                Text(
                    if (form.triggerPackage.isBlank()) {
                        stringResource(R.string.setup_trigger_none)
                    } else {
                        app?.label ?: form.triggerPackage
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null)
        }
        SwitchRow(stringResource(R.string.setup_sync_on_open), form.syncOnOpen) { v -> onEdit { it.copy(syncOnOpen = v) } }
        SwitchRow(stringResource(R.string.setup_sync_on_close), form.syncOnClose) { v -> onEdit { it.copy(syncOnClose = v) } }
        if (form.triggerPackage.isNotBlank()) {
            val name = app?.label ?: form.triggerPackage
            val hint = when {
                form.syncOnOpen && form.syncOnClose -> stringResource(R.string.setup_trigger_hint_both, name)
                form.syncOnOpen -> stringResource(R.string.setup_trigger_hint_open, name)
                form.syncOnClose -> stringResource(R.string.setup_trigger_hint_close, name)
                else -> stringResource(R.string.setup_trigger_hint_none, name)
            }
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (picking) {
        AppPickerDialog(
            selected = form.triggerPackage,
            onSelect = { pkg ->
                onEdit { it.copy(triggerPackage = pkg) }
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}
