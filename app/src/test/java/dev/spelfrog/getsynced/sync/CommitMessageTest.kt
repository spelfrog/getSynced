package dev.spelfrog.getsynced.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class CommitMessageTest {
    private val now = LocalDateTime.of(2026, 10, 26, 14, 3, 5, 123_456_789)

    private fun changes(
        added: List<String> = emptyList(),
        modified: List<String> = emptyList(),
        deleted: List<String> = emptyList(),
    ) = ChangeSet(added = added, modified = modified, deleted = deleted)

    private fun msg(c: ChangeSet, device: String = "SM-S908B") = CommitMessage.build(device, c, now)
    private fun subject(c: ChangeSet, device: String = "SM-S908B") = msg(c, device).substringBefore('\n')

    @Test
    fun singleEdited() {
        assertEquals("SM-S908B: edited Bücher collection.md", subject(changes(modified = listOf("Bücher collection.md"))))
    }

    @Test
    fun singleAddedKeepsDirectory() {
        assertEquals("SM-S908B: added img/Streets.png", subject(changes(added = listOf("img/Streets.png"))))
    }

    @Test
    fun singleDeleted() {
        assertEquals("SM-S908B: deleted old.md", subject(changes(deleted = listOf("old.md"))))
    }

    @Test
    fun twoAndThreeOfOneKindAreListed() {
        assertEquals("SM-S908B: edited a.md, b.md", subject(changes(modified = listOf("a.md", "b.md"))))
        assertEquals("SM-S908B: edited a.md, b.md, c.md", subject(changes(modified = listOf("a.md", "b.md", "c.md"))))
        assertEquals(
            "SM-S908B: deleted DM D&D/img/Läuterkraut.svg, x.txt",
            subject(changes(deleted = listOf("DM D&D/img/Läuterkraut.svg", "x.txt"))),
        )
    }

    @Test
    fun fourOfOneKindAreCounted() {
        assertEquals("SM-S908B: edited 4 notes", subject(changes(modified = listOf("a.md", "b.md", "c.md", "d.md"))))
    }

    @Test
    fun mixedKindsAreCountedInOrderEditedAddedDeleted() {
        val c = changes(
            added = listOf("img/a.png"),
            modified = listOf("a.md", "b.md"),
            deleted = listOf("old.pdf"),
        )
        assertEquals("SM-S908B: edited 2 notes, added 1 image, deleted 1 file", subject(c))
        assertEquals("SM-S908B: added 1 note, deleted 1 note", subject(changes(added = listOf("n.md"), deleted = listOf("o.md"))))
    }

    @Test
    fun typeWordsAndPluralisation() {
        val images = listOf("a.PNG", "b.jpg", "c.jpeg", "d.gif", "e.svg", "f.webp")
        assertEquals("SM-S908B: added 6 images", subject(changes(added = images)))
        assertEquals("SM-S908B: added 4 files", subject(changes(added = listOf("a.md", "b.png", "c.md", "d.md"))))
        assertEquals("SM-S908B: added 4 files", subject(changes(added = listOf("a.pdf", "b.txt", "Makefile", "d.zip"))))
        assertEquals(
            "SM-S908B: edited 1 file, added 1 image",
            subject(changes(modified = listOf("x.canvas"), added = listOf("y.webp"))),
        )
    }

    @Test
    fun tooLongSubjectFallsBackToCounts() {
        val long = "Some very long folder name/with a nested folder/and a long note title.md"
        assertEquals("SM-S908B: edited 1 note", subject(changes(modified = listOf(long))))
        val three = listOf("Projects/alpha notes.md", "Projects/beta notes.md", "Projects/gamma notes.md")
        assertTrue(("SM-S908B: edited " + three.joinToString(", ")).length > 72)
        assertEquals("SM-S908B: edited 3 notes", subject(changes(modified = three)))
    }

    @Test
    fun subjectNeverExceeds72Chars() {
        val device = "x".repeat(100)
        val s = subject(changes(modified = listOf("a.md")), device)
        assertTrue(s, s.length <= 72)
    }

    @Test
    fun obsidianFilesStayOutOfSubjectWhenOtherChangesExist() {
        val c = changes(
            modified = listOf(".obsidian/workspace.json", "Daily.md"),
            added = listOf(".obsidian/plugins/x/data.json"),
        )
        val m = msg(c)
        assertEquals("SM-S908B: edited Daily.md", m.substringBefore('\n'))
        assertTrue(m, m.contains("\nM .obsidian/workspace.json\n"))
        assertTrue(m, m.contains("\nA .obsidian/plugins/x/data.json\n"))
    }

    @Test
    fun onlyObsidianFilesMeansSettings() {
        val c = changes(modified = listOf(".obsidian/workspace.json", ".obsidian/app.json"))
        assertEquals("SM-S908B: updated Obsidian settings", subject(c))
    }

    @Test
    fun deviceNameIsTrimmedAndDefaultsToPhone() {
        assertEquals("Raphaels S22: deleted a.md", subject(changes(deleted = listOf("a.md")), "  Raphaels S22 \n"))
        assertEquals("local: deleted a.md", subject(changes(deleted = listOf("a.md")), "   "))
    }

    @Test
    fun bodyListsEveryPathAndTheTime() {
        val c = changes(added = listOf("img/a.png"), modified = listOf("a.md"), deleted = listOf("old.md"))
        assertEquals(
            "SM-S908B: edited 1 note, added 1 image, deleted 1 note\n" +
                "\n" +
                "M a.md\n" +
                "A img/a.png\n" +
                "D old.md\n" +
                "synced 2026-10-26T14:03:05 by getSynced",
            msg(c),
        )
    }

    @Test
    fun bodyShowsSecondsEvenWhenZero() {
        val m = CommitMessage.build("p", changes(modified = listOf("a.md")), LocalDateTime.of(2026, 1, 2, 3, 4))
        assertTrue(m, m.endsWith("\nsynced 2026-01-02T03:04:00 by getSynced"))
    }

    @Test
    fun bodyIsCappedAt100Paths() {
        val paths = (1..150).map { "n%03d.md".format(it) }
        val lines = msg(changes(added = paths)).lines()
        assertEquals("SM-S908B: added 150 notes", lines[0])
        assertEquals("", lines[1])
        val list = lines.drop(2)
        assertEquals(102, list.size) // 100 paths, "… and 50 more", "synced …"
        assertEquals("A n001.md", list[0])
        assertEquals("A n100.md", list[99])
        assertEquals("… and 50 more", list[100])
        assertTrue(list[101], list[101].startsWith("synced "))
    }

    @Test
    fun subjectHasNoTrailingPeriod() {
        val s = subject(changes(added = listOf("a.md"), deleted = listOf("b.md")))
        assertTrue(s, !s.endsWith("."))
    }
}
