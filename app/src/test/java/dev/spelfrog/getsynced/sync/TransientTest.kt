package dev.spelfrog.getsynced.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.errors.TransportException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

class TransientTest {

    private fun transient(t: Throwable) = Transient.isTransientNetworkError(t)

    // ---- classification ----

    @Test
    fun connectionAbortWrappedInTransportExceptionIsTransient() {
        assertTrue(transient(TransportException("https://github.com/x/y.git: cannot open git-upload-pack",
            SocketException("Software caused connection abort"))))
    }

    @Test
    fun jgitInternalTransportExceptionWithSocketCauseIsTransient() {
        assertTrue(transient(org.eclipse.jgit.errors.TransportException("https://x: push failed",
            SocketException("Software caused connection abort"))))
    }

    @Test
    fun unknownHostIsTransient() {
        assertTrue(transient(UnknownHostException("github.com")))
        assertTrue(transient(TransportException("https://github.com/x: cannot open", UnknownHostException("github.com"))))
    }

    @Test
    fun timeoutAndSslAreTransient() {
        assertTrue(transient(TransportException("x", SocketTimeoutException("Read timed out"))))
        assertTrue(transient(TransportException("x", SSLException("Read error: ssl=0x7b: I/O error during system call"))))
    }

    @Test
    fun messageMarkersAreTransient() {
        assertTrue(transient(TransportException("https://x: Connection reset")))
        assertTrue(transient(IOException("unexpected end of file from server")))
        assertTrue(transient(TransportException("https://x: 503 Service Unavailable")))
        assertTrue(transient(IOException("Network is unreachable")))
    }

    @Test
    fun authErrorsAreNotTransient() {
        assertFalse(transient(TransportException("https://x: not authorized")))
        assertFalse(transient(TransportException("https://x: Authentication is required but no CredentialsProvider has been registered")))
        assertFalse(transient(TransportException("https://x: 401 Unauthorized")))
        assertFalse(transient(TransportException("https://x: 403 Forbidden")))
    }

    @Test
    fun notFoundMergeAndRejectionAreNotTransient() {
        assertFalse(transient(TransportException("https://x: 404 Not Found")))
        assertFalse(transient(IllegalStateException("merge CONFLICTING: a.md")))
        assertFalse(transient(IllegalStateException("Merge conflict in 2 files: a.md, b.md")))
        assertFalse(transient(TransportException("https://x: rejected: non-fast-forward")))
        assertFalse(transient(IllegalStateException("HEAD is detached, refusing to push")))
    }

    @Test
    fun cancellationIsNotTransient() {
        assertFalse(transient(CancellationException("cancelled")))
    }

    @Test
    fun shortMessageUsesInnermostMessage() {
        val t = TransportException("https://x: cannot open git-upload-pack", SocketException("Software caused connection abort"))
        assertEquals("Software caused connection abort", Transient.shortMessage(t))
    }

    // ---- retryOnce ----

    private val abort = { TransportException("x", SocketException("Software caused connection abort")) }

    @Test
    fun retryOnceSucceedsOnSecondAttempt() = runBlocking {
        val sleeps = mutableListOf<Long>()
        val retried = mutableListOf<Throwable>()
        var calls = 0
        val result = Transient.retryOnce(delayMs = 1234, onRetry = { retried += it }, sleep = { sleeps += it }) {
            if (calls++ == 0) throw abort()
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(2, calls)
        assertEquals(listOf(1234L), sleeps)
        assertEquals(1, retried.size)
    }

    @Test
    fun retryOnceDoesNotRetryNonTransient() = runBlocking {
        val sleeps = mutableListOf<Long>()
        var retries = 0
        var calls = 0
        val boom = TransportException("https://x: not authorized")
        try {
            Transient.retryOnce<String>(onRetry = { retries++ }, sleep = { sleeps += it }) {
                calls++
                throw boom
            }
            fail("expected exception")
        } catch (e: TransportException) {
            assertSame(boom, e)
        }
        assertEquals(1, calls)
        assertEquals(0, retries)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun retryOnceThrowsSecondTransientError() = runBlocking {
        val sleeps = mutableListOf<Long>()
        var calls = 0
        val first = abort()
        val second = TransportException("y", SocketException("Connection reset"))
        try {
            Transient.retryOnce<String>(sleep = { sleeps += it }) {
                throw if (calls++ == 0) first else second
            }
            fail("expected exception")
        } catch (e: TransportException) {
            assertSame(second, e)
        }
        assertEquals(2, calls)
        assertEquals(listOf(2000L), sleeps)
    }

    @Test
    fun retryOnceDoesNotRetryCancellation() = runBlocking {
        val sleeps = mutableListOf<Long>()
        var calls = 0
        try {
            Transient.retryOnce<String>(sleep = { sleeps += it }) {
                calls++
                throw CancellationException("stop")
            }
            fail("expected exception")
        } catch (e: CancellationException) {
            assertEquals("stop", e.message)
        }
        assertEquals(1, calls)
        assertTrue(sleeps.isEmpty())
    }
}
