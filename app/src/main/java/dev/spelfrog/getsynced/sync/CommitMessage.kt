package dev.spelfrog.getsynced.sync

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** What a sync commit contains, as repo-relative paths ("DM D&D/img/Läuterkraut.svg"). */
data class ChangeSet(
    val added: List<String>,
    val modified: List<String>,
    val deleted: List<String>,
) {
    val total: Int get() = added.size + modified.size + deleted.size
    val isEmpty: Boolean get() = total == 0
}

/**
 * Builds a commit message that says what changed, e.g.
 *
 * ```
 * SM-S908B: edited 2 notes, added 1 image, deleted 1 file
 *
 * M Bücher collection.md
 * M Daily/2026-10-26.md
 * A img/Streets.png
 * D old.txt
 * synced 2026-10-26T14:03:05 by getSynced
 * ```
 *
 * Files under `.obsidian/` (plugin and config noise) only appear in the body, unless they are the only change.
 */
object CommitMessage {
    const val MAX_SUBJECT = 72
    const val MAX_BODY_LINES = 100
    private const val OBSIDIAN_DIR = ".obsidian/"
    private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "gif", "svg", "webp")
    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)

    private enum class Kind(val verb: String, val letter: String) {
        EDITED("edited", "M"), ADDED("added", "A"), DELETED("deleted", "D"),
    }

    fun build(deviceName: String, changes: ChangeSet, now: LocalDateTime = LocalDateTime.now()): String {
        val device = deviceName.trim().replace(Regex("\\s+"), " ").ifEmpty { "phone" }
        val groups: List<Pair<Kind, List<String>>> = listOf(
            Kind.EDITED to changes.modified,
            Kind.ADDED to changes.added,
            Kind.DELETED to changes.deleted,
        )
        val subject = limit(subjectLine(device, groups))
        val body = buildList {
            val lines = groups.flatMap { (kind, paths) -> paths.map { "${kind.letter} $it" } }
            addAll(lines.take(MAX_BODY_LINES))
            if (lines.size > MAX_BODY_LINES) add("… and ${lines.size - MAX_BODY_LINES} more")
            add("synced ${now.truncatedTo(ChronoUnit.SECONDS).format(TIME)} by getSynced")
        }
        return subject + "\n\n" + body.joinToString("\n")
    }

    private fun isObsidian(path: String) = path.startsWith(OBSIDIAN_DIR) || path == ".obsidian"

    private fun subjectLine(device: String, all: List<Pair<Kind, List<String>>>): String {
        val visible = all.map { (kind, paths) -> kind to paths.filterNot(::isObsidian) }
            .filter { it.second.isNotEmpty() }
        val total = visible.sumOf { it.second.size }
        if (total == 0) {
            return "$device: " + if (all.any { it.second.isNotEmpty() }) "updated Obsidian settings" else "sync"
        }
        val counts = "$device: " + visible.joinToString(", ") { (kind, paths) ->
            "${kind.verb} ${paths.size} ${typeWord(paths)}"
        }
        if (visible.size == 1 && total <= 3) {
            val (kind, paths) = visible.single()
            return "$device: ${kind.verb} ${paths.joinToString(", ")}"
                .takeIf { it.length <= MAX_SUBJECT } ?: counts
        }
        return counts
    }

    /** "note(s)" for .md, "image(s)" for pictures, "file(s)" otherwise or when the group mixes types. */
    private fun typeWord(paths: List<String>): String {
        val types = paths.map(::typeOf).toSet()
        val word = types.singleOrNull() ?: "file"
        return if (paths.size == 1) word else word + "s"
    }

    private fun typeOf(path: String): String {
        val name = path.substringAfterLast('/')
        val ext = if ('.' in name) name.substringAfterLast('.').lowercase(Locale.ROOT) else ""
        return when (ext) {
            "md" -> "note"
            in IMAGE_EXT -> "image"
            else -> "file"
        }
    }

    /** [subjectLine] already falls back to the counts form; this only guards against a very long device name. */
    private fun limit(subject: String): String =
        if (subject.length <= MAX_SUBJECT) subject else subject.take(MAX_SUBJECT - 1).trimEnd() + "…"
}
