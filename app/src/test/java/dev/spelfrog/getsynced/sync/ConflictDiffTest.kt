package dev.spelfrog.getsynced.sync

import dev.spelfrog.getsynced.sync.DiffLine.Kind.ADDED
import dev.spelfrog.getsynced.sync.DiffLine.Kind.REMOVED
import dev.spelfrog.getsynced.sync.DiffLine.Kind.SAME
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConflictDiffTest {
    private fun numbered(range: IntRange) = range.joinToString("") { "l$it\n" }

    private fun List<DiffLine>.render() = joinToString("\n") { l ->
        when (l.kind) {
            ADDED -> "+${l.text}"
            REMOVED -> "-${l.text}"
            SAME -> if (l.isCollapsed) "~${l.hidden}" else " ${l.text}"
        }
    }

    @Test
    fun classifiesAddedRemovedAndSame() {
        val out = ConflictDiff.lines("a\nb\nc\n", "a\nB\nc\nd\n")
        assertEquals(
            listOf(
                DiffLine(SAME, "a"),
                DiffLine(REMOVED, "b"),
                DiffLine(ADDED, "B"),
                DiffLine(SAME, "c"),
                DiffLine(ADDED, "d"),
            ),
            out,
        )
    }

    @Test
    fun identicalTextIsAllSame() {
        val out = ConflictDiff.lines("x\ny\n", "x\ny\n")
        assertEquals(listOf(DiffLine(SAME, "x"), DiffLine(SAME, "y")), out)
    }

    @Test
    fun missingFinalNewlineAndCrLf() {
        val out = ConflictDiff.lines("a\r\nb", "a\r\nc")
        assertEquals(listOf(DiffLine(SAME, "a"), DiffLine(REMOVED, "b"), DiffLine(ADDED, "c")), out)
    }

    @Test
    fun shortUnchangedRunsAreKept() {
        // 6 unchanged lines between two changes: not collapsed.
        val from = "x\n" + numbered(1..6) + "y\n"
        val to = "X\n" + numbered(1..6) + "Y\n"
        val out = ConflictDiff.lines(from, to)
        assertTrue(out.none { it.isCollapsed })
        assertEquals(10, out.size)
    }

    @Test
    fun longMiddleRunKeepsThreeLinesOfContextEachSide() {
        val from = "x\n" + numbered(1..10) + "y\n"
        val to = "X\n" + numbered(1..10) + "Y\n"
        val expected = listOf("-x", "+X", " l1", " l2", " l3", "~4", " l8", " l9", " l10", "-y", "+Y")
        assertEquals(expected.joinToString("\n"), ConflictDiff.lines(from, to).render())
        val collapsed = ConflictDiff.lines(from, to).single { it.isCollapsed }
        assertEquals(SAME, collapsed.kind)
        assertEquals("··· 4 unchanged lines ···", collapsed.text)
    }

    @Test
    fun leadingAndTrailingRunsKeepContextOnlyNextToTheChange() {
        val from = numbered(1..10) + "mid\n" + numbered(11..20)
        val to = numbered(1..10) + "MID\n" + numbered(11..20)
        val expected = listOf("~7", " l8", " l9", " l10", "-mid", "+MID", " l11", " l12", " l13", "~7")
        assertEquals(expected.joinToString("\n"), ConflictDiff.lines(from, to).render())
    }

    @Test
    fun unchangedFileCollapsesToOneLine() {
        val text = numbered(1..20)
        val out = ConflictDiff.lines(text, text)
        assertEquals(listOf(DiffLine(SAME, "··· 20 unchanged lines ···", 20)), out)
    }

    @Test
    fun nullSidesAreEmpty() {
        assertEquals(listOf(DiffLine(ADDED, "a"), DiffLine(ADDED, "b")), ConflictDiff.lines(null, "a\nb\n"))
        assertEquals(listOf(DiffLine(REMOVED, "a")), ConflictDiff.lines("a\n", null))
        assertEquals(emptyList<DiffLine>(), ConflictDiff.lines(null, null))
        assertEquals(emptyList<DiffLine>(), ConflictDiff.lines("", null))
    }

    @Test
    fun threeWayWithBase() {
        val v = ConflictVersions(base = "a\nb\nc\n", local = "a\nL\nc\n", remote = "a\nb\nc\nR\n")
        val t = ConflictDiff.threeWay(v)
        assertTrue(t.hasBase)
        assertFalse(t.localDeleted)
        assertFalse(t.remoteDeleted)
        assertEquals(" a\n-b\n+L\n c", t.localChanges.render())
        assertEquals(" a\n b\n c\n+R", t.remoteChanges.render())
        assertEquals(" a\n-L\n+b\n c\n+R", t.direct.render())
    }

    @Test
    fun threeWayWithoutBaseOnlyHasDirect() {
        val t = ConflictDiff.threeWay(ConflictVersions(base = null, local = "x\n", remote = "y\n"))
        assertFalse(t.hasBase)
        assertTrue(t.localChanges.isEmpty())
        assertTrue(t.remoteChanges.isEmpty())
        assertEquals("-x\n+y", t.direct.render())
    }

    @Test
    fun threeWayDeletedSide() {
        val t = ConflictDiff.threeWay(ConflictVersions(base = "a\n", local = null, remote = "a\nb\n"))
        assertTrue(t.localDeleted)
        assertFalse(t.remoteDeleted)
        assertEquals("-a", t.localChanges.render())
        assertEquals(" a\n+b", t.remoteChanges.render())
    }
}
