package dev.spelfrog.getsynced.sync

import org.eclipse.jgit.diff.HistogramDiff
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.diff.RawTextComparator

/**
 * The three versions of one conflicting file as text: the merge base, HEAD ("local") and the remote commit.
 * A null text means the file does not exist on that side (or there is no merge base).
 * [binary] = at least one side looks binary; all texts are null then.
 * [truncated] = at least one side was cut at the size cap (the diff then covers only the start of the file).
 */
data class ConflictVersions(
    val base: String?,
    val local: String?,
    val remote: String?,
    val binary: Boolean = false,
    val truncated: Boolean = false,
)

data class DiffLine(
    val kind: Kind,
    val text: String,
    /** > 0 only for a collapsed run of unchanged lines: how many lines it stands for. */
    val hidden: Int = 0,
) {
    enum class Kind { SAME, ADDED, REMOVED }

    val isCollapsed: Boolean get() = hidden > 0
}

/**
 * Line diffs for the conflict view. [localChanges] = base -> local, [remoteChanges] = base -> remote
 * (both empty when there is no merge base), [direct] = local -> remote.
 */
data class ThreeWay(
    val localChanges: List<DiffLine>,
    val remoteChanges: List<DiffLine>,
    val direct: List<DiffLine>,
    val hasBase: Boolean,
    val localDeleted: Boolean,
    val remoteDeleted: Boolean,
)

object ConflictDiff {
    /** Unchanged runs longer than this are collapsed. */
    const val COLLAPSE_OVER = 6
    /** Unchanged lines kept next to each change when a run is collapsed. */
    const val CONTEXT = 3

    /** Full listing of [from] -> [to] (null = empty) with long unchanged runs collapsed. */
    fun lines(from: String?, to: String?): List<DiffLine> {
        val a = rawText(from)
        val b = rawText(to)
        val edits = HistogramDiff().diff(RawTextComparator.DEFAULT, a, b)
        val out = ArrayList<DiffLine>(maxOf(a.size(), b.size()) + edits.size)
        var ia = 0
        for (e in edits) {
            while (ia < e.beginA) out += DiffLine(DiffLine.Kind.SAME, line(a, ia++))
            for (i in e.beginA until e.endA) out += DiffLine(DiffLine.Kind.REMOVED, line(a, i))
            for (i in e.beginB until e.endB) out += DiffLine(DiffLine.Kind.ADDED, line(b, i))
            ia = e.endA
        }
        while (ia < a.size()) out += DiffLine(DiffLine.Kind.SAME, line(a, ia++))
        return collapse(out)
    }

    fun threeWay(v: ConflictVersions): ThreeWay {
        val hasBase = v.base != null
        return ThreeWay(
            localChanges = if (hasBase) lines(v.base, v.local) else emptyList(),
            remoteChanges = if (hasBase) lines(v.base, v.remote) else emptyList(),
            direct = lines(v.local, v.remote),
            hasBase = hasBase,
            localDeleted = hasBase && v.local == null,
            remoteDeleted = hasBase && v.remote == null,
        )
    }

    /** "··· 12 unchanged lines ···" (the UI shows a localized text using [DiffLine.hidden]). */
    fun collapsedText(n: Int): String = "··· $n unchanged ${if (n == 1) "line" else "lines"} ···"

    private fun rawText(s: String?): RawText =
        if (s.isNullOrEmpty()) RawText.EMPTY_TEXT else RawText(s.toByteArray(Charsets.UTF_8))

    /** Line [i] without its line break (also drops a trailing CR of CRLF files). */
    private fun line(t: RawText, i: Int): String = t.getString(i).removeSuffix("\r")

    private fun collapse(lines: List<DiffLine>): List<DiffLine> {
        val out = ArrayList<DiffLine>(lines.size)
        var i = 0
        while (i < lines.size) {
            if (lines[i].kind != DiffLine.Kind.SAME) {
                out += lines[i++]
                continue
            }
            var j = i
            while (j < lines.size && lines[j].kind == DiffLine.Kind.SAME) j++
            val run = j - i
            if (run <= COLLAPSE_OVER) {
                for (k in i until j) out += lines[k]
            } else {
                // Context only next to a change: none before the first change or after the last one.
                val head = if (i == 0) 0 else CONTEXT
                val tail = if (j == lines.size) 0 else CONTEXT
                for (k in i until i + head) out += lines[k]
                val hidden = run - head - tail
                out += DiffLine(DiffLine.Kind.SAME, collapsedText(hidden), hidden)
                for (k in j - tail until j) out += lines[k]
            }
            i = j
        }
        return out
    }
}
