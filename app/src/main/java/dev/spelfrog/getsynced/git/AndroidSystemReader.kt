package dev.spelfrog.getsynced.git

import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader
import java.io.File

/**
 * JGit SystemReader that never looks at ~/.gitconfig, /etc/gitconfig or a git binary.
 * All three config levels live in [configDir] (an app-private directory on Android).
 * Everything else (time, env, properties) is delegated to the original reader.
 */
class AndroidSystemReader(
    private val delegate: SystemReader,
    val configDir: File,
) : SystemReader() {

    private fun configFile(name: String): File {
        configDir.mkdirs()
        val f = File(configDir, name)
        if (!f.exists()) f.createNewFile()
        return f
    }

    override fun openUserConfig(parent: Config?, fs: FS): FileBasedConfig =
        FileBasedConfig(parent, configFile("gitconfig"), fs)

    override fun openSystemConfig(parent: Config?, fs: FS): FileBasedConfig =
        FileBasedConfig(parent, configFile("gitconfig-system"), fs)

    override fun openJGitConfig(parent: Config?, fs: FS): FileBasedConfig =
        FileBasedConfig(parent, configFile("jgitconfig"), fs)

    /** The default does a (possibly network) hostname lookup; only used for fallback idents. */
    override fun getHostname(): String = "localhost"

    override fun getenv(variable: String?): String? = delegate.getenv(variable)
    override fun getProperty(key: String?): String? = delegate.getProperty(key)
    override fun getCurrentTime(): Long = delegate.currentTime
    override fun getTimezone(`when`: Long): Int = delegate.getTimezone(`when`)
}
