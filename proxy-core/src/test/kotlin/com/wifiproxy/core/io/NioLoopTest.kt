package com.wifiproxy.core.io

import com.wifiproxy.core.socketPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.SelectionKey
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NioLoopTest {
    private val loop = NioLoop()

    @AfterTest
    fun tearDown() = loop.close()

    @Test
    fun `await read resumes when data arrives`() = runBlocking {
        val (a, b) = socketPair()
        val got = async(Dispatchers.Default) {
            loop.await(b, SelectionKey.OP_READ)
            val buf = ByteBuffer.allocate(8)
            b.read(buf)
            String(buf.array(), 0, buf.position())
        }
        delay(50)
        a.write(ByteBuffer.wrap("hi".toByteArray()))
        assertEquals("hi", withTimeout(2000) { got.await() })
        a.close(); b.close()
    }

    @Test
    fun `cancelled waiter does not block later waiters`() = runBlocking {
        val (a, b) = socketPair()
        val job = launch(Dispatchers.Default) { loop.await(b, SelectionKey.OP_READ) }
        delay(50)
        job.cancelAndJoin()
        a.write(ByteBuffer.wrap("x".toByteArray()))
        withTimeout(2000) { loop.await(b, SelectionKey.OP_READ) }
        a.close(); b.close()
    }

    @Test
    fun `await on a closed channel throws`() = runBlocking {
        val (a, b) = socketPair()
        b.close()
        assertFailsWith<ClosedChannelException> { withTimeout(2000) { loop.await(b, SelectionKey.OP_READ) } }
        a.close()
    }

    @Test
    fun `close fails pending waiters`() = runBlocking {
        val (a, b) = socketPair()
        val waiter = async(Dispatchers.Default) { runCatching { loop.await(b, SelectionKey.OP_READ) } }
        delay(50)
        loop.close()
        assertIs<ClosedChannelException>(withTimeout(2000) { waiter.await() }.exceptionOrNull())
        a.close(); b.close()
    }

    @Test
    fun `read and write waiters on the same channel resume independently`() = runBlocking {
        val (a, b) = socketPair()
        // Fill a's kernel send buffer so further writes return 0 (non-blocking, no space left).
        val filler = ByteArray(64 * 1024)
        while (true) {
            val buf = ByteBuffer.wrap(filler)
            var wrote = false
            while (buf.hasRemaining()) {
                if (a.write(buf) == 0) { wrote = true; break }
            }
            if (wrote) break
        }
        val writeWaiter = async(Dispatchers.Default) { loop.await(a, SelectionKey.OP_WRITE) }
        val readWaiter = async(Dispatchers.Default) { loop.await(a, SelectionKey.OP_READ) }
        delay(50)
        b.write(ByteBuffer.wrap("hi".toByteArray()))
        withTimeout(2000) { readWaiter.await() }
        assertTrue(writeWaiter.isActive)
        // Drain b (non-blocking) in the background until the write waiter resumes; a single
        // bounded blocking read isn't reliable across platforms and socket buffer sizes.
        val drainer = launch(Dispatchers.IO) {
            val drainBuf = ByteBuffer.allocate(64 * 1024)
            while (true) {
                drainBuf.clear()
                val n = b.read(drainBuf)
                if (n == 0) {
                    if (writeWaiter.isCompleted) break
                    delay(1)
                }
            }
        }
        withTimeout(2000) { writeWaiter.await() }
        drainer.cancelAndJoin()
        a.close(); b.close()
    }

    @Test
    fun `await on a closed loop throws`() = runBlocking {
        val (a, b) = socketPair()
        loop.close()
        assertFailsWith<ClosedChannelException> { loop.await(b, SelectionKey.OP_READ) }
        a.close(); b.close()
    }

    @Test
    fun `invalid op registration does not break close`() = runBlocking {
        val (a, b) = socketPair()
        withTimeout(2000) { runCatching { loop.await(b, SelectionKey.OP_ACCEPT) } }
        val readWaiter = async(Dispatchers.Default) { runCatching { loop.await(b, SelectionKey.OP_READ) } }
        val others = List(5) { socketPair() }
        val otherWaiters = others.map { (_, pb) -> async(Dispatchers.Default) { runCatching { loop.await(pb, SelectionKey.OP_READ) } } }
        delay(100)
        loop.close()
        assertIs<ClosedChannelException>(withTimeout(2000) { readWaiter.await() }.exceptionOrNull())
        otherWaiters.forEach { w -> assertIs<ClosedChannelException>(withTimeout(2000) { w.await() }.exceptionOrNull()) }
        a.close(); b.close()
        others.forEach { (pa, pb) -> pa.close(); pb.close() }
    }

    @Test
    fun `duplicate waiter is rejected and the original still works`() = runBlocking {
        val (a, b) = socketPair()
        val first = async(Dispatchers.Default) { loop.await(b, SelectionKey.OP_READ) }
        delay(50)
        val second = async(Dispatchers.Default) { runCatching { loop.await(b, SelectionKey.OP_READ) } }
        assertIs<IllegalStateException>(withTimeout(2000) { second.await() }.exceptionOrNull())
        a.write(ByteBuffer.wrap("x".toByteArray()))
        withTimeout(2000) { first.await() }
        a.close(); b.close()
    }

    @Test
    fun `channel can be awaited again after its idle key is swept`() = runBlocking {
        val (a, b) = socketPair()
        withTimeout(2000) {
            val waiter = async(Dispatchers.Default) { loop.await(b, SelectionKey.OP_READ) }
            delay(50)
            a.write(ByteBuffer.wrap("x".toByteArray()))
            waiter.await()
        }
        b.read(ByteBuffer.allocate(8)) // drain so the key is left with no waiters and interest 0
        repeat(4) {
            loop.nudge()
            delay(20)
        }
        delay(100) // let the sweep run after its required quiet-pass run
        assertTrue(loop.sweptKeys.get() >= 1)
        a.write(ByteBuffer.wrap("y".toByteArray()))
        withTimeout(2000) { loop.await(b, SelectionKey.OP_READ) }
        a.close(); b.close()
    }
}
