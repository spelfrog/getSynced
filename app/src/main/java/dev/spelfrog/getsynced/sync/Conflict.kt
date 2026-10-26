package dev.spelfrog.getsynced.sync

import kotlinx.serialization.Serializable

/** Which side wins for one conflicting path. BOTH keeps the remote at the path and the phone copy as "<name> (phone).<ext>". */
enum class Side { PHONE, REMOTE, BOTH }

enum class ConflictKind { BOTH_MODIFIED, BOTH_ADDED, DELETED_ON_PHONE, DELETED_ON_REMOTE }

@Serializable
data class ConflictFile(
    val path: String,
    val kind: ConflictKind,
    val binary: Boolean,
    /** Blob sizes in bytes; null when that side deleted the file. */
    val phoneSize: Long? = null,
    val remoteSize: Long? = null,
) {
    /** "Keep both" only makes sense when both sides still have the file. */
    val canKeepBoth: Boolean get() = kind == ConflictKind.BOTH_MODIFIED || kind == ConflictKind.BOTH_ADDED
}

/** Payload of a CONFLICT run: what conflicts and against which commits (so stale choices can be detected). */
@Serializable
data class ConflictInfo(
    val files: List<ConflictFile>,
    val headSha: String,
    val remoteSha: String,
)

/** The user's choices, pinned to the remote commit they were made against. */
@Serializable
data class Resolution(
    val remoteSha: String,
    val choices: Map<String, Side>,
)
