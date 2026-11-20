package com.wifiproxy.core.io

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.io.IOException
import java.nio.channels.CancelledKeyException
import java.nio.channels.ClosedChannelException
import java.nio.channels.ClosedSelectorException
import java.nio.channels.SelectableChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private typealias Waiters = HashMap<Int, CancellableContinuation<Unit>>

/**
 * A single selector thread that lets coroutines suspend until a non-blocking channel is ready.
 *
 * resume() is invoked on the loop thread; never await from Dispatchers.Unconfined or another
 * immediate dispatcher, or caller code runs on, and can stall, the loop thread.
 *
 * Callers must cancel any coroutine waiting on a channel before closing that channel.
 */
class NioLoop : Closeable {
    private class Registration(val channel: SelectableChannel, val op: Int, val cont: CancellableContinuation<Unit>)

    private val selector: Selector = Selector.open()
    private val pending = ConcurrentLinkedQueue<Registration>()

    @Volatile
    private var open = true
    private var quietRun = 0 // touched only by the loop thread

    /** Count of keys sweepIdleKeys() has cancelled; exposed so tests can observe a sweep happened. */
    internal val sweptKeys = AtomicLong()

    private val thread = Thread(::run, "nio-loop").apply {
        isDaemon = true
        start()
    }

    suspend fun await(channel: SelectableChannel, op: Int) {
        require(op in OPS) { "unsupported interest op $op" }
        if (!open) throw ClosedChannelException()
        suspendCancellableCoroutine { cont ->
            val r = Registration(channel, op, cont)
            pending.add(r)
            // close() may have set `open = false` and drained `pending` just before this add.
            // ConcurrentLinkedQueue.remove()/poll() claim an element atomically, so at most one
            // of failAll()'s drain and this check resumes `cont` - never both.
            if (!open && pending.remove(r)) {
                cont.resumeWithException(ClosedChannelException())
            } else {
                runCatching { selector.wakeup() }
            }
        }
    }

    @Synchronized
    override fun close() {
        if (!open) return
        open = false
        runCatching { selector.wakeup() }
        if (Thread.currentThread() !== thread) thread.join(1000)
    }

    /** Forces a zero-result `select()` pass so tests can exercise idle-key sweeping deterministically. */
    internal fun nudge() {
        runCatching { selector.wakeup() }
    }

    private fun run() {
        try {
            while (open) {
                val n = selector.select()
                val applied = registerPending()
                val keys = selector.selectedKeys().iterator()
                while (keys.hasNext()) {
                    val key = keys.next()
                    keys.remove()
                    dispatch(key)
                }
                // A zero-interest fd stuck in HUP/ERR can make select() return 0 immediately,
                // forever, on some platforms (e.g. Android's poll-based selector). An isolated
                // quiet pass also happens under load from an orphan wakeup, so require a short
                // run of consecutive quiet passes before sweeping; the real spin still produces
                // an unbounded run of them and gets caught within microseconds.
                if (n == 0 && applied == 0) {
                    if (++quietRun >= 4) {
                        quietRun = 0
                        sweepIdleKeys()
                    }
                } else {
                    quietRun = 0
                }
            }
        } catch (e: ClosedSelectorException) {
            // fall through to cleanup
        } catch (e: IOException) {
            // selector failure: fail every waiter below
        } finally {
            open = false
            failAll()
            runCatching { selector.close() }
        }
    }

    /** Returns how many registrations it dequeued, so the caller can detect a quiet pass. */
    private fun registerPending(): Int {
        var applied = 0
        while (true) {
            val r = pending.poll() ?: return applied
            applied++
            if (!r.cont.isActive) continue
            try {
                // A cancelled key is deregistered by the next select() before this method runs,
                // so keyFor() here is either null or still valid; register() throws if the
                // channel itself was closed concurrently, which the catch below handles.
                val resolvedKey = r.channel.keyFor(selector) ?: r.channel.register(selector, 0, Waiters())
                @Suppress("UNCHECKED_CAST")
                val waiters = resolvedKey.attachment() as Waiters
                val existing = waiters[r.op]
                if (existing != null && existing.isActive) {
                    r.cont.resumeWithException(IllegalStateException("already waiting for op ${r.op} on this channel"))
                    continue
                }
                // Only record the waiter once the selector has accepted the new interest set, so a
                // rejected op (e.g. OP_ACCEPT on a SocketChannel) never leaves a dangling entry.
                resolvedKey.interestOps(resolvedKey.interestOps() or r.op)
                waiters[r.op] = r.cont
            } catch (e: Throwable) {
                if (r.cont.isActive) {
                    r.cont.resumeWithException(if (e is CancelledKeyException) ClosedChannelException() else e)
                }
            }
        }
    }

    private fun dispatch(key: SelectionKey) {
        @Suppress("UNCHECKED_CAST")
        val waiters = key.attachment() as Waiters
        try {
            val ready = key.readyOps()
            var interest = key.interestOps()
            for (op in OPS) {
                if ((ready and op) == 0) continue
                interest = interest and op.inv()
                waiters.remove(op)?.resume(Unit)
            }
            val it = waiters.iterator()
            while (it.hasNext()) {
                val (op, cont) = it.next()
                if (!cont.isActive) {
                    it.remove()
                    interest = interest and op.inv()
                }
            }
            key.interestOps(interest)
        } catch (e: Throwable) {
            // One bad key (e.g. cancelled concurrently) must not kill the loop thread: fail just
            // this key's waiters and move on.
            val toThrow = if (e is CancelledKeyException) ClosedChannelException() else e
            waiters.values.forEach { cont -> runCatching { cont.resumeWithException(toThrow) } }
            waiters.clear()
            runCatching { key.cancel() }
        }
    }

    private fun sweepIdleKeys() {
        selector.keys().forEach { key ->
            runCatching {
                if (key.isValid && key.interestOps() == 0) {
                    @Suppress("UNCHECKED_CAST")
                    val waiters = key.attachment() as? Waiters
                    if (waiters == null || waiters.values.none { it.isActive }) {
                        key.cancel()
                        sweptKeys.incrementAndGet()
                    }
                }
            }
        }
    }

    private fun failAll() {
        runCatching {
            selector.keys().forEach { key ->
                @Suppress("UNCHECKED_CAST")
                (key.attachment() as? Waiters)?.let { waiters ->
                    waiters.values.forEach { cont -> runCatching { cont.resumeWithException(ClosedChannelException()) } }
                    waiters.clear()
                }
            }
        }
        while (true) {
            val r = pending.poll() ?: break
            runCatching { r.cont.resumeWithException(ClosedChannelException()) }
        }
    }

    private companion object {
        val OPS = intArrayOf(SelectionKey.OP_READ, SelectionKey.OP_WRITE, SelectionKey.OP_CONNECT, SelectionKey.OP_ACCEPT)
    }
}
