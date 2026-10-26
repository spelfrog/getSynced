package dev.spelfrog.getsynced.sync

import dev.spelfrog.getsynced.git.Credentials
import dev.spelfrog.getsynced.git.GitRepo
import dev.spelfrog.getsynced.git.GitSetup
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.TransportException
import org.eclipse.jgit.lib.PersonIdent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.SocketException

/** Automatic single retry of the Fetch / Push steps on transient network errors. */
class SyncEngineRetryTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var remoteUrl: String
    private lateinit var desktop: Git
    private lateinit var phoneDir: File
    private val creds = Credentials("dummy", "dummy-token")
    private val ident = PersonIdent("Desktop", "desktop@example.com")
    private val sleeps = mutableListOf<Long>()

    @Before
    fun setUp() {
        GitSetup.init(tmp.newFolder("files"))
        val bare = tmp.newFolder("remote.git")
        Git.init().setBare(true).setDirectory(bare).call().close()
        remoteUrl = bare.toURI().toString()
        desktop = Git.cloneRepository().setURI(remoteUrl).setDirectory(tmp.newFolder("desktop")).call()
        val wt = desktop.repository.workTree
        File(wt, "a.md").writeText("line 1\n")
        desktop.add().addFilepattern(".").call()
        desktop.commit().setMessage("desktop edit").setAuthor(ident).setCommitter(ident).call()
        desktop.push().call()

        phoneDir = File(tmp.root, "phone")
        GitRepo.clone(phoneDir, remoteUrl, null, creds).close()
    }

    @After
    fun tearDown() {
        desktop.close()
    }

    // ---- helpers ----

    private fun config() = SyncConfig(
        repoDir = phoneDir,
        remoteUrl = remoteUrl,
        username = creds.username,
        token = creds.token,
        authorName = "Phone",
        authorEmail = "phone@example.com",
        deviceName = "testphone",
    )

    private fun engine(beforeFetch: suspend () -> Unit = {}, beforePush: suspend () -> Unit = {}) =
        SyncEngine(beforePush = beforePush, beforeFetch = beforeFetch, sleep = { sleeps += it })

    private fun sync(engine: SyncEngine, snapshots: MutableList<SyncRun>? = null): SyncRun =
        runBlocking { engine.run(config(), "test") { snapshots?.add(it) } }

    private fun SyncRun.step(name: String): SyncStep =
        steps.firstOrNull { it.name == name } ?: throw AssertionError("no step '$name' in ${dump()}")

    private fun SyncRun.dump(): String =
        "$outcome '$summary'\n" + steps.joinToString("\n") { "  ${it.name} ${it.state} ${it.detail} (${it.durationMs} ms)" }

    private fun connectionAbort() =
        TransportException("https://github.com/x/y.git: cannot open git-upload-pack",
            SocketException("Software caused connection abort"))

    private fun remoteFile(name: String): String {
        desktop.pull().call()
        return File(desktop.repository.workTree, name).readText()
    }

    // ---- tests ----

    @Test
    fun transientFetchErrorIsRetriedOnce() {
        var calls = 0
        val snaps = mutableListOf<SyncRun>()
        val run = sync(engine(beforeFetch = { if (calls++ == 0) throw connectionAbort() }), snaps)
        assertEquals(run.dump(), SyncOutcome.OK, run.outcome)
        assertEquals(2, calls)
        assertEquals(listOf(2000L), sleeps)
        val fetch = run.step("Fetch")
        assertEquals(StepState.OK, fetch.state)
        assertTrue(fetch.detail, fetch.detail.endsWith("(after 1 retry)"))
        assertEquals("up to date (after 1 retry)", fetch.detail)
        assertEquals(listOf("Check", "Commit", "Fetch", "Merge", "Push"), run.steps.map { it.name })
        // The retry was visible in the step list while it happened.
        assertTrue(
            snaps.joinToString("\n") { it.dump() },
            snaps.any { s ->
                s.steps.any {
                    it.name == "Fetch" && it.state == StepState.RUNNING &&
                        it.detail == "network error, retrying… (Software caused connection abort)"
                }
            },
        )
    }

    @Test
    fun nonTransientFetchErrorIsNotRetried() {
        var calls = 0
        val run = sync(engine(beforeFetch = { calls++; throw TransportException("https://x: not authorized") }))
        assertEquals(run.dump(), SyncOutcome.FAILED, run.outcome)
        assertEquals(1, calls)
        assertTrue(sleeps.isEmpty())
        assertEquals(StepState.FAILED, run.step("Fetch").state)
        assertTrue(run.summary, run.summary.startsWith("Auth failed"))
        assertTrue(run.steps.none { it.name == "Push" })
    }

    @Test
    fun transientFetchErrorTwiceFails() {
        var calls = 0
        val run = sync(engine(beforeFetch = { calls++; throw connectionAbort() }))
        assertEquals(run.dump(), SyncOutcome.FAILED, run.outcome)
        assertEquals(2, calls)
        assertEquals(listOf(2000L), sleeps)
        val fetch = run.step("Fetch")
        assertEquals(StepState.FAILED, fetch.state)
        assertTrue(fetch.detail, fetch.detail.contains("Software caused connection abort"))
        assertTrue(run.summary, run.summary.startsWith("Fetch failed: "))
    }

    @Test
    fun transientPushErrorIsRetriedOnce() {
        File(phoneDir, "a.md").writeText("phone edit\n")
        var calls = 0
        val run = sync(engine(beforePush = { if (calls++ == 0) throw connectionAbort() }))
        assertEquals(run.dump(), SyncOutcome.OK, run.outcome)
        assertEquals(2, calls)
        assertEquals(listOf(2000L), sleeps)
        val push = run.step("Push")
        assertEquals(StepState.OK, push.state)
        assertEquals("pushed 1 commit (after 1 retry)", push.detail)
        assertEquals(StepState.OK, run.step("Fetch").state)
        assertTrue(run.step("Fetch").detail, !run.step("Fetch").detail.contains("retry"))
        assertTrue(run.steps.none { it.name.endsWith("(retry)") })
        assertTrue(run.summary, run.summary.startsWith("pushed 1, pulled 0, "))
        assertEquals("phone edit\n", remoteFile("a.md"))
    }
}
