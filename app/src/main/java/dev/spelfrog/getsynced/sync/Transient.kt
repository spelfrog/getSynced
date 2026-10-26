package dev.spelfrog.getsynced.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.EOFException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import javax.net.ssl.SSLException

/** Classification of transient network failures and a single automatic retry for them. */
/** Default pause between attempts; a named value, not a lambda literal, which the Kotlin 2.4 backend cannot lower as a suspend default. */
val DefaultSleep: suspend (Long) -> Unit = { delay(it) }

object Transient {

    private val TRANSIENT_MARKERS = listOf(
        "connection abort", "connection reset", "timed out", "unexpected end of file",
        "unable to resolve host", "network is unreachable", "temporarily unavailable",
        "503", "502", "504",
    )

    /** Markers that make an error definitely non-transient, even if a transient marker also matches. */
    private val PERMANENT_MARKERS = listOf(
        "not authorized", "authentication", "auth fail", "401", "403", "404", "not found",
        "conflict", "rejected",
    )

    /**
     * True if [t] (or any of its causes) looks like a temporary network problem worth retrying:
     * dropped connection, DNS failure, timeout, TLS hiccup or a 502/503/504 from the server.
     * Auth errors, 404s and merge/conflict/rejection errors are never transient.
     */
    fun isTransientNetworkError(t: Throwable): Boolean {
        if (t is CancellationException) return false
        val chain = causeChain(t)
        val messages = chain.mapNotNull { it.message?.lowercase(Locale.ROOT) }
        if (messages.any { m -> PERMANENT_MARKERS.any { it in m } }) return false
        if (chain.any { isTransientType(it) }) return true
        return messages.any { m -> TRANSIENT_MARKERS.any { it in m } }
    }

    private fun isTransientType(t: Throwable): Boolean =
        t is SocketException || // includes ConnectException
            t is SocketTimeoutException ||
            t is UnknownHostException ||
            t is ConnectException ||
            t is EOFException ||
            t is SSLException ||
            t is InterruptedIOException // includes SocketTimeoutException

    private fun causeChain(t: Throwable): List<Throwable> {
        val out = mutableListOf<Throwable>()
        val seen = HashSet<Throwable>()
        var cur: Throwable? = t
        while (cur != null && seen.add(cur)) {
            out += cur
            cur = cur.cause
        }
        return out
    }

    /** The most specific non-blank message in the cause chain (e.g. "Software caused connection abort"). */
    fun shortMessage(t: Throwable, max: Int = 80): String {
        val chain = causeChain(t)
        val msg = chain.asReversed().firstNotNullOfOrNull { it.message?.trim()?.takeIf(String::isNotEmpty) }
            ?: chain.last().javaClass.simpleName.ifEmpty { chain.last().javaClass.name }
        return if (msg.length <= max) msg else msg.take(max - 1) + "…"
    }

    /**
     * Runs [block]; if it throws a transient network error, calls [onRetry], waits [delayMs] via [sleep] and
     * runs [block] once more (whose outcome, success or exception, is final). Non-transient errors and
     * [CancellationException] propagate immediately.
     */
    suspend fun <T> retryOnce(
        delayMs: Long = 2000,
        onRetry: (Throwable) -> Unit = {},
        sleep: suspend (Long) -> Unit = DefaultSleep,
        block: suspend () -> T,
    ): T {
        // No `return` inside the try: the Kotlin 2.4 JVM backend fails to lower that with suspend lambdas.
        val first = try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Result.failure(t)
        }
        val failure = first.exceptionOrNull() ?: return first.getOrThrow()
        if (!isTransientNetworkError(failure)) throw failure
        currentCoroutineContext().ensureActive()
        onRetry(failure)
        sleep(delayMs)
        return block()
    }
}
