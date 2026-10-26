package dev.spelfrog.getsynced.sync

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The user's pending conflict choices, stored as filesDir/conflict-choices.json.
 *
 * A file rather than WorkManager input data: SyncTrigger.enqueue uses KEEP while a run is already enqueued,
 * which would drop a new request and its data. Whichever worker runs next reads this file.
 */
object ConflictChoices {
    private const val TAG = "ConflictChoices"
    private const val FILE_NAME = "conflict-choices.json"
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)

    /** The saved choices, or null when none are saved or the file is unreadable. */
    fun load(context: Context): Resolution? = synchronized(lock) {
        val f = file(context)
        if (!f.exists()) return null
        try {
            json.decodeFromString(Resolution.serializer(), f.readText())
        } catch (e: Exception) {
            Log.w(TAG, "could not read choices, ignoring them", e)
            null
        }
    }

    /** Replaces the saved choices atomically (tmp file + rename). */
    fun save(context: Context, r: Resolution): Unit = synchronized(lock) {
        val f = file(context)
        val tmp = File(f.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(json.encodeToString(Resolution.serializer(), r))
        if (!tmp.renameTo(f)) {
            f.writeText(tmp.readText())
            tmp.delete()
        }
    }

    fun clear(context: Context): Unit = synchronized(lock) {
        file(context).delete()
    }
}
