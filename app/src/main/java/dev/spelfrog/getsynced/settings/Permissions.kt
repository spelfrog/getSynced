package dev.spelfrog.getsynced.settings

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat

/** Status checks and deep links for the permissions the app needs. */
object Permissions {
    /** Fully qualified class of the accessibility service (implemented in trigger/, referenced by name only). */
    const val ACCESSIBILITY_SERVICE_CLASS = "dev.spelfrog.getsynced.trigger.AppWatchAccessibilityService"

    /** "All files access" (MANAGE_EXTERNAL_STORAGE). Below Android 11 the legacy WRITE_EXTERNAL_STORAGE runtime permission is used instead. */
    fun hasAllFilesAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    /** True when the storage grant is a runtime permission dialog (Android 8-10) rather than a settings screen. */
    val storageIsRuntimePermission: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.R

    fun allFilesAccessIntent(context: Context): Intent {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return appInfoIntent(context)
        val perApp = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
        return if (perApp.resolveActivity(context.packageManager) != null) {
            perApp
        } else {
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        }
    }

    fun hasNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** System page with this app's notification toggles (used after the runtime request was denied). */
    fun notificationSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context.packageName, ACCESSIBILITY_SERVICE_CLASS)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any { entry ->
            entry.isNotBlank() && (
                ComponentName.unflattenFromString(entry.trim()) == expected ||
                    entry.trim().equals(expected.flattenToString(), ignoreCase = true)
                )
        }
    }

    fun accessibilitySettingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    fun appInfoIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
}
