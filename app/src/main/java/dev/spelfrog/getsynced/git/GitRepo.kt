package dev.spelfrog.getsynced.git

import dev.spelfrog.getsynced.sync.ChangeSet
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.api.MergeResult.MergeStatus
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.lib.ConfigConstants
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.lib.RepositoryCache
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.util.FS
import java.io.File

data class Credentials(val username: String, val token: String)

data class WorkingTreeStatus(
    val added: Int,
    val modified: Int,
    val deleted: Int,
    val untracked: Int,
) {
    val isClean: Boolean get() = added + modified + deleted + untracked == 0
    val changedCount: Int get() = added + modified + untracked
}

sealed class MergeOutcome {
    object AlreadyUpToDate : MergeOutcome()
    data class FastForward(val commits: Int) : MergeOutcome()
    data class Merged(val commits: Int) : MergeOutcome()
    data class Conflict(val files: List<String>) : MergeOutcome()
}

data class CommitInfo(val sha: String, val message: String, val author: String, val timeMs: Long)

sealed class PushOutcome {
    object UpToDate : PushOutcome()
    data class Pushed(val commits: Int) : PushOutcome()
    /** Non fast-forward or other remote rejection; message is the remote's text. */
    data class Rejected(val message: String) : PushOutcome()
}

/**
 * Thin blocking JGit 5.13 wrapper. All methods block; callers use Dispatchers.IO.
 *
 * [branch] is the branch to sync (local and remote name); null = the currently checked out branch.
 */
class GitRepo(val dir: File, var branch: String? = null) : AutoCloseable {
    companion object {
        /** Set while hunting the "new files not seen" problem; prints to System.err. */
        @Volatile var DEBUG_STATUS = false
        const val REMOTE = "origin"
        /** Network timeout in seconds for clone/fetch/push, so nothing hangs forever. */
        const val TIMEOUT_S = 60
        private const val FETCH_SPEC = "+refs/heads/*:refs/remotes/origin/*"

        fun isRepo(dir: File): Boolean =
            RepositoryCache.FileKey.isGitRepository(File(dir, Constants.DOT_GIT), FS.DETECTED)

        /**
         * Clone remoteUrl into dir (dir must be empty or missing). Blocking.
         * progress receives one-line status updates ("receiving objects 40%").
         */
        fun clone(
            dir: File,
            remoteUrl: String,
            branch: String?,
            creds: Credentials,
            progress: (String) -> Unit = {},
        ): GitRepo {
            val cmd = Git.cloneRepository()
                .setURI(remoteUrl)
                .setDirectory(dir)
                .setRemote(REMOTE)
                .setCredentialsProvider(creds.provider())
                .setProgressMonitor(LineProgressMonitor(progress))
                .setTimeout(TIMEOUT_S)
            if (!branch.isNullOrBlank()) cmd.setBranch(Constants.R_HEADS + branch)
            cmd.call().close()
            val repo = GitRepo(dir, branch?.takeIf { it.isNotBlank() })
            try {
                repo.ensureAndroidConfig(remoteUrl)
            } catch (t: Throwable) {
                repo.close()
                throw t
            }
            return repo
        }

        private fun Credentials.provider() = UsernamePasswordCredentialsProvider(username, token)
    }

    private val git: Git = Git.open(dir)
    private val repo get() = git.repository

    /** Applies core.filemode=false, core.symlinks=false and sets origin url if given. Idempotent. */
    fun ensureAndroidConfig(remoteUrl: String? = null) {
        val cfg = repo.config
        var changed = false
        fun setBool(section: String, name: String, value: Boolean) {
            val present = cfg.getNames(section).any { it.equals(name, ignoreCase = true) }
            if (!present || cfg.getBoolean(section, name, !value) != value) {
                cfg.setBoolean(section, null, name, value); changed = true
            }
        }
        setBool(ConfigConstants.CONFIG_CORE_SECTION, ConfigConstants.CONFIG_KEY_FILEMODE, false)
        setBool(ConfigConstants.CONFIG_CORE_SECTION, ConfigConstants.CONFIG_KEY_SYMLINKS, false)
        if (!remoteUrl.isNullOrBlank()) {
            if (cfg.getString(ConfigConstants.CONFIG_REMOTE_SECTION, REMOTE, ConfigConstants.CONFIG_KEY_URL) != remoteUrl) {
                cfg.setString(ConfigConstants.CONFIG_REMOTE_SECTION, REMOTE, ConfigConstants.CONFIG_KEY_URL, remoteUrl)
                changed = true
            }
            if (cfg.getStringList(ConfigConstants.CONFIG_REMOTE_SECTION, REMOTE, "fetch").isEmpty()) {
                cfg.setString(ConfigConstants.CONFIG_REMOTE_SECTION, REMOTE, "fetch", FETCH_SPEC)
                changed = true
            }
        }
        if (changed) cfg.save()
    }

