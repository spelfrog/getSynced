package dev.spelfrog.getsynced.git

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GitRepoVersionsTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var git: Git
    private val ident = PersonIdent("Test", "test@example.com")

    @Before
    fun setUp() {
        GitSetup.init(tmp.newFolder("files"))
        dir = tmp.newFolder("repo")
        git = Git.init().setDirectory(dir).setInitialBranch("main").call()
    }

    @After
    fun tearDown() {
        git.close()
    }

    // ---- helpers ----

    private fun write(name: String, content: String) = File(dir, name).writeText(content)

    private fun commit(msg: String): String {
        git.add().addFilepattern(".").call()
        git.add().setUpdate(true).addFilepattern(".").call()
        return git.commit().setMessage(msg).setAuthor(ident).setCommitter(ident).call().name
    }

    private fun checkout(branch: String, create: Boolean = false) {
        git.checkout().setName(branch).setCreateBranch(create).call()
    }

    /** base commit on main, then [local] on main and [remote] on a side branch; returns (headSha, remoteSha). */
    private fun diverge(base: () -> Unit, local: () -> Unit, remote: () -> Unit): Pair<String, String> {
        base()
        commit("base")
        checkout("other", create = true)
        remote()
        val remoteSha = commit("remote")
        checkout("main")
        local()
        val headSha = commit("local")
        return headSha to remoteSha
    }

    private fun versions(path: String, shas: Pair<String, String>) =
        GitRepo(dir).use { it.conflictVersions(path, shas.first, shas.second) }

    // ---- tests ----

    @Test
    fun modifyModifyReturnsAllThreeTexts() {
        val shas = diverge(
            base = { write("a.md", "one\ntwo\n") },
            local = { write("a.md", "one\nlocal\n") },
            remote = { write("a.md", "one\nremote\n") },
        )
        // The working tree must not matter.
        write("a.md", "uncommitted junk\n")
        val v = versions("a.md", shas)
        assertEquals("one\ntwo\n", v.base)
        assertEquals("one\nlocal\n", v.local)
        assertEquals("one\nremote\n", v.remote)
        assertFalse(v.binary)
        assertFalse(v.truncated)
    }

    @Test
    fun deletedSidesAreNull() {
        val shas = diverge(
            base = { write("a.md", "base\n"); write("b.md", "bee\n") },
            local = { File(dir, "a.md").delete() },
            remote = { write("a.md", "remote\n"); File(dir, "b.md").delete() },
        )
        val a = versions("a.md", shas)
        assertEquals("base\n", a.base)
        assertNull(a.local)
        assertEquals("remote\n", a.remote)

        val b = versions("b.md", shas)
        assertEquals("bee\n", b.base)
        assertEquals("bee\n", b.local)
        assertNull(b.remote)
    }

    @Test
    fun addedOnBothSidesHasNoBase() {
        val shas = diverge(
            base = { write("x.md", "x\n") },
            local = { write("new.md", "mine\n") },
            remote = { write("new.md", "theirs\n") },
        )
        val v = versions("new.md", shas)
        assertNull(v.base)
        assertEquals("mine\n", v.local)
        assertEquals("theirs\n", v.remote)
    }

    @Test
    fun binaryBlobGivesBinaryFlagAndNoTexts() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D) +
            ByteArray(64) { it.toByte() }
        val shas = diverge(
            base = { File(dir, "img.png").writeBytes(png) },
            local = { File(dir, "img.png").writeBytes(png + byteArrayOf(1)) },
            remote = { File(dir, "img.png").writeBytes(png + byteArrayOf(2)) },
        )
        val v = versions("img.png", shas)
        assertTrue(v.binary)
        assertNull(v.base)
        assertNull(v.local)
        assertNull(v.remote)
    }

    @Test
    fun largeTextIsTruncatedAtCompleteLine() {
        val line = "a".repeat(99) + "\n" // 100 bytes
        val big = line.repeat(3000) // 300 KB
        val shas = diverge(
            base = { write("big.md", "small\n") },
            local = { write("big.md", big) },
            remote = { write("big.md", "small edit\n") },
        )
        val v = versions("big.md", shas)
        assertTrue(v.truncated)
        assertFalse(v.binary)
        val local = v.local!!
        assertTrue(local.length <= GitRepo.VERSION_CAP)
        assertTrue(local.length > GitRepo.VERSION_CAP - 100)
        assertTrue(local.endsWith("\n"))
        assertEquals("small edit\n", v.remote)
    }
}
