package dev.spelfrog.getsynced.sync

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Bounded sync log: one JSON-encoded [SyncRun] per line in filesDir/sync-history.jsonl.
 * Process singleton via [SyncHistory.get].
 */
class SyncHistory private constructor(context: Context) {
    companion object {
        private const val TAG = "SyncHistory"
        private const val FILE_NAME = "sync-history.jsonl"
        const val MAX_LINES = 200
        const val DEFAULT_LIMIT = 50

        private val json = Json { ignoreUnknownKeys = true }

        @Volatile private var instance: SyncHistory? = null

        fun get(context: Context): SyncHistory =
            instance ?: synchronized(this) {
                instance ?: SyncHistory(context.applicationContext).also { instance = it }
            }
    }

    private val file = File(context.filesDir, FILE_NAME)
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _runs = MutableStateFlow<List<SyncRun>>(emptyList())

    /** Newest first, at most [DEFAULT_LIMIT] runs. Loaded in the background on first access, refreshed on [append]. */
    val runs: StateFlow<List<SyncRun>> = _runs.asStateFlow()

    init {
        scope.launch { synchronized(lock) { _runs.value = parse(readLines(), DEFAULT_LIMIT) } }
    }

    /** Appends a finished run and trims the file to [MAX_LINES] lines. */
    suspend fun append(run: SyncRun): Unit = withContext(Dispatchers.IO) {
        synchronized(lock) {
            try {
                file.appendText(json.encodeToString(SyncRun.serializer(), run) + "\n")
                val lines = readLines()
                if (lines.size > MAX_LINES) rewrite(lines.takeLast(MAX_LINES))
                _runs.value = parse(lines, DEFAULT_LIMIT)
            } catch (e: Exception) {
                Log.w(TAG, "could not write history", e)
            }
        }
    }

    /** Newest first. */
    suspend fun load(limit: Int = DEFAULT_LIMIT): List<SyncRun> = withContext(Dispatchers.IO) {
        synchronized(lock) { parse(readLines(), limit) }
    }

    private fun readLines(): List<String> =
        try {
            if (file.exists()) file.readLines().filter { it.isNotBlank() } else emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "could not read history", e)
            emptyList()
        }

    private fun rewrite(lines: List<String>) {
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(lines.joinToString(separator = "\n", postfix = "\n"))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    private fun parse(lines: List<String>, limit: Int): List<SyncRun> =
        lines.takeLast(limit)
            .mapNotNull { line -> runCatching { json.decodeFromString(SyncRun.serializer(), line) }.getOrNull() }
            .distinctBy { it.id }
            .sortedByDescending { it.startedAt }
}