    fun currentBranch(): String =
        repo.branch ?: throw IllegalStateException("HEAD is missing in $dir")

    private fun syncBranch(): String = branch?.takeIf { it.isNotBlank() } ?: currentBranch()
    private fun trackingRef(): String = Constants.R_REMOTES + REMOTE + "/" + syncBranch()

    fun remoteUrl(): String? =
        repo.config.getString(ConfigConstants.CONFIG_REMOTE_SECTION, REMOTE, ConfigConstants.CONFIG_KEY_URL)

    fun status(): WorkingTreeStatus {
        val s = git.status().call()
        if (DEBUG_STATUS) {
            // Temporary diagnostics (System.err lands in logcat as "System.err").
            val root = dir.listFiles().orEmpty()
            System.err.println("GitRepo.status dir=$dir rootEntries=${root.size} " +
                "untracked=${s.untracked} added=${s.added} modified=${s.modified} changed=${s.changed} " +
                "missing=${s.missing} removed=${s.removed} ignored=${s.ignoredNotInIndex.take(20)}")
            root.filter { it.name.contains("perf-test") }.forEach { f ->
                val e = FS.DETECTED.list(dir, org.eclipse.jgit.treewalk.FileTreeIterator.DefaultFileModeStrategy.INSTANCE)
                    .firstOrNull { it.name == f.name }
                System.err.println("GitRepo.status probe ${f.name}: isFile=${f.isFile} len=${f.length()} " +
                    "mtime=${f.lastModified()} jgitMode=${e?.mode} jgitLen=${e?.length}")
            }
        }
        return WorkingTreeStatus(
            added = s.added.size,
            modified = (s.changed + s.modified).size,
            deleted = (s.removed + s.missing).size,
            untracked = s.untracked.size,
        )
    }

    /** Timings of the last [commitAll] as "status 1.2 s · add 0.3 s · add -u 0.1 s · commit 0.2 s". */
    var lastCommitTimings: String = ""
        private set

    /**
     * add -A (incl. deletions) + commit. [messageFor] gets the staged changes and returns the commit message.
     * Returns short sha, or null if the tree was clean.
     */
    fun commitAll(messageFor: (ChangeSet) -> String, authorName: String, authorEmail: String): String? {
        val timings = mutableListOf<String>()
        fun <T> timed(label: String, block: () -> T): T {
            val t0 = System.currentTimeMillis()
            try {
                return block()
            } finally {
                timings += "$label ${"%.1f".format(java.util.Locale.US, (System.currentTimeMillis() - t0) / 1000.0)} s"
                lastCommitTimings = timings.joinToString(" · ")
            }
        }
        // JGit 5.13's `add .` reads and hashes EVERY file in the tree, changed or not (AddCommand has no
        // "unchanged, keep index entry" shortcut). On a 200 MB vault over Android's FUSE storage that is
        // ~30 s per call. So we take the paths from status (which trusts index timestamps and is fast)
        // and stage only those. A PathFilter walk still visits the tree but only reads matched files.
        val s = timed("status") { git.status().call() }
        val toAdd = (s.untracked + s.modified + s.added + s.changed).toSortedSet()
        val toRemove = (s.missing + s.removed).toSortedSet() - toAdd
        if (toAdd.isEmpty() && toRemove.isEmpty()) return null
        val message = messageFor(changeSetOf(s))
        if (toAdd.isNotEmpty()) {
            timed("add ${toAdd.size}") {
                val cmd = git.add()
                toAdd.forEach { cmd.addFilepattern(it) }
                cmd.call()
            }
        }
        if (toRemove.isNotEmpty()) {
            timed("rm ${toRemove.size}") {
                val cmd = git.rm().setCached(true)
                toRemove.forEach { cmd.addFilepattern(it) }
                cmd.call()
            }
        }
        val ident = PersonIdent(authorName, authorEmail)
        val commit = timed("commit") {
            git.commit()
                .setMessage(message)
                .setAuthor(ident)
                .setCommitter(ident)
                .call()
        }
        return commit.id.abbreviate(7).name()
    }

