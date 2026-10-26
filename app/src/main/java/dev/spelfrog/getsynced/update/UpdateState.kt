package dev.spelfrog.getsynced.update

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Foreground-only update check with a 6 h throttle and a "skip this version" memory.
 * Process singleton so Activity recreation does not re-query GitHub.
 */
object UpdateState {
    private const val PREFS = "update"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_SKIPPED = "skipped_version"
    private const val MIN_INTERVAL_MS = 6 * 60 * 60 * 1000L

    private val _available = MutableStateFlow<ReleaseInfo?>(null)
    /** A newer, not skipped release, or null. */
    val available: StateFlow<ReleaseInfo?> = _available

    private val lock = Mutex()

    fun currentVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull().orEmpty()

    /** Called when the app comes to the foreground. Does nothing within 6 h of the last check unless [force]. */
    suspend fun checkIfDue(context: Context, force: Boolean = false) = withContext(Dispatchers.IO) {
        lock.withLock {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            if (!force && now - prefs.getLong(KEY_LAST_CHECK, 0L) < MIN_INTERVAL_MS) return@withLock
            val current = currentVersion(context)
            val latest = try {
                UpdateChecker().fetchLatest()
            } catch (e: Exception) {
                Log.d("UpdateCheck", "check failed: ${e.message}")
                return@withLock
            }
            prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
            val skipped = prefs.getString(KEY_SKIPPED, null)
            _available.value = latest.takeIf {
                current.isNotBlank() && UpdateChecker.isNewer(it.version, current) && it.version != skipped
            }
        }
    }

    fun skip(context: Context, release: ReleaseInfo) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SKIPPED, release.version).apply()
        _available.value = null
    }
}
