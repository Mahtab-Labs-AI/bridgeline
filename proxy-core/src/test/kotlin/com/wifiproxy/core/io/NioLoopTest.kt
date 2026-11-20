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
        // Drain b's receive buffer to free space in a's send buffer so the write waiter can resume.
        b.configureBlocking(true)
        b.read(ByteBuffer.allocate(256 * 1024))
        withTimeout(2000) { writeWaiter.await() }
        a.close(); b.close()
    }

    @Test
    fun `await after close throws`() = runBlocking {
        val (a, b) = socketPair()
        loop.close()
        assertFailsWith<ClosedChannelException> { loop.await(b, SelectionKey.OP_READ) }
        a.close(); b.close()
    }

    @Test
    fun `invalid op registration does not break close`() = runBlocking {
        val (a, b) = socketPair()
        val badWaiter = async(Dispatchers.Default) { runCatching { loop.await(b, SelectionKey.OP_ACCEPT) } }
        val others = List(5) { socketPair() }
        val waiters = others.map { (_, pb) -> async(Dispatchers.Default) { runCatching { loop.await(pb, SelectionKey.OP_READ) } } }
        assertTrue(withTimeout(2000) { badWaiter.await() }.isFailure)
        loop.close()
        waiters.forEach { w -> assertIs<ClosedChannelException>(withTimeout(2000) { w.await() }.exceptionOrNull()) }
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
        loop.nudge()
        delay(100) // let the loop's next (zero-result) select pass sweep the now-idle key
        a.write(ByteBuffer.wrap("y".toByteArray()))
        withTimeout(2000) { loop.await(b, SelectionKey.OP_READ) }
        a.close(); b.close()
    }
}
