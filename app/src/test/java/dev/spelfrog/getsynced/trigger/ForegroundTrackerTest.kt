package dev.spelfrog.getsynced.trigger

import dev.spelfrog.getsynced.trigger.ForegroundTracker.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ForegroundTrackerTest {

    private class FakeScheduler {
        var pending: (() -> Unit)? = null
        var lastDelay: Long = -1
        var scheduledCount = 0

        fun schedule(delayMs: Long, action: () -> Unit): Cancellable {
            lastDelay = delayMs
            scheduledCount++
            pending = action
            return Cancellable { if (pending === action) pending = null }
        }

        /** Simulates the timer expiring. */
        fun fire() {
            val action = pending ?: error("no pending action")
            pending = null
            action()
        }
    }

    private val target = "md.obsidian"
    private val launcher = "com.google.android.apps.nexuslauncher"
    private val keyboard = "com.google.android.inputmethod.latin"
    private val other = "org.mozilla.firefox"

    private lateinit var scheduler: FakeScheduler
    private lateinit var events: MutableList<Event>
    private lateinit var tracker: ForegroundTracker
    private var now = 0L

    @Before
    fun setUp() {
        scheduler = FakeScheduler()
        events = mutableListOf()
        tracker = ForegroundTracker(
            target = target,
            ignored = setOf(keyboard, "com.android.systemui", "android"),
            debounceMs = 3000,
            onEvent = { events += it },
            scheduler = scheduler::schedule,
        )
    }

    private fun front(pkg: String?) {
        now += 100
        tracker.onForeground(pkg, now)
    }

    @Test
    fun openFromLauncherEmitsOpenedOnce() {
        front(launcher)
        front(target)
        assertEquals(listOf(Event.Opened), events)
        assertTrue(tracker.targetOpen)
    }

    @Test
    fun repeatedTargetEventsEmitSingleOpened() {
        front(launcher)
        front(target)
        front(target)
        front(target)
        assertEquals(listOf(Event.Opened), events)
        assertEquals(0, scheduler.scheduledCount)
    }

    @Test
    fun keyboardOverTargetIsIgnored() {
        front(target)
        events.clear()
        front(keyboard)
        front(null)
        front("")
        front("com.android.systemui")
        front(target)
        assertEquals(emptyList<Event>(), events)
        assertEquals(0, scheduler.scheduledCount)
        assertEquals(target, tracker.lastForeground)
    }

    @Test
    fun leavingTargetEmitsClosedAfterDebounce() {
        front(target)
        front(other)
        assertEquals(listOf(Event.Opened), events)
        assertTrue(tracker.closePending)
        assertEquals(3000, scheduler.lastDelay)

        scheduler.fire()
        assertEquals(listOf(Event.Opened, Event.Closed), events)
        assertFalse(tracker.targetOpen)

        // Coming back afterwards is a fresh open.
        front(target)
        assertEquals(listOf(Event.Opened, Event.Closed, Event.Opened), events)
    }

    @Test
    fun returningBeforeTimerCancelsCloseWithoutSecondOpened() {
        front(target)
        front(other)
        assertTrue(tracker.closePending)
        front(target)
        assertFalse(tracker.closePending)
        assertEquals(null, scheduler.pending)
        assertEquals(listOf(Event.Opened), events)
    }

    @Test
    fun hoppingBetweenOtherAppsKeepsSingleTimer() {
        front(target)
        front(other)
        front(launcher)
        assertEquals(1, scheduler.scheduledCount)
        scheduler.fire()
        assertEquals(listOf(Event.Opened, Event.Closed), events)
    }

    @Test
    fun noEventsWithBlankTarget() {
        tracker.updateTarget("")
        front(launcher)
        front(target)
        front(other)
        assertEquals(emptyList<Event>(), events)
        assertEquals(0, scheduler.scheduledCount)
    }

    @Test
    fun updateTargetMidway() {
        front(target)
        front(other)
        assertTrue(tracker.closePending)

        // Switching target cancels the pending close of the old target.
        tracker.updateTarget("com.example.notes")
        assertFalse(tracker.closePending)
        assertEquals(null, scheduler.pending)
        assertEquals(listOf(Event.Opened), events)

        // Old target no longer matters.
        front(target)
        front(other)
        assertEquals(listOf(Event.Opened), events)

        // New target behaves normally.
        front("com.example.notes")
        front(launcher)
        scheduler.fire()
        assertEquals(listOf(Event.Opened, Event.Opened, Event.Closed), events)
    }

    @Test
    fun updateTargetToAppAlreadyInFrontDoesNotEmitOpened() {
        front(other)
        tracker.updateTarget(other)
        assertTrue(tracker.targetOpen)
        assertEquals(emptyList<Event>(), events)
        front(launcher)
        scheduler.fire()
        assertEquals(listOf(Event.Closed), events)
    }

    @Test
    fun resetForgetsState() {
        front(target)
        front(other)
        tracker.reset()
        assertEquals(null, scheduler.pending)
        assertFalse(tracker.targetOpen)
        front(target)
        assertEquals(listOf(Event.Opened, Event.Opened), events)
    }
}
