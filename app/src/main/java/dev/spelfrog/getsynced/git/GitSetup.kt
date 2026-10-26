package dev.spelfrog.getsynced.git

import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader
import java.io.File

/** One-time JGit environment setup for Android. Called from App.onCreate before any JGit use. */
object GitSetup {
    @Volatile
    private var original: SystemReader? = null

    /**
     * Points JGit's user/system/jgit config at files under [filesDir]/git, fixes user.home if it is
     * unset or "/", and initialises FS.DETECTED once. Idempotent; pure JVM (no android.* imports).
     */
    @Synchronized
    fun init(filesDir: File) {
        val home = System.getProperty("user.home")
        if (home.isNullOrBlank() || home == "/") {
            System.setProperty("user.home", filesDir.absolutePath)
        }
        val configDir = File(filesDir, "git")
        configDir.mkdirs()

        val current = SystemReader.getInstance()
        if (current is AndroidSystemReader && current.configDir == configDir) return
        val base = original ?: (if (current is AndroidSystemReader) null else current).also { original = it }
            ?: current
        SystemReader.setInstance(AndroidSystemReader(base, configDir))

        // Touch FS.DETECTED so detection happens once, here, and is reused everywhere.
        FS.DETECTED.userHome()
        // Do not block the calling thread on file-timestamp resolution probing.
        FS.FileStoreAttributes.setBackground(true)
    }
}
