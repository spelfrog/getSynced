package dev.spelfrog.getsynced.git

import dev.spelfrog.getsynced.sync.ConflictFile
import dev.spelfrog.getsynced.sync.ConflictKind
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectReader
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.merge.MergeStrategy
import org.eclipse.jgit.merge.ResolveMerger
import org.eclipse.jgit.revwalk.RevTree
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.revwalk.filter.RevFilter
import org.eclipse.jgit.treewalk.TreeWalk

/**
 * Read-only conflict analysis: merges in memory (object reads only, no working tree or index access)
 * and describes each conflicting path.
 */
internal object ConflictInspector {
    /** Bytes inspected by the binary heuristic (same as git's 8000-byte check, rounded up). */
    private const val BINARY_PROBE = 8 * 1024

    /**
     * Recursive merge of [head] and [remote] in memory. Returns the sorted unmerged paths, or an empty list
     * when the merge would be clean.
     */
    fun unmergedPaths(repo: Repository, head: ObjectId, remote: ObjectId): List<String> {
        val merger = MergeStrategy.RECURSIVE.newMerger(repo, true) as ResolveMerger
        val clean = merger.merge(head, remote)
        if (clean) return emptyList()
        return merger.unmergedPaths.orEmpty().distinct().sorted()
    }

    /** Describes [paths] (as reported by [unmergedPaths]) using the merge base, HEAD and remote trees. */
    fun classify(repo: Repository, paths: List<String>, head: ObjectId, remote: ObjectId): List<ConflictFile> {
        RevWalk(repo).use { walk ->
            val headCommit = walk.parseCommit(head)
            val remoteCommit = walk.parseCommit(remote)
            val baseTree = mergeBase(repo, headCommit, remoteCommit)?.let { walk.parseCommit(it).tree }
            val reader = walk.objectReader
            return paths.map { path ->
                val inHead = blobAt(reader, path, headCommit.tree)
                val inRemote = blobAt(reader, path, remoteCommit.tree)
                val inBase = baseTree?.let { blobAt(reader, path, it) }
                val kind = when {
                    inHead == null -> ConflictKind.DELETED_LOCALLY
                    inRemote == null -> ConflictKind.DELETED_ON_REMOTE
                    inBase == null -> ConflictKind.BOTH_ADDED
                    else -> ConflictKind.BOTH_MODIFIED
                }
                ConflictFile(
                    path = path,
                    kind = kind,
                    binary = listOfNotNull(inHead, inRemote).any { isBinary(reader, it) },
                    localSize = inHead?.let { reader.getObjectSize(it, ObjectReader.OBJ_ANY) },
                    remoteSize = inRemote?.let { reader.getObjectSize(it, ObjectReader.OBJ_ANY) },
                )
            }
        }
    }

    /** First merge base of [a] and [b], or null for unrelated histories. */
    fun mergeBase(repo: Repository, a: ObjectId, b: ObjectId): ObjectId? =
        RevWalk(repo).use { walk ->
            walk.revFilter = RevFilter.MERGE_BASE
            walk.markStart(walk.parseCommit(a))
            walk.markStart(walk.parseCommit(b))
            walk.next()?.copy()
        }

    /** Blob id of [path] in [tree]; null if absent or not a regular file/symlink (e.g. a directory). */
    fun blobAt(reader: ObjectReader, path: String, tree: RevTree): ObjectId? =
        TreeWalk.forPath(reader, path, tree)?.use { tw ->
            if (tw.getFileMode(0).objectType == org.eclipse.jgit.lib.Constants.OBJ_BLOB) tw.getObjectId(0) else null
        }

    private fun isBinary(reader: ObjectReader, blob: ObjectId): Boolean =
        reader.open(blob).openStream().use { input ->
            val buf = ByteArray(BINARY_PROBE)
            var n = 0
            while (n < buf.size) {
                val r = input.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
            RawText.isBinary(buf, n)
        }

    /**
     * "dir/a.md" -> "dir/a (local).md", then "dir/a (local 2).md" ... until [taken] returns false.
     * Names without an extension (or dotfiles) get the suffix at the end.
     */
    fun localCopyName(path: String, taken: (String) -> Boolean): String {
        val slash = path.lastIndexOf('/')
        val dir = path.substring(0, slash + 1)
        val name = path.substring(slash + 1)
        val dot = name.lastIndexOf('.')
        val (stem, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
        var i = 1
        while (true) {
            val candidate = dir + stem + (if (i == 1) " (local)" else " (local $i)") + ext
            if (!taken(candidate)) return candidate
            i++
        }
    }
}
