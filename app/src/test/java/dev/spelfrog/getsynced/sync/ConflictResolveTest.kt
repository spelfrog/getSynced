package dev.spelfrog.getsynced.sync

import dev.spelfrog.getsynced.git.Credentials
import dev.spelfrog.getsynced.git.GitRepo
import dev.spelfrog.getsynced.git.GitSetup
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.transport.RefSpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Engine-level tests of light conflict resolution: the CONFLICT payload, the Resolve step, stale choices
 * and the Check step's interrupted-merge cleanup. Same setup as [SyncEngineTest] (bare remote, desktop clone,
 * phone clone made by GitRepo.clone).
 */
class ConflictResolveTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var remoteUrl: String
    private lateinit var desktop: Git
    private lateinit var phoneDir: File
    private val creds = Credentials("dummy", "dummy-token")
    private val ident = PersonIdent("Desktop", "desktop@example.com")
    private val phoneIdent = PersonIdent("Phone", "phone@example.com")

    private val base = "line 1\nline 2\nline 3\n"
    private val desktopA = "line 1\nDESKTOP\nline 3\n"
    private val phoneA = "line 1\nPHONE\nline 3\n"

    @Before
    fun setUp() {
        GitSetup.init(tmp.newFolder("files"))
        val bare = tmp.newFolder("remote.git")
        Git.init().setBare(true).setDirectory(bare).call().close()
        remoteUrl = bare.toURI().toString()
        desktop = Git.cloneRepository().setURI(remoteUrl).setDirectory(tmp.newFolder("desktop")).call()
        desktopCommit("a.md" to base, "b.md" to "bee\n")
        desktop.push().call()

        phoneDir = File(tmp.root, "phone")
        GitRepo.clone(phoneDir, remoteUrl, null, creds).close()
    }

    @After
    fun tearDown() {
        desktop.close()
    }

    // ---- helpers ----

    private fun desktopCommit(vararg files: Pair<String, String>, msg: String = "desktop edit") {
        val wt = desktop.repository.workTree
        files.forEach { (name, content) -> File(wt, name).writeText(content) }
        desktop.add().addFilepattern(".").call()
        desktop.commit().setMessage(msg).setAuthor(ident).setCommitter(ident).call()
    }

    private fun desktopPushChange(vararg files: Pair<String, String>) {
        desktop.pull().call()
        desktopCommit(*files)
        desktop.push().call()
    }

    private fun desktopPushDelete(name: String) {
        desktop.pull().call()
        desktop.rm().addFilepattern(name).call()
        desktop.commit().setMessage("desktop delete").setAuthor(ident).setCommitter(ident).call()
        desktop.push().call()
    }

    private fun config(resolution: Resolution? = null) = SyncConfig(
        repoDir = phoneDir,
        remoteUrl = remoteUrl,
        username = creds.username,
        token = creds.token,
        authorName = "Phone",
        authorEmail = "phone@example.com",
        deviceName = "testphone",
        resolution = resolution,
    )

    private fun sync(resolution: Resolution? = null, engine: SyncEngine = SyncEngine()): SyncRun =
        runBlocking { engine.run(config(resolution), "test") }

    private fun SyncRun.step(name: String): SyncStep =
        steps.firstOrNull { it.name == name } ?: throw AssertionError("no step '$name' in ${dump()}")

    private fun SyncRun.dump(): String =
        "$outcome '$summary'\n" + steps.joinToString("\n") { "  ${it.name} ${it.state} ${it.detail} (${it.durationMs} ms)" }

    /** Desktop's view of the remote after a pull; null when the file does not exist there. */
    private fun remoteFileOrNull(name: String): String? {
        desktop.pull().call()
        return File(desktop.repository.workTree, name).takeIf { it.exists() }?.readText()
    }

    private fun phoneFileOrNull(name: String): String? = File(phoneDir, name).takeIf { it.exists() }?.readText()

    private fun phoneGit(): Git = Git.open(phoneDir)

    private fun branch(): String = desktop.repository.branch

    private fun desktopHeadSha(): String {
        desktop.fetch().call()
        return desktop.repository.resolve("refs/remotes/origin/" + branch()).name
    }

    /** Asserts the phone vault is a clean, non-merging checkout without conflict markers. */
    private fun assertPhoneClean(vararg names: String) {
        phoneGit().use { g ->
            assertEquals(RepositoryState.SAFE, g.repository.repositoryState)
            assertTrue(g.status().call().isClean)
            assertFalse(g.repository.readDirCache().hasUnmergedPaths())
        }
        names.forEach { n -> phoneFileOrNull(n)?.let { assertFalse("$n has markers: $it", it.contains("<<<<<<<")) } }
    }

    /** Desktop changes a.md (and optionally more), phone changes a.md differently; returns the CONFLICT run. */
    private fun conflictOnA(): SyncRun {
        desktopPushChange("a.md" to desktopA)
        File(phoneDir, "a.md").writeText(phoneA)
        val run = sync()
        assertEquals(run.dump(), SyncOutcome.CONFLICT, run.outcome)
        return run
    }

    private fun resolutionFor(run: SyncRun, vararg choices: Pair<String, Side>): Resolution {
        val info = notNull(run.conflict)
        return Resolution(info.remoteSha, mapOf(*choices))
    }

    private fun <T> notNull(value: T?): T = value ?: throw AssertionError("expected a value, got null")

    /** After a successful resolve: remote head is a two-parent merge commit with the expected message. */
    private fun assertRemoteHeadIsResolveMerge(vararg lines: String) {
        desktop.pull().call()
        val head = desktop.log().setMaxCount(1).call().first()
        assertEquals(2, head.parentCount)
        assertEquals("Merge origin/${branch()} (resolved on testphone)", head.shortMessage)
        lines.forEach { assertTrue(head.fullMessage, head.fullMessage.lines().contains(it)) }
        assertEquals("Phone", head.committerIdent.name)
    }

    private fun assertFollowUpIsNoOp() {
        val again = sync()
        assertEquals(again.dump(), SyncOutcome.OK, again.outcome)
        assertEquals(listOf("Check", "Commit", "Fetch", "Merge", "Push"), again.steps.map { it.name })
        assertEquals(StepState.SKIPPED, again.step("Merge").state)
        assertEquals(StepState.SKIPPED, again.step("Push").state)
        assertNull(again.conflict)
    }

    // ---- tests ----

    /** Plan 6.1: payload with paths, kinds and both SHAs; no markers; state SAFE. */
    @Test
    fun conflictReportsFilesWithoutTouchingWorktree() {
        desktopPushChange("a.md" to desktopA, "b.md" to "both\n")
        File(phoneDir, "a.md").writeText(phoneA)
        File(phoneDir, "b.md").writeText("both phone\n")
        val run = sync()
        assertEquals(run.dump(), SyncOutcome.CONFLICT, run.outcome)
        assertEquals("Merge conflict in 2 files: a.md, b.md", run.summary)
        val merge = run.step("Merge")
        assertEquals(StepState.FAILED, merge.state)
        assertEquals("conflict in 2 files: a.md, b.md (nothing merged, local commit kept)", merge.detail)
        assertFalse(run.steps.any { it.name == "Push" })

        val info = notNull(run.conflict)
        assertEquals(listOf("a.md", "b.md"), info.files.map { it.path })
        assertTrue(info.files.all { it.kind == ConflictKind.BOTH_MODIFIED && !it.binary })
        assertEquals(phoneA.length.toLong(), info.files[0].localSize)
        assertEquals(desktopA.length.toLong(), info.files[0].remoteSize)
        assertEquals(desktopHeadSha(), info.remoteSha)
        phoneGit().use { g -> assertEquals(g.repository.resolve("HEAD").name, info.headSha) }

        assertEquals(phoneA, phoneFileOrNull("a.md"))
        assertEquals("both phone\n", phoneFileOrNull("b.md"))
        assertPhoneClean("a.md", "b.md")
        assertEquals(desktopA, remoteFileOrNull("a.md"))
    }

    /** Plan 6.2. */
    @Test
    fun keepPhonePushesMergeCommit() {
        val first = conflictOnA()
        val run = sync(resolutionFor(first, "a.md" to Side.LOCAL))
        assertEquals(run.dump(), SyncOutcome.OK, run.outcome)
        assertEquals(listOf("Check", "Commit", "Fetch", "Resolve", "Push"), run.steps.map { it.name })
        assertEquals(StepState.SKIPPED, run.step("Commit").state)
        assertEquals("kept local: a.md (merged 1 commit)", run.step("Resolve").detail)
        assertEquals("pushed 2 commits", run.step("Push").detail) // phone commit + merge commit
        assertTrue(run.summary, run.summary.startsWith("pushed 2, pulled 1, "))
        assertNull(run.conflict)

        assertEquals(phoneA, phoneFileOrNull("a.md"))
        assertEquals(phoneA, remoteFileOrNull("a.md"))
        assertRemoteHeadIsResolveMerge("local a.md")
        assertPhoneClean("a.md")
        assertFollowUpIsNoOp()
    }

    /** Plan 6.3. */
    @Test
    fun keepRemote() {
        val first = conflictOnA()
        val run = sync(resolutionFor(first, "a.md" to Side.REMOTE))
        assertEquals(run.dump(), SyncOutcome.OK, run.outcome)
        assertEquals("kept remote: a.md (merged 1 commit)", run.step("Resolve").detail)
        assertEquals(desktopA, phoneFileOrNull("a.md"))
        assertEquals(desktopA, remoteFileOrNull("a.md"))
        assertRemoteHeadIsResolveMerge("remote a.md")
        // The phone's version stays in the history (second parent chain).
        phoneGit().use { g ->
            assertTrue(g.log().call().any { it.shortMessage == "testphone: edited a.md" })
        }
        assertPhoneClean("a.md")
        assertFollowUpIsNoOp()
    }

    /** Plan 6.4. */
    @Test
    fun mixedChoices() {
        desktopPushChange("a.md" to desktopA, "b.md" to "bee desktop\n")
        File(phoneDir, "a.md").writeText(phoneA)
        File(phoneDir, "b.md").writeText("bee phone\n")
        val first = sync()
        assertEquals(first.dump(), SyncOutcome.CONFLICT, first.outcome)
        val run = sync(resolutionFor(first, "a.md" to Side.LOCAL, "b.md" to Side.REMOTE))
        assertEquals(run.dump(), SyncOutcome.OK, run.outcome)
        assertEquals("kept local: a.md · kept remote: b.md (merged 1 commit)", run.step("Resolve").detail)
        assertEquals(phoneA, remoteFileOrNull("a.md"))
        assertEquals("bee desktop\n", remoteFileOrNull("b.md"))
        assertEquals(phoneA, phoneFileOrNull("a.md"))
        assertEquals("bee desktop\n", phoneFileOrNull("b.md"))
        assertRemoteHeadIsResolveMerge("local a.md", "remote b.md")
        assertFollowUpIsNoOp()
    }

    /** Plan 6.5, phone deleted / desktop edited: PHONE keeps the deletion, REMOTE restores the file. */
    @Test
    fun deletedOnPhoneKeepPhone() = deleteModify(phoneDeletes = true, side = Side.LOCAL, expected = null)

    @Test
    fun deletedOnPhoneKeepRemote() = deleteModify(phoneDeletes = true, side = Side.REMOTE, expected = desktopA)

    /** Plan 6.5 reversed, phone edited / desktop deleted: PHONE keeps the edit, REMOTE keeps the deletion. */
    @Test
    fun deletedOnRemoteKeepPhone() = deleteModify(phoneDeletes = false, side = Side.LOCAL, expected = phoneA)

    @Test
    fun deletedOnRemoteKeepRemote() = deleteModify(phoneDeletes = false, side = Side.REMOTE, expected = null)

    private fun deleteModify(phoneDeletes: Boolean, side: Side, expected: String?) {
        if (phoneDeletes) {
            desktopPushChange("a.md" to desktopA)
            File(phoneDir, "a.md").delete()
        } else {
            desktopPushDelete("a.md")
            File(phoneDir, "a.md").writeText(phoneA)
        }
        val first = sync()
        assertEquals(first.dump(), SyncOutcome.CONFLICT, first.outcome)
        val file = notNull(first.conflict).files.single()
        assertEquals("a.md", file.path)
        assertEquals(if (phoneDeletes) ConflictKind.DELETED_LOCALLY else ConflictKind.DELETED_ON_REMOTE, file.kind)
        assertFalse(file.canKeepBoth)
        if (phoneDeletes) assertNull(file.localSize) else assertNull(file.remoteSize)
        assertPhoneClean("a.md")
        assertEquals(if (phoneDeletes) null else phoneA, phoneFileOrNull("a.md")) // worktree untouched

        val run = sync(resolutionFor(first, "a.md" to side))
        assertEquals(run.dump(), SyncOutcome.OK, run.outcome)
        assertEquals(StepState.OK, run.step("Resolve").state)
        assertEquals(expected, phoneFileOrNull("a.md"))
        assertEquals(expected, remoteFileOrNull("a.md"))
        assertEquals("bee\n", remoteFileOrNull("b.md"))
        assertRemoteHeadIsResolveMerge("${side.name.lowercase()} a.md")
        assertPhoneClean("a.md")
        assertFollowUpIsNoOp()
    }

    /**
     * Plan 6.7. Implemented behaviour: after the fetch the engine compares origin/<branch> with
     * Resolution.remoteSha; if the remote moved, the choices are not applied, the normal Merge step runs
     * (no Resolve step) and its detail notes "earlier choices discarded (remote moved)". Here the conflict
     * persists, so the run ends CONFLICT with a fresh payload pinned to the new remote commit.
     */
    @Test
    fun staleChoicesIgnoredWhenRemoteMoved() {
        val first = conflictOnA()
        val stale = resolutionFor(first, "a.md" to Side.LOCAL)
        desktopPushChange("a.md" to "line 1\nDESKTOP AGAIN\nline 3\n")

        val run = sync(stale)
        assertEquals(run.dump(), SyncOutcome.CONFLICT, run.outcome)
        assertEquals(listOf("Check", "Commit", "Fetch", "Merge"), run.steps.map { it.name })
        assertEquals(
            "conflict in 1 file: a.md (nothing merged, local commit kept) · earlier choices discarded (remote moved)",
            run.step("Merge").detail,
        )
        val info = notNull(run.conflict)
        assertNotEquals(stale.remoteSha, info.remoteSha)
        assertEquals(desktopHeadSha(), info.remoteSha)
        assertEquals(phoneA, phoneFileOrNull("a.md"))
        assertEquals("line 1\nDESKTOP AGAIN\nline 3\n", remoteFileOrNull("a.md"))
        assertPhoneClean("a.md")
    }

    /** Stale choices whose conflict went away: the normal merge succeeds without applying them. */
    @Test
    fun staleChoicesMergeCleanlyWhenDesktopResolvedTheSameWay() {
        val first = conflictOnA()
        val stale = resolutionFor(first, "a.md" to Side.REMOTE)
        // Desktop adopts the phone's text: the merge is now clean (both sides changed a.md identically).
        desktopPushChange("a.md" to phoneA)

        val run = sync(stale)
        assertEquals(run.dump(), SyncOutcome.OK, run.outcome)
        assertFalse(run.steps.any { it.name == "Resolve" })
        assertTrue(run.step("Merge").detail, run.step("Merge").detail.endsWith(" · earlier choices discarded (remote moved)"))
        assertEquals(phoneA, phoneFileOrNull("a.md")) // REMOTE choice was not applied to the old remote text
        assertEquals(phoneA, remoteFileOrNull("a.md"))
    }

    /** Plan 6.8. */
    @Test
    fun unresolvedThenDesktopResolves() {
        conflictOnA()
        // Phone keeps editing while the conflict is open: committed locally, never pushed.
        File(phoneDir, "c.md").writeText("sea\n")
        val second = sync()
        assertEquals(second.dump(), SyncOutcome.CONFLICT, second.outcome)
        assertEquals(StepState.OK, second.step("Commit").state)
        assertEquals(listOf("a.md"), notNull(second.conflict).paths())
        assertNull(remoteFileOrNull("c.md"))
        assertPhoneClean("a.md")

        // Desktop pulls the phone's branch directly, resolves a.md and pushes.
        val b = branch()
        desktop.fetch().setRemote(phoneDir.toURI().toString())
            .setRefSpecs(RefSpec("refs/heads/$b:refs/remotes/phone/$b")).call()
        val merge = desktop.merge().include(desktop.repository.resolve("refs/remotes/phone/$b")).call()
        assertEquals(MergeResult.MergeStatus.CONFLICTING, merge.mergeStatus)
        File(desktop.repository.workTree, "a.md").writeText("line 1\nRESOLVED\nline 3\n")
        desktop.add().addFilepattern("a.md").call()
        desktop.commit().setMessage("resolve on desktop").setAuthor(ident).setCommitter(ident).call()
        desktop.push().call()

        val run = sync()
        assertEquals(run.dump(), SyncOutcome.OK, run.outcome)
        assertNull(run.conflict)
        assertEquals("fast-forward, 2 commits", run.step("Merge").detail)
        assertEquals(StepState.SKIPPED, run.step("Push").state)
        assertEquals("line 1\nRESOLVED\nline 3\n", phoneFileOrNull("a.md"))
        assertEquals("sea\n", phoneFileOrNull("c.md"))
        assertPhoneClean("a.md")
    }

    private fun ConflictInfo.paths() = files.map { it.path }

    /** Plan 6.9: a conflicting path without a choice leaves everything untouched. */
    @Test
    fun choicesMissingPathStaysConflict() {
        desktopPushChange("a.md" to desktopA, "b.md" to "bee desktop\n")
        File(phoneDir, "a.md").writeText(phoneA)
        val first = sync()
        assertEquals(first.dump(), SyncOutcome.CONFLICT, first.outcome)
        assertEquals(listOf("a.md"), notNull(first.conflict).paths())
        val choices = resolutionFor(first, "a.md" to Side.LOCAL)
        // Before the user resolves, the phone also edits b.md: a second conflicting path, same remote commit.
        File(phoneDir, "b.md").writeText("bee phone\n")

        val run = sync(choices)
        assertEquals(run.dump(), SyncOutcome.CONFLICT, run.outcome)
        assertEquals(listOf("Check", "Commit", "Fetch", "Resolve"), run.steps.map { it.name })
        val resolve = run.step("Resolve")
        assertEquals(StepState.FAILED, resolve.state)
        assertEquals(
            "choices do not cover every conflicting file; conflict in 2 files: a.md, b.md (nothing merged, local commit kept)",
            resolve.detail,
        )
        assertEquals("Merge conflict in 2 files: a.md, b.md", run.summary)
        val info = notNull(run.conflict)
        assertEquals(listOf("a.md", "b.md"), info.paths())
        assertEquals(choices.remoteSha, info.remoteSha)
        assertEquals(phoneA, phoneFileOrNull("a.md"))
        assertEquals("bee phone\n", phoneFileOrNull("b.md"))
        assertPhoneClean("a.md", "b.md")
        assertEquals(desktopA, remoteFileOrNull("a.md"))
    }

    /** Plan 6.10: a merge left half-done (process killed) is cleaned up by the Check step. */
    @Test
    fun leftoverMergeStateIsCleaned() {
        desktopPushChange("a.md" to desktopA)
        phoneGit().use { g ->
            File(phoneDir, "a.md").writeText(phoneA)
            g.add().addFilepattern("a.md").call()
            g.commit().setMessage("phone edit").setAuthor(phoneIdent).setCommitter(phoneIdent).call()
            g.fetch().call()
            val m = g.merge().include(g.repository.resolve("refs/remotes/origin/${branch()}")).call()
            assertEquals(MergeResult.MergeStatus.CONFLICTING, m.mergeStatus)
            assertEquals(RepositoryState.MERGING, g.repository.repositoryState)
        }
        assertTrue(File(phoneDir, "a.md").readText().contains("<<<<<<<"))

        val run = sync()
        assertTrue(run.step("Check").detail, run.step("Check").detail.endsWith(" · cleaned up an interrupted merge"))
        assertEquals(StepState.SKIPPED, run.step("Commit").state) // markers were not committed
        assertEquals(run.dump(), SyncOutcome.CONFLICT, run.outcome)
        assertEquals(phoneA, phoneFileOrNull("a.md"))
        assertPhoneClean("a.md")
        phoneGit().use { g ->
            assertEquals(1, g.log().setMaxCount(1).call().first().parentCount)
            assertTrue(g.log().call().none { it.fullMessage.contains("<<<<<<<") })
        }

        // Nothing left to clean on the next run.
        val again = sync()
        assertFalse(again.step("Check").detail.contains("interrupted"))
    }

    @Test
    fun resolveMessageAndChoiceDescription() {
        val choices = mapOf("b.md" to Side.REMOTE, "a.md" to Side.LOCAL, "c.md" to Side.BOTH, "d.md" to Side.LOCAL)
        assertEquals("kept local: a.md, d.md · kept remote: b.md · kept both: c.md", SyncEngine.describeChoices(choices))
        assertEquals(
            "Merge origin/main (resolved on Pixel)\n\nlocal a.md\nremote b.md\nboth c.md\nlocal d.md",
            SyncEngine.resolveMessage("main", "Pixel", choices),
        )
        assertTrue(SyncEngine.sameCommit("0123456789abcdef0123456789abcdef01234567", "0123456"))
        assertFalse(SyncEngine.sameCommit("0123456789abcdef0123456789abcdef01234567", ""))
        assertFalse(SyncEngine.sameCommit(null, "0123456"))
    }
}
