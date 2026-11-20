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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private typealias Waiters = HashMap<Int, CancellableContinuation<Unit>>

/**
 * A single selector thread that lets coroutines suspend until a non-blocking channel is ready.
 * Callers must cancel any coroutine waiting on a channel before closing that channel.
 */
class NioLoop : Closeable {
    private class Registration(val channel: SelectableChannel, val op: Int, val cont: CancellableContinuation<Unit>)

    private val selector: Selector = Selector.open()
    private val pending = ConcurrentLinkedQueue<Registration>()

    @Volatile
    private var open = true
    private val thread = Thread(::run, "nio-loop").apply {
        isDaemon = true
        start()
    }

    suspend fun await(channel: SelectableChannel, op: Int) {
        require(op in OPS) { "unsupported interest op $op" }
        if (!open) throw ClosedChannelException()
        suspendCancellableCoroutine { cont ->
            pending.add(Registration(channel, op, cont))
            selector.wakeup()
        }
    }

    @Synchronized
    override fun close() {
        if (!open) return
        open = false
        selector.wakeup()
        if (Thread.currentThread() !== thread) thread.join(1000)
    }

    private fun run() {
        try {
            while (open) {
                selector.select()
                registerPending()
                val keys = selector.selectedKeys().iterator()
                while (keys.hasNext()) {
                    val key = keys.next()
                    keys.remove()
                    dispatch(key)
                }
            }
        } catch (e: ClosedSelectorException) {
            // fall through to cleanup
        } catch (e: IOException) {
            // selector failure: fail every waiter below
        } finally {
            failAll()
            runCatching { selector.close() }
        }
    }

    private fun registerPending() {
        while (true) {
            val r = pending.poll() ?: return
            if (!r.cont.isActive) continue
            try {
                val key = r.channel.keyFor(selector) ?: r.channel.register(selector, 0, Waiters())
                @Suppress("UNCHECKED_CAST")
                (key.attachment() as Waiters)[r.op] = r.cont
                key.interestOps(key.interestOps() or r.op)
            } catch (e: Exception) {
                r.cont.resumeWithException(if (e is CancelledKeyException) ClosedChannelException() else e)
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
            val stale = waiters.filterValues { !it.isActive }.keys.toList()
            stale.forEach { op ->
                waiters.remove(op)
                interest = interest and op.inv()
            }
            key.interestOps(interest)
        } catch (e: CancelledKeyException) {
            waiters.values.forEach { it.resumeWithException(ClosedChannelException()) }
            waiters.clear()
        }
    }

    private fun failAll() {
        runCatching {
            selector.keys().forEach { key ->
                @Suppress("UNCHECKED_CAST")
                (key.attachment() as? Waiters)?.let { waiters ->
                    waiters.values.forEach { it.resumeWithException(ClosedChannelException()) }
                    waiters.clear()
                }
            }
        }
        while (true) {
            val r = pending.poll() ?: break
            r.cont.resumeWithException(ClosedChannelException())
        }
    }

    private companion object {
        val OPS = intArrayOf(SelectionKey.OP_READ, SelectionKey.OP_WRITE, SelectionKey.OP_CONNECT, SelectionKey.OP_ACCEPT)
    }
}
