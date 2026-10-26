package dev.spelfrog.getsynced.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.spelfrog.getsynced.auth.GitHubDeviceFlow
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.security.KeyStore

data class AppSettings(
    val remoteUrl: String = "",
    /** HTTPS username; GitHub ignores it with a PAT, GitLab wants "oauth2". */
    val username: String = "oauth2",
    /** Absolute path of the vault, e.g. /storage/emulated/0/Documents/Obsidian. */
    val vaultPath: String = "",
    val authorName: String = "",
    val authorEmail: String = "",
    /** Empty = remote default / current branch. */
    val branch: String = "",
    /** Package name of the app to watch, e.g. md.obsidian. */
    val triggerPackage: String = "",
    val syncOnOpen: Boolean = true,
    val syncOnClose: Boolean = true,
    /** Show a toast when an automatic sync (open/close/intent) starts and ends. */
    val toastOnAutoSync: Boolean = true,
    /** True once a repo exists at vaultPath and the remote is configured. */
    val setupDone: Boolean = false,
    /** [AUTH_TOKEN] (personal access token + username) or [AUTH_GITHUB] (GitHub App / OAuth App device flow). */
    val authMode: String = AUTH_TOKEN,
    val githubClientId: String = GitHubDeviceFlow.DEFAULT_CLIENT_ID,
    /** GitHub login of the signed-in user, empty when not signed in. */
    val githubLogin: String = "",
    /** Epoch millis when the GitHub token expires, 0 = never (OAuth Apps, or expiration disabled). */
    val githubTokenExpiresAt: Long = 0L,
) {
    companion object {
        const val AUTH_TOKEN = "token"
        const val AUTH_GITHUB = "github"
    }

    val isConfigured: Boolean get() = setupDone && remoteUrl.isNotBlank() && vaultPath.isNotBlank()

    /** Username for git over HTTPS; GitHub tokens from the device flow use a fixed one. */
    val gitUsername: String get() = if (authMode == AUTH_GITHUB) GitHubDeviceFlow.GIT_USERNAME else username.ifBlank { "oauth2" }
}

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

private object Keys {
    val remoteUrl = stringPreferencesKey("remote_url")
    val username = stringPreferencesKey("username")
    val vaultPath = stringPreferencesKey("vault_path")
    val authorName = stringPreferencesKey("author_name")
    val authorEmail = stringPreferencesKey("author_email")
    val branch = stringPreferencesKey("branch")
    val triggerPackage = stringPreferencesKey("trigger_package")
    val syncOnOpen = booleanPreferencesKey("sync_on_open")
    val syncOnClose = booleanPreferencesKey("sync_on_close")
    val toastOnAutoSync = booleanPreferencesKey("toast_on_auto_sync")
    val setupDone = booleanPreferencesKey("setup_done")
    val authMode = stringPreferencesKey("auth_mode")
    val githubClientId = stringPreferencesKey("github_client_id")
    val githubLogin = stringPreferencesKey("github_login")
    val githubTokenExpiresAt = longPreferencesKey("github_token_expires_at")
}

private fun Preferences.toAppSettings(): AppSettings {
    val d = AppSettings()
    return AppSettings(
        remoteUrl = this[Keys.remoteUrl] ?: d.remoteUrl,
        username = this[Keys.username] ?: d.username,
        vaultPath = this[Keys.vaultPath] ?: d.vaultPath,
        authorName = this[Keys.authorName] ?: d.authorName,
        authorEmail = this[Keys.authorEmail] ?: d.authorEmail,
        branch = this[Keys.branch] ?: d.branch,
        triggerPackage = this[Keys.triggerPackage] ?: d.triggerPackage,
        syncOnOpen = this[Keys.syncOnOpen] ?: d.syncOnOpen,
        syncOnClose = this[Keys.syncOnClose] ?: d.syncOnClose,
        toastOnAutoSync = this[Keys.toastOnAutoSync] ?: d.toastOnAutoSync,
        setupDone = this[Keys.setupDone] ?: d.setupDone,
        authMode = this[Keys.authMode] ?: d.authMode,
        githubClientId = this[Keys.githubClientId] ?: d.githubClientId,
        githubLogin = this[Keys.githubLogin] ?: d.githubLogin,
        githubTokenExpiresAt = this[Keys.githubTokenExpiresAt] ?: d.githubTokenExpiresAt,
    )
}

private fun MutablePreferences.write(s: AppSettings) {
    this[Keys.remoteUrl] = s.remoteUrl
    this[Keys.username] = s.username
    this[Keys.vaultPath] = s.vaultPath
    this[Keys.authorName] = s.authorName
    this[Keys.authorEmail] = s.authorEmail
    this[Keys.branch] = s.branch
    this[Keys.triggerPackage] = s.triggerPackage
    this[Keys.syncOnOpen] = s.syncOnOpen
    this[Keys.syncOnClose] = s.syncOnClose
    this[Keys.toastOnAutoSync] = s.toastOnAutoSync
    this[Keys.setupDone] = s.setupDone
    this[Keys.authMode] = s.authMode
    this[Keys.githubClientId] = s.githubClientId
    this[Keys.githubLogin] = s.githubLogin
    this[Keys.githubTokenExpiresAt] = s.githubTokenExpiresAt
}

/** DataStore-backed settings. Single instance per process via [SettingsRepository.get]. */
class SettingsRepository private constructor(private val context: Context) {
    companion object {
        @Volatile private var instance: SettingsRepository? = null
        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) { instance ?: SettingsRepository(context.applicationContext).also { instance = it } }
    }

    val settings: Flow<AppSettings>
        get() = context.settingsDataStore.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .map { it.toAppSettings() }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsDataStore.edit { prefs ->
            val old = prefs.toAppSettings()
            val new = transform(old)
            if (new != old) prefs.write(new)
        }
    }
}

/** Token storage (EncryptedSharedPreferences). Blocking keystore work: create and use off the main thread. */
@Suppress("DEPRECATION")
class Secrets(context: Context) {
    private companion object {
        const val TAG = "Secrets"
        const val FILE = "secrets"
        const val KEY_TOKEN = "token"
        /** Default alias used by MasterKey.Builder. */
        const val MASTER_KEY_ALIAS = "_androidx_security_master_key_"
    }

    private val prefs: SharedPreferences = open(context.applicationContext)

    private fun create(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun open(context: Context): SharedPreferences = try {
        create(context)
    } catch (e: Exception) {
        // Corrupt keystore entry or prefs file (e.g. after a backup restore): start over, the token is lost.
        Log.w(TAG, "Encrypted prefs unreadable, recreating", e)
        context.deleteSharedPreferences(FILE)
        try {
            create(context)
        } catch (e2: Exception) {
            Log.w(TAG, "Still unreadable, deleting master key", e2)
            runCatching {
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(MASTER_KEY_ALIAS)
            }
            context.deleteSharedPreferences(FILE)
            create(context)
        }
    }

    fun getToken(): String = prefs.getString(KEY_TOKEN, null).orEmpty()

    fun setToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
    }
}
