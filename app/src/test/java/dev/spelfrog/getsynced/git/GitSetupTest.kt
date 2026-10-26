package dev.spelfrog.getsynced.git

import org.eclipse.jgit.util.SystemReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GitSetupTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun installsReaderWithConfigUnderFilesDir() {
        val files = tmp.newFolder("files")
        GitSetup.init(files)
        val reader = SystemReader.getInstance()
        assertTrue(reader is AndroidSystemReader)
        assertEquals(File(files, "git"), (reader as AndroidSystemReader).configDir)

        val user = reader.userConfig
        user.load()
        assertTrue(File(files, "git/gitconfig").exists())
        assertTrue(File(files, "git/gitconfig-system").exists())
        assertTrue(File(files, "git/jgitconfig").exists())
        assertTrue(reader.currentTime > 0)

        // Idempotent: same dir keeps the same instance.
        GitSetup.init(files)
        assertSame(reader, SystemReader.getInstance())
    }

    @Test
    fun fixesUserHomeWhenRoot() {
        val old = System.getProperty("user.home")
        try {
            System.setProperty("user.home", "/")
            val files = tmp.newFolder("files2")
            GitSetup.init(files)
            assertEquals(files.absolutePath, System.getProperty("user.home"))
        } finally {
            System.setProperty("user.home", old)
        }
    }
}
