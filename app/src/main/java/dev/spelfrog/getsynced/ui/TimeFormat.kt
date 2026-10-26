package dev.spelfrog.getsynced.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * English time formatting, independent of the device locale (the app is English only; mixing
 * "Vor 25 Minuten" into English labels reads badly). Pure Kotlin so it can be unit tested.
 */

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE

/** Within this span an hour count reads better than "yesterday" (22:00 seen at 02:00 is "4 h ago"). */
private const val HOURS_BEFORE_YESTERDAY = 6 * HOUR

private val DayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.US)
private val DayMonthYear = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)
private val Clock24 = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

/**
 * "just now", "3 min ago", "2 h ago", "yesterday", "26 Oct" (same year) or "26 Oct 2025".
 * Times in the future (clock skew) count as "just now".
 */
fun relativeTime(nowMs: Long, thenMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val diff = nowMs - thenMs
    if (diff < MINUTE) return "just now"
    if (diff < HOUR) return "${diff / MINUTE} min ago"
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val then = Instant.ofEpochMilli(thenMs).atZone(zone).toLocalDate()
    if (then == today || diff < HOURS_BEFORE_YESTERDAY) return "${diff / HOUR} h ago"
    if (then == today.minusDays(1)) return "yesterday"
    return (if (then.year == today.year) DayMonth else DayMonthYear).format(then)
}

/** "19:30", 24 h clock. */
fun clockTime(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Clock24.format(Instant.ofEpochMilli(ms).atZone(zone))

/** "25 min ago · 19:30". */
fun relativeWithClock(nowMs: Long, thenMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    "${relativeTime(nowMs, thenMs, zone)} · ${clockTime(thenMs, zone)}"
