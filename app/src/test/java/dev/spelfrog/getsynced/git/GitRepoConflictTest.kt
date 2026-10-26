package dev.spelfrog.getsynced.git

import dev.spelfrog.getsynced.sync.ConflictKind
import dev.spelfrog.getsynced.sync.Side
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.revwalk.RevCommit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GitRepoConflictTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var remoteUrl: String
    private lateinit var desktop: Git
    private lateinit var phoneDir: File
    private lateinit var phone: GitRepo
    private val creds = Credentials("dummy", "dummy-token")
    private val ident = PersonIdent("Desktop", "desktop@example.com")
    private val msg = "Merge origin (resolved on testphone)"

    private val base = "line 1\nline 2\nline 3\n"
    private val phoneA = "line 1\nphone 2\nline 3\n"
    private val deskA = "line 1\ndesk 2\nline 3\n"

    @Before
    fun setUp() {
        GitSetup.init(tmp.newFolder("files"))
        val bare = tmp.newFolder("remote.git")
        Git.init().setBare(true).setDirectory(bare).call().close()
        remoteUrl = bare.toURI().toString()
        desktop = Git.cloneRepository().setURI(remoteUrl).setDirectory(tmp.newFolder("desktop")).call()
        desktopWrite("a.md" to base, "b.md" to "bee\n")
        desktopCommitPush()
        phoneDir = File(tmp.root, "phone")
        phone = GitRepo.clone(phoneDir, remoteUrl, null, creds)
    }

    @After
    fun tearDown() {
        phone.close()
        desktop.close()
    }

    // ---- helpers ----

    private fun desktopWrite(vararg files: Pair<String, String>) {
        files.forEach { (name, content) -> File(desktop.repository.workTree, name).writeText(content) }
    }

    private fun desktopCommitPush() {
        desktop.add().addFilepattern(".").call()
        desktop.add().setUpdate(true).addFilepattern(".").call()
        desktop.commit().setMessage("desktop edit").setAuthor(ident).setCommitter(ident).call()
        desktop.push().call()
    }

    private fun desktopPull() {
        val r = desktop.pull().call()
        assertTrue(r.toString(), r.isSuccessful)
    }

    private fun desktopFile(name: String): File = File(desktop.repository.workTree, name)

    private fun phoneFile(name: String) = File(phoneDir, name)

    private fun phoneCommit(vararg files: Pair<String, String>) {
        files.forEach { (name, content) -> phoneFile(name).writeText(content) }
        assertTrue(phone.commitAll({ "phone edit" }, "Phone", "phone@example.com") != null)
    }

    private fun phoneDelete(name: String) {
        assertTrue(phoneFile(name).delete())
        assertTrue(phone.commitAll({ "phone delete" }, "Phone", "phone@example.com") != null)
    }

    private fun <T> phoneGit(block: (Git) -> T): T = Git.open(phoneDir).use(block)

    private fun phoneHead(): String = phoneGit { it.repository.resolve(Constants.HEAD).name }

    private fun phoneHeadCommit(): RevCommit = phoneGit { g -> g.log().setMaxCount(1).call().first() }

    private fun phoneState(): RepositoryState = phoneGit { it.repository.repositoryState }

    private fun phoneClean(): Boolean = phoneGit { it.status().call().isClean }

    private fun resolve(vararg choices: Pair<String, Side>): MergeOutcome =
        phone.mergeResolving(choices.toMap(), "Phone", "phone@example.com", msg)

    /** Typical setup: a.md edited differently on both sides, desktop pushed, phone fetched. */
    private fun conflictOnA() {
        desktopWrite("a.md" to deskA)
        desktopCommitPush()
        phoneCommit("a.md" to phoneA)
        phone.fetch(creds)
    }

    private fun assertMergeCommitPushed() {
        val head = phoneHeadCommit()
        assertEquals(2, head.parentCount)
        assertEquals(msg, head.fullMessage)
        assertEquals(RepositoryState.SAFE, phoneState())
        assertTrue(phoneClean())
        val push = phone.push(creds)
        assertTrue(push.toString(), push is PushOutcome.Pushed)
        desktopPull()
        assertEquals(head.name, desktop.repository.resolve(Constants.HEAD).name)
    }

    // ---- tests ----

    @Test
    fun conflictDetectedWithoutTouchingWorktree() {
        conflictOnA()
        val headBefore = phoneHead()
        val out = phone.merge("Phone", "phone@example.com")
        assertTrue(out.toString(), out is MergeOutcome.Conflict)
        out as MergeOutcome.Conflict
        assertEquals(listOf("a.md"), out.paths)
        val f = out.files.single()
        assertEquals(ConflictKind.BOTH_MODIFIED, f.kind)
        assertFalse(f.binary)
        assertEquals(phoneA.length.toLong(), f.phoneSize)
        assertEquals(deskA.length.toLong(), f.remoteSize)
        assertEquals(headBefore, out.headSha)
        assertEquals(desktop.repository.resolve(Constants.HEAD).name, out.remoteSha)
        assertEquals(phoneA, phoneFile("a.md").readText())
        assertEquals(RepositoryState.SAFE, phoneState())
        assertTrue(phoneClean())
        assertEquals(headBefore, phoneHead())
        // Reset stays harmless.
        phone.resetHardToHead()
        assertEquals(phoneA, phoneFile("a.md").readText())
        // Default-identity merge detects it too.
        assertTrue(phone.merge() is MergeOutcome.Conflict)
    }

    @Test
    fun keepPhone() {
        conflictOnA()
        val out = resolve("a.md" to Side.PHONE)
        assertEquals(MergeOutcome.Merged(1), out)
        assertEquals(phoneA, phoneFile("a.md").readText())
        assertMergeCommitPushed()
        assertEquals(phoneA, desktopFile("a.md").readText())
        assertEquals(MergeOutcome.AlreadyUpToDate, phone.merge("Phone", "phone@example.com"))
    }

    @Test
    fun keepRemote() {
        conflictOnA()
        val phoneCommit = phoneHead()
        assertEquals(MergeOutcome.Merged(1), resolve("a.md" to Side.REMOTE))
        assertEquals(deskA, phoneFile("a.md").readText())
        assertEquals(phoneCommit, phoneHeadCommit().getParent(0).name)
        assertMergeCommitPushed()
        assertEquals(deskA, desktopFile("a.md").readText())
    }

    @Test
    fun mixedChoices() {
        desktopWrite("a.md" to deskA, "b.md" to "desk bee\n")
        desktopCommitPush()
        phoneCommit("a.md" to phoneA, "b.md" to "phone bee\n")
        phone.fetch(creds)
        val c = phone.merge() as MergeOutcome.Conflict
        assertEquals(listOf("a.md", "b.md"), c.paths)
        assertEquals(MergeOutcome.Merged(1), resolve("a.md" to Side.PHONE, "b.md" to Side.REMOTE))
        assertEquals(phoneA, phoneFile("a.md").readText())
        assertEquals("desk bee\n", phoneFile("b.md").readText())
        assertMergeCommitPushed()
        assertEquals(phoneA, desktopFile("a.md").readText())
        assertEquals("desk bee\n", desktopFile("b.md").readText())
    }

    private fun deletedOnPhone() {
        desktopWrite("a.md" to deskA)
        desktopCommitPush()
        phoneDelete("a.md")
        phone.fetch(creds)
        val c = phone.merge() as MergeOutcome.Conflict
        val f = c.files.single()
        assertEquals(ConflictKind.DELETED_ON_PHONE, f.kind)
        assertEquals(null, f.phoneSize)
        assertEquals(deskA.length.toLong(), f.remoteSize)
        assertFalse(f.canKeepBoth)
        assertFalse(phoneFile("a.md").exists())
    }

    private fun deletedOnRemote() {
        File(desktop.repository.workTree, "a.md").delete()
        desktopCommitPush()
        phoneCommit("a.md" to phoneA)
        phone.fetch(creds)
        val c = phone.merge() as MergeOutcome.Conflict
        val f = c.files.single()
        assertEquals(ConflictKind.DELETED_ON_REMOTE, f.kind)
        assertEquals(phoneA.length.toLong(), f.phoneSize)
        assertEquals(null, f.remoteSize)
        assertEquals(phoneA, phoneFile("a.md").readText())
    }

    @Test
    fun deletedOnPhoneKeepPhone() {
        deletedOnPhone()
        assertEquals(MergeOutcome.Merged(1), resolve("a.md" to Side.PHONE))
        assertFalse(phoneFile("a.md").exists())
        assertMergeCommitPushed()
        assertFalse(desktopFile("a.md").exists())
    }

    @Test
    fun deletedOnPhoneKeepRemote() {
        deletedOnPhone()
        assertEquals(MergeOutcome.Merged(1), resolve("a.md" to Side.REMOTE))
        assertEquals(deskA, phoneFile("a.md").readText())
        assertMergeCommitPushed()
        assertEquals(deskA, desktopFile("a.md").readText())
    }

    @Test
    fun deletedOnRemoteKeepPhone() {
        deletedOnRemote()
        assertEquals(MergeOutcome.Merged(1), resolve("a.md" to Side.PHONE))
        assertEquals(phoneA, phoneFile("a.md").readText())
        assertMergeCommitPushed()
        assertEquals(phoneA, desktopFile("a.md").readText())
    }

    @Test
    fun deletedOnRemoteKeepRemote() {
        deletedOnRemote()
        assertEquals(MergeOutcome.Merged(1), resolve("a.md" to Side.REMOTE))
        assertFalse(phoneFile("a.md").exists())
        assertMergeCommitPushed()
        assertFalse(desktopFile("a.md").exists())
    }

    @Test
    fun deletedOnRemoteBothKeepsTheExistingSide() {
        deletedOnRemote()
        assertEquals(MergeOutcome.Merged(1), resolve("a.md" to Side.BOTH))
        assertEquals(phoneA, phoneFile("a.md").readText())
        assertFalse(phoneFile("a (phone).md").exists())
        assertMergeCommitPushed()
    }

    @Test
    fun binaryKeepRemote() {
        val start = ByteArray(3000) { (it % 7).toByte() }
        File(desktop.repository.workTree, "img.png").writeBytes(start)
        desktopCommitPush()
        phone.fetch(creds)
        assertTrue(phone.merge() is MergeOutcome.FastForward)
        val desk = start.copyOf().also { it[10] = 99; it[2000] = 42 }
        val mine = start.copyOf(2500).also { it[10] = 77 }
        File(desktop.repository.workTree, "img.png").writeBytes(desk)
        desktopCommitPush()
        phoneFile("img.png").writeBytes(mine)
        assertTrue(phone.commitAll({ "phone img" }, "Phone", "phone@example.com") != null)
        phone.fetch(creds)
        val c = phone.merge() as MergeOutcome.Conflict
        val f = c.files.single()
        assertEquals("img.png", f.path)
        assertTrue(f.binary)
        assertEquals(ConflictKind.BOTH_MODIFIED, f.kind)
        assertEquals(2500L, f.phoneSize)
        assertEquals(3000L, f.remoteSize)
        assertArrayEquals(mine, phoneFile("img.png").readBytes())
        assertEquals(MergeOutcome.Merged(1), resolve("img.png" to Side.REMOTE))
        assertArrayEquals(desk, phoneFile("img.png").readBytes())
        assertMergeCommitPushed()
        assertArrayEquals(desk, desktopFile("img.png").readBytes())
    }

    @Test
    fun keepBoth() {
        conflictOnA()
        assertEquals(MergeOutcome.Merged(1), resolve("a.md" to Side.BOTH))
        assertEquals(deskA, phoneFile("a.md").readText())
        assertEquals(phoneA, phoneFile("a (phone).md").readText())
        assertMergeCommitPushed()
        assertEquals(deskA, desktopFile("a.md").readText())
        assertEquals(phoneA, desktopFile("a (phone).md").readText())
    }

    @Test
    fun keepBothPicksFreeName() {
        desktopWrite("a (phone).md" to "older copy\n")
        desktopCommitPush()
        phone.fetch(creds)
        assertTrue(phone.merge() is MergeOutcome.FastForward)
        conflictOnA()
        assertEquals(MergeOutcome.Merged(1), resolve("a.md" to Side.BOTH))
        assertEquals("older copy\n", phoneFile("a (phone).md").readText())
        assertEquals(phoneA, phoneFile("a (phone 2).md").readText())
        assertMergeCommitPushed()
    }

    @Test
    fun bothAddedUnrelatedSides() {
        desktopWrite("new.md" to "desk new\n")
        desktopCommitPush()
        phoneCommit("new.md" to "phone new\n")
        phone.fetch(creds)
        val c = phone.merge() as MergeOutcome.Conflict
        assertEquals(ConflictKind.BOTH_ADDED, c.files.single().kind)
        assertEquals(MergeOutcome.Merged(1), resolve("new.md" to Side.PHONE))
        assertEquals("phone new\n", phoneFile("new.md").readText())
        assertMergeCommitPushed()
    }

    @Test
    fun choicesMissingPathStaysConflict() {
        desktopWrite("a.md" to deskA, "b.md" to "desk bee\n")
        desktopCommitPush()
        phoneCommit("a.md" to phoneA, "b.md" to "phone bee\n")
        phone.fetch(creds)
        val head = phoneHead()
        val out = resolve("a.md" to Side.REMOTE)
        assertTrue(out.toString(), out is MergeOutcome.Conflict)
        assertEquals(listOf("a.md", "b.md"), (out as MergeOutcome.Conflict).paths)
        assertEquals(head, phoneHead())
        assertEquals(phoneA, phoneFile("a.md").readText())
        assertEquals("phone bee\n", phoneFile("b.md").readText())
        assertEquals(RepositoryState.SAFE, phoneState())
        assertTrue(phoneClean())
    }

    @Test
    fun leftoverMergeStateIsCleaned() {
        conflictOnA()
        assertFalse(phone.abortLeftoverMerge())
        val head = phoneHead()
        phoneGit { g ->
            val r = g.merge().include(g.repository.exactRef("refs/remotes/origin/" + g.repository.branch)).call()
            assertEquals(MergeResult.MergeStatus.CONFLICTING, r.mergeStatus)
        }
        assertEquals(RepositoryState.MERGING, phoneState())
        assertTrue(phoneFile("a.md").readText().contains("<<<<<<<"))
        assertTrue(phone.abortLeftoverMerge())
        assertEquals(RepositoryState.SAFE, phoneState())
        assertEquals(phoneA, phoneFile("a.md").readText())
        assertEquals(head, phoneHead())
        assertTrue(phoneClean())
        assertFalse(phone.abortLeftoverMerge())
        // And resolving still works afterwards.
        assertEquals(MergeOutcome.Merged(1), resolve("a.md" to Side.PHONE))
        assertMergeCommitPushed()
    }

    @Test
    fun cleanDivergentMergeStillMerges() {
        desktopWrite("b.md" to "desk bee\n")
        desktopCommitPush()
        phoneCommit("a.md" to phoneA)
        phone.fetch(creds)
        assertEquals(MergeOutcome.Merged(1), phone.merge("Phone", "phone@example.com"))
        assertEquals(phoneA, phoneFile("a.md").readText())
        assertEquals("desk bee\n", phoneFile("b.md").readText())
        assertEquals(2, phoneHeadCommit().parentCount)
        assertEquals(RepositoryState.SAFE, phoneState())
    }

    @Test
    fun resolvingWithoutConflictMergesNormally() {
        desktopWrite("b.md" to "desk bee\n")
        desktopCommitPush()
        phoneCommit("a.md" to phoneA)
        phone.fetch(creds)
        assertEquals(MergeOutcome.Merged(1), resolve("a.md" to Side.REMOTE))
        assertEquals(phoneA, phoneFile("a.md").readText())
        assertEquals("desk bee\n", phoneFile("b.md").readText())
    }

    @Test
    fun phoneCopyNames() {
        val none: (String) -> Boolean = { false }
        assertEquals("a (phone).md", ConflictInspector.phoneCopyName("a.md", none))
        assertEquals("dir/x.y/a (phone).tar", ConflictInspector.phoneCopyName("dir/x.y/a.tar", none))
        assertEquals("Makefile (phone)", ConflictInspector.phoneCopyName("Makefile", none))
        assertEquals(".gitignore (phone)", ConflictInspector.phoneCopyName(".gitignore", none))
        assertEquals("a (phone 3).md",
            ConflictInspector.phoneCopyName("a.md") { it == "a (phone).md" || it == "a (phone 2).md" })
    }
}