    /**
     * What a commit of the whole working tree would contain. A path that is both added and deleted
     * (e.g. removed from the index but still on disk) counts as modified; added/deleted win over modified.
     */
    private fun changeSetOf(s: org.eclipse.jgit.api.Status): ChangeSet {
        val added = (s.untracked + s.added).toSortedSet()
        val deleted = (s.missing + s.removed).toSortedSet()
        val both = added intersect deleted
        added -= both
        deleted -= both
        val modified = ((s.modified + s.changed + both).toSortedSet() - added - deleted).toSortedSet()
        return ChangeSet(added = added.toList(), modified = modified.toList(), deleted = deleted.toList())
    }

    /** fetch origin. Returns number of commits on origin/<branch> that are not in HEAD. */
    fun fetch(creds: Credentials, progress: (String) -> Unit = {}): Int {
        git.fetch()
            .setRemote(REMOTE)
            .setRefSpecs(RefSpec(FETCH_SPEC))
            .setCredentialsProvider(creds.provider())
            .setProgressMonitor(LineProgressMonitor(progress))
            .setTimeout(TIMEOUT_S)
            .call()
        return aheadBehind().second
    }

    /** (ahead, behind) of current branch vs origin/<branch>. Missing refs count as empty. */
    fun aheadBehind(): Pair<Int, Int> {
        val head = repo.resolve(Constants.HEAD)
        val remote = repo.exactRef(trackingRef())?.objectId
        return countOnlyIn(head, remote) to countOnlyIn(remote, head)
    }

    /** Number of commits reachable from [a] but not from [b]. */
    private fun countOnlyIn(a: ObjectId?, b: ObjectId?): Int {
        if (a == null) return 0
        RevWalk(repo).use { walk ->
            walk.markStart(walk.parseCommit(a))
            if (b != null) walk.markUninteresting(walk.parseCommit(b))
            return walk.count()
        }
    }

    /**
     * Merge origin/<branch> into current branch. On Conflict the caller must call resetHardToHead().
     * If [authorName]/[authorEmail] are given they are used for a merge commit; otherwise JGit's default identity.
     */
    fun merge(authorName: String? = null, authorEmail: String? = null): MergeOutcome {
        val remote = repo.exactRef(trackingRef()) ?: return MergeOutcome.AlreadyUpToDate
        val behind = countOnlyIn(remote.objectId, repo.resolve(Constants.HEAD))
        if (behind == 0) return MergeOutcome.AlreadyUpToDate
        val withIdent = authorName != null && authorEmail != null
        val result = git.merge()
            .include(remote)
            .setFastForward(MergeCommand.FastForwardMode.FF)
            .setCommit(!withIdent)
            .setMessage("Merge ${REMOTE}/${syncBranch()}")
            .call()
        return when (result.mergeStatus) {
            MergeStatus.ALREADY_UP_TO_DATE -> MergeOutcome.AlreadyUpToDate
            MergeStatus.FAST_FORWARD, MergeStatus.FAST_FORWARD_SQUASHED -> MergeOutcome.FastForward(behind)
            MergeStatus.MERGED -> MergeOutcome.Merged(behind)
            MergeStatus.MERGED_NOT_COMMITTED -> {
                val ident = PersonIdent(authorName, authorEmail)
                git.commit()
                    .setMessage(repo.readMergeCommitMsg() ?: "Merge ${REMOTE}/${syncBranch()}")
                    .setAuthor(ident)
                    .setCommitter(ident)
                    .call()
                MergeOutcome.Merged(behind)
            }
            MergeStatus.CONFLICTING -> MergeOutcome.Conflict(result.conflicts?.keys?.sorted() ?: emptyList())
            else -> {
                val paths = result.failingPaths?.entries?.joinToString { "${it.key} (${it.value})" }
                    ?: result.checkoutConflicts?.joinToString()
                throw IllegalStateException(
                    "merge ${result.mergeStatus}" + if (paths.isNullOrBlank()) "" else ": $paths"
                )
            }
        }
    }

