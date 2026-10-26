package dev.spelfrog.getsynced.sync

import dev.spelfrog.getsynced.git.Credentials
import dev.spelfrog.getsynced.git.GitRepo
import dev.spelfrog.getsynced.git.GitSetup
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.RepositoryState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SyncEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var remoteUrl: String
    private lateinit var desktop: Git
    private lateinit var phoneDir: File
    private val creds = Credentials("dummy", "dummy-token")
    private val ident = PersonIdent("Desktop", "desktop@example.com")

    @Before
    fun setUp() {
        GitSetup.init(tmp.newFolder("files"))
        val bare = tmp.newFolder("remote.git")
        Git.init().setBare(true).setDirectory(bare).call().close()
        remoteUrl = bare.toURI().toString() // file:/.../remote.git/
        desktop = Git.cloneRepository().setURI(remoteUrl).setDirectory(tmp.newFolder("desktop")).call()
        desktopCommit("a.md" to "line 1\nline 2\nline 3\n", "b.md" to "bee\n")
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

    private fun config() = SyncConfig(
        repoDir = phoneDir,
        remoteUrl = remoteUrl,
        username = creds.username,
        token = creds.token,
        authorName = "Phone",
        authorEmail = "phone@example.com",
        deviceName = "testphone",
    )

    private fun sync(engine: SyncEngine = SyncEngine(), snapshots: MutableList<SyncRun>? = null): SyncRun =
        runBlocking { engine.run(config(), "test") { snapshots?.add(it) } }

    private fun SyncRun.step(name: String): SyncStep =
        steps.firstOrNull { it.name == name } ?: throw AssertionError("no step '$name' in ${dump()}")

    private fun SyncRun.dump(): String =
        "$outcome '$summary'\n" + steps.joinToString("\n") { "  ${it.name} ${it.state} ${it.detail} (${it.durationMs} ms)" }

    private fun remoteFile(name: String): String {
        desktop.pull().call()
        return File(desktop.repository.workTree, name).readText()
    }

    private fun phoneGit(): Git = Git.open(phoneDir)

    private fun remoteHeadCommitCount(): Int {
        desktop.fetch().call()
        val id = desktop.repository.resolve("refs/remotes/origin/" + desktop.repository.branch)
        return desktop.log().add(id).call().count()
    }

    // ---- tests ----

    @Test
    fun cloneWorksAndAppliesAndroidConfig() {
        assertTrue(GitRepo.isRepo(phoneDir))
        assertEquals("bee\n", File(phoneDir, "b.md").readText())
        GitRepo(phoneDir).use { r ->
            val cfg = phoneGit().use { it.repository.config }
            assertFalse(cfg.getBoolean("core", "filemode", true))
            assertFalse(cfg.getBoolean("core", "symlinks", true))
            assertEquals(remoteUrl, r.remoteUrl())
            assertEquals(desktop.repository.branch, r.currentBranch())
            assertTrue(r.status().isClean)
            r.ensureAndroidConfig(remoteUrl) // idempotent
            assertEquals(0 to 0, r.aheadBehind())
        }
    }

    @Test
    fun cleanRepo() {
        val snaps = mutableListOf<SyncRun>()
        val run = sync(snapshots = snaps)
        assertEquals(run.dump(), SyncOutcome.OK, run.outcome)
        assertEquals(listOf("Check", "Commit", "Fetch", "Merge", "Push"), run.steps.map { it.name })
        assertEquals(StepState.SKIPPED, run.step("Commit").state)
        assertEquals("nothing to commit", run.step("Commit").detail)
        assertEquals(StepState.SKIPPED, run.step("Merge").state)
        assertEquals(StepState.SKIPPED, run.step("Push").state)
        assertTrue(run.summary, run.summary.startsWith("pushed 0, pulled 0, "))
        assertNotNull(run.finishedAt)
        run.steps.forEach { assertNotNull(it.name, it.durationMs); assertNotNull(it.name, it.startedAt) }
        // Snapshots: every step first appears RUNNING, last snapshot equals result.
        assertEquals(StepState.RUNNING, snaps.first().steps.single().state)
        assertEquals(run, snaps.last())
        assertTrue(snaps.all { it.steps.size <= 5 })
    }

    @Test
    fun localChangeIsCommittedAndPushed() {
        File(phoneDir, "a.md").writeText("phone edit\n")
        File(phoneDir, "new.md").writeText("new\n")
        File(phoneDir, "b.md").delete()
        val run = sync()
        assertEquals(run.dump(), SyncOutcome.OK, run.outcome)
        val commit = run.step("Commit")
        assertEquals(StepState.OK, commit.state)
        assertTrue(commit.detail, Regex("""2 changed, 1 deleted \([0-9a-f]{7}\)""").matches(commit.detail))
        assertEquals(StepState.OK, run.step("Push").state)
        assertEquals("pushed 1 commit", run.step("Push").detail)
        assertTrue(run.summary, run.summary.startsWith("pushed 1, pulled 0, "))

        assertEquals("phone edit\n", remoteFile("a.md"))
        assertEquals("new\n", remoteFile("new.md"))
        assertFalse(File(desktop.repository.workTree, "b.md").exists())
        val msg = desktop.log().setMaxCount(1).call().first()
        assertTrue(msg.fullMessage, msg.fullMessage.startsWith("sync from testphone 20"))
        assertEquals("Phone", msg.authorIdent.name)
        phoneGit().use { assertTrue(it.status().call().isClean) }
    }

    @Test
    fun remoteAheadFastForwards() {
        desktopPushChange("b.md" to "bee 2\n")
        desktopPushChange("c.md" to "sea\n")
        val run = sync()
        assertEquals(run.dump(), SyncOutcome.OK, run.outcome)
        assertEquals("2 new commits", run.step("Fetch").detail)
        assertEquals(StepState.OK, run.step("Merge").state)
        assertEquals("fast-forward, 2 commits", run.step("Merge").detail)
        assertEquals(StepState.SKIPPED, run.step("Push").state)
        assertTrue(run.summary, run.summary.startsWith("pushed 0, pulled 2, "))
        assertEquals("bee 2\n", File(phoneDir, "b.md").readText())
        assertEquals("sea\n", File(phoneDir, "c.md").readText())
    }

    @Test
    fun divergedWithoutConflictMergesAndPushes() {
        desktopPushChange("b.md" to "bee from desktop\n")
        File(phoneDir, "a.md").writeText("line 1\nline 2\nline 3\nphone line\n")
        val run = sync()
        assertEquals(run.dump(), SyncOutcome.OK, run.outcome)
        assertEquals(StepState.OK, run.step("Commit").state)
        assertEquals("merged 1 commit", run.step("Merge").detail)
        assertEquals(StepState.OK, run.step("Push").state)
        assertEquals("pushed 2 commits", run.step("Push").detail) // local commit + merge commit
        assertEquals("bee from desktop\n", File(phoneDir, "b.md").readText())
        assertEquals("line 1\nline 2\nline 3\nphone line\n", remoteFile("a.md"))
        val head = desktop.log().setMaxCount(1).call().first()
        assertEquals(2, head.parentCount)
        assertEquals("Phone", head.committerIdent.name)
    }

    @Test
    fun conflictResetsToLocalCommit() {
        desktopPushChange("a.md" to "line 1\nDESKTOP\nline 3\n", "b.md" to "both\n")
        File(phoneDir, "a.md").writeText("line 1\nPHONE\nline 3\n")
        File(phoneDir, "b.md").writeText("both phone\n")
        val run = sync()
        assertEquals(run.dump(), SyncOutcome.CONFLICT, run.outcome)
        assertEquals("Merge conflict in 2 files: a.md, b.md", run.summary)
        val merge = run.step("Merge")
        assertEquals(StepState.FAILED, merge.state)
        assertTrue(merge.detail, merge.detail.contains("a.md, b.md"))
        assertFalse(run.steps.any { it.name == "Push" })

        assertEquals("line 1\nPHONE\nline 3\n", File(phoneDir, "a.md").readText())
        assertEquals("both phone\n", File(phoneDir, "b.md").readText())
        phoneGit().use { g ->
            assertTrue(g.status().call().isClean)
            assertEquals(RepositoryState.SAFE, g.repository.repositoryState)
            assertTrue(g.log().setMaxCount(1).call().first().fullMessage.startsWith("sync from testphone"))
        }
        // Remote untouched.
        assertEquals("line 1\nDESKTOP\nline 3\n", remoteFile("a.md"))
    }

    @Test
    fun pushRejectedIsRetriedOnce() {
        File(phoneDir, "a.md").writeText("line 1\nline 2\nline 3\nphone\n")
        var moved = false
        val engine = SyncEngine(beforePush = {
            if (!moved) {
                moved = true
                desktopPushChange("b.md" to "moved meanwhile\n")
            }
        })
        val run = sync(engine)
        assertEquals(run.dump(), SyncOutcome.OK, run.outcome)
        assertEquals(
            listOf("Check", "Commit", "Fetch", "Merge", "Push", "Fetch (retry)", "Merge (retry)", "Push (retry)"),
            run.steps.map { it.name },
        )
        assertEquals(StepState.FAILED, run.step("Push").state)
        assertTrue(run.step("Push").detail, run.step("Push").detail.contains("REJECTED"))
        assertEquals("merged 1 commit", run.step("Merge (retry)").detail)
        assertEquals(StepState.OK, run.step("Push (retry)").state)
        assertTrue(run.summary, run.summary.startsWith("pushed 2, pulled 1, "))
        assertEquals("line 1\nline 2\nline 3\nphone\n", remoteFile("a.md"))
        assertEquals("moved meanwhile\n", remoteFile("b.md"))

        // A follow-up run is a no-op.
        val again = sync()
        assertEquals(again.dump(), SyncOutcome.OK, again.outcome)
        assertEquals(5, again.steps.size)
    }

    @Test
    fun pushRejectedTwiceFails() {
        File(phoneDir, "a.md").writeText("phone\n")
        var n = 0
        val engine = SyncEngine(beforePush = { desktopPushChange("b.md" to "moved ${n++}\n") })
        val run = sync(engine)
        assertEquals(run.dump(), SyncOutcome.FAILED, run.outcome)
        assertTrue(run.summary, run.summary.startsWith("Push rejected: REJECTED"))
        assertEquals(StepState.FAILED, run.step("Push (retry)").state)
    }

    @Test
    fun missingRepoFailsCheckWithoutThrowing() {
        val run = runBlocking { SyncEngine().run(config().copy(repoDir = File(tmp.root, "nope")), "test") }
        assertEquals(SyncOutcome.FAILED, run.outcome)
        assertEquals(listOf("Check"), run.steps.map { it.name })
        assertEquals(StepState.FAILED, run.steps.single().state)
        assertTrue(run.summary, run.summary.contains("does not exist"))
    }

    @Test
    fun unreachableRemoteFailsFetchWithMessage() {
        val run = runBlocking {
            SyncEngine().run(config().copy(remoteUrl = File(tmp.root, "gone.git").toURI().toString()), "test")
        }
        assertEquals(run.dump(), SyncOutcome.FAILED, run.outcome)
        val fetch = run.step("Fetch")
        assertEquals(StepState.FAILED, fetch.state)
        assertTrue(fetch.detail, fetch.detail.contains("Exception"))
        assertTrue(run.summary, run.summary.startsWith("Fetch failed: "))
    }

    @Test
    fun emptyRemoteGetsCreatedByPush() {
        val bare = tmp.newFolder("empty.git")
        Git.init().setBare(true).setDirectory(bare).call().close()
        val url = bare.toURI().toString()
        val dir = File(tmp.root, "phone2")
        GitRepo.clone(dir, url, null, creds).close()
        File(dir, "first.md").writeText("hello\n")
        val run = runBlocking { SyncEngine().run(config().copy(repoDir = dir, remoteUrl = url), "test") }
        assertEquals(run.dump(), SyncOutcome.OK, run.outcome)
        assertEquals("pushed 1 commit", run.step("Push").detail)
        Git.open(bare).use { g ->
            val branch = Git.open(dir).use { it.repository.branch }
            assertNotNull(g.repository.resolve(Constants.R_HEADS + branch))
        }
    }
}
