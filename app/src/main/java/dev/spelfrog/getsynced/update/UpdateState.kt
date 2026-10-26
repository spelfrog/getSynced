package dev.spelfrog.getsynced.update

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What the About footer shows: when we last asked GitHub, what it said, and whether an update is offered. */
data class UpdateInfo(
    val installedVersion: String = "",
    /** 0 = never checked. */
    val lastCheckAt: Long = 0L,
    /** Newest version seen on GitHub at the last successful check, "" if unknown. */
    val latestVersion: String = "",
    val checking: Boolean = false,
    /** Message of the last failed check (offline, rate limit), null when the last check succeeded. */
    val lastError: String? = null,
    /** Newer, not skipped release to offer, or null. */
    val available: ReleaseInfo? = null,
    val skippedVersion: String? = null,
)

/**
 * Foreground-only update check with a 6 h throttle and a "skip this version" memory.
 * Process singleton so Activity recreation does not re-query GitHub.
 */
object UpdateState {
    private const val PREFS = "update"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_LATEST = "latest_version"
    private const val KEY_SKIPPED = "skipped_version"
    private const val MIN_INTERVAL_MS = 6 * 60 * 60 * 1000L

    private val _info = MutableStateFlow(UpdateInfo())
    val info: StateFlow<UpdateInfo> = _info

    private val lock = Mutex()
    @Volatile private var loaded = false

    fun currentVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull().orEmpty()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Restores the last result from disk so the footer is right before any network call. */
    private fun loadOnce(context: Context) {
        if (loaded) return
        loaded = true
        val p = prefs(context)
        _info.update {
            it.copy(
                installedVersion = currentVersion(context),
                lastCheckAt = p.getLong(KEY_LAST_CHECK, 0L),
                latestVersion = p.getString(KEY_LATEST, "").orEmpty(),
                skippedVersion = p.getString(KEY_SKIPPED, null),
            )
        }
    }

    /** Called when the app comes to the foreground. Does nothing within 6 h of the last check unless [force]. */
    suspend fun checkIfDue(context: Context, force: Boolean = false) = withContext(Dispatchers.IO) {
        lock.withLock {
            loadOnce(context)
            val now = System.currentTimeMillis()
            if (!force && now - _info.value.lastCheckAt < MIN_INTERVAL_MS) return@withLock
            _info.update { it.copy(checking = true) }
            val current = currentVersion(context)
            val latest = try {
                UpdateChecker().fetchLatest()
            } catch (e: Exception) {
                Log.d("UpdateCheck", "check failed: ${e.message}")
                _info.update { it.copy(checking = false, lastError = e.message ?: e.javaClass.simpleName) }
                return@withLock
            }
            prefs(context).edit().putLong(KEY_LAST_CHECK, now).putString(KEY_LATEST, latest.version).apply()
            val skipped = _info.value.skippedVersion
            _info.update {
                it.copy(
                    installedVersion = current,
                    lastCheckAt = now,
                    latestVersion = latest.version,
                    checking = false,
                    lastError = null,
                    available = latest.takeIf { r ->
                        current.isNotBlank() && UpdateChecker.isNewer(r.version, current) && r.version != skipped
                    },
                )
            }
        }
    }

    fun skip(context: Context, release: ReleaseInfo) {
        prefs(context).edit().putString(KEY_SKIPPED, release.version).apply()
        _info.update { it.copy(available = null, skippedVersion = release.version) }
    }

    /** Forget the skipped version so the card can show again (used by "Check now"). */
    fun unskip(context: Context) {
        prefs(context).edit().remove(KEY_SKIPPED).apply()
        _info.update { it.copy(skippedVersion = null) }
    }
}