    fun resetHardToHead() {
        if (repo.resolve(Constants.HEAD) == null) return
        git.reset().setMode(ResetCommand.ResetType.HARD).setRef(Constants.HEAD).call()
    }

    fun push(creds: Credentials, progress: (String) -> Unit = {}): PushOutcome {
        if (repo.resolve(Constants.HEAD) == null) return PushOutcome.UpToDate
        val local = repo.fullBranch ?: throw IllegalStateException("HEAD is missing in $dir")
        if (!local.startsWith(Constants.R_HEADS)) throw IllegalStateException("HEAD is detached, refusing to push")
        val target = Constants.R_HEADS + syncBranch()
        val ahead = aheadBehind().first
        val results = git.push()
            .setRemote(REMOTE)
            .setRefSpecs(RefSpec("$local:$target"))
            .setCredentialsProvider(creds.provider())
            .setProgressMonitor(LineProgressMonitor(progress))
            .setTimeout(TIMEOUT_S)
            .call()
        val updates = results.flatMap { it.remoteUpdates }
        val bad = updates.firstOrNull {
            it.status != RemoteRefUpdate.Status.OK && it.status != RemoteRefUpdate.Status.UP_TO_DATE
        }
        if (bad != null) {
            val msg = listOfNotNull(bad.status.name, bad.message?.takeIf { it.isNotBlank() }).joinToString(": ")
            return PushOutcome.Rejected(msg)
        }
        return if (updates.any { it.status == RemoteRefUpdate.Status.OK }) {
            // Keep origin/<branch> in sync even if the remote has no fetch refspec configured.
            repo.updateRef(trackingRef()).apply {
                setNewObjectId(repo.resolve(Constants.HEAD))
                setForceUpdate(true)
                update()
            }
            PushOutcome.Pushed(ahead)
        } else {
            PushOutcome.UpToDate
        }
    }

    /** Newest [limit] commits of HEAD; empty for a repo without commits. */
    fun recentCommits(limit: Int = 3): List<CommitInfo> {
        val head = repo.resolve(Constants.HEAD) ?: return emptyList()
        return git.log().add(head).setMaxCount(limit).call().map { c ->
            CommitInfo(
                sha = c.name.take(7),
                message = c.shortMessage,
                author = c.authorIdent.name,
                timeMs = c.commitTime * 1000L,
            )
        }
    }

    override fun close() {
        git.close()
    }
}

/** Maps JGit progress to throttled one-liners: "Receiving objects 40%". */
internal class LineProgressMonitor(
    private val out: (String) -> Unit,
    private val minIntervalMs: Long = 200,
) : ProgressMonitor {
    private var task = ""
    private var total = 0
    private var done = 0
    private var lastEmit = 0L
    private var lastLine = ""

    override fun start(totalTasks: Int) {}

    override fun beginTask(title: String?, totalWork: Int) {
        task = title ?: ""
        total = totalWork
        done = 0
        emit(force = false)
    }

    override fun update(completed: Int) {
        done += completed
        emit(force = false)
    }

    override fun endTask() {
        if (total > 0) done = total
        emit(force = true)
    }

    override fun isCancelled(): Boolean = false

    private fun emit(force: Boolean) {
        val line = if (total > 0 && total != ProgressMonitor.UNKNOWN) {
            "$task ${(done.toLong() * 100 / total).coerceIn(0, 100)}%"
        } else if (done > 0) "$task $done" else task
        val now = System.currentTimeMillis()
        if (line == lastLine) return
        if (!force && now - lastEmit < minIntervalMs) return
        lastEmit = now
        lastLine = line
        try { out(line) } catch (_: Throwable) {}
    }
}
