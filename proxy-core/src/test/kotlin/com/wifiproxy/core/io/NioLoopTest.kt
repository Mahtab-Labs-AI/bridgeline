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
}
