package com.wifiproxy.core.io

import com.wifiproxy.core.readAllBlocking
import com.wifiproxy.core.socketPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ChannelIoTest {
    private val loop = NioLoop()

    @AfterTest
    fun tearDown() = loop.close()

    private fun bytes(s: String) = ByteBuffer.wrap(s.toByteArray(Charsets.ISO_8859_1))

    @Test
    fun `readHead splits head and leftover`() = runBlocking {
        val (a, b) = socketPair()
        a.write(bytes("GET / HTTP/1.1\r\nHost: x\r\n\r\nBODY"))
        val head = withTimeout(2000) { readHead(loop, b) }!!
        assertEquals("GET / HTTP/1.1\r\nHost: x", head.text)
        assertEquals("BODY", head.leftover.toString(Charsets.ISO_8859_1))
        a.close(); b.close()
    }

    @Test
    fun `readHead assembles a head split across packets`() = runBlocking {
        val (a, b) = socketPair()
        val head = async(Dispatchers.Default) { readHead(loop, b) }
        a.write(bytes("GET / HT"))
        delay(50)
        a.write(bytes("TP/1.1\r\n\r"))
        delay(50)
        a.write(bytes("\n"))
        assertEquals("GET / HTTP/1.1", withTimeout(2000) { head.await() }!!.text)
        a.close(); b.close()
    }

    @Test
    fun `readHead returns null on eof before a full head`() = runBlocking {
        val (a, b) = socketPair()
        a.write(bytes("GET /"))
        a.close()
        assertNull(withTimeout(2000) { readHead(loop, b) })
        b.close()
    }

    @Test
    fun `readHead rejects oversized heads`() = runBlocking {
        val (a, b) = socketPair()
        a.write(bytes("X".repeat(64)))
        assertFailsWith<HeadTooLargeException> { withTimeout(2000) { readHead(loop, b, maxBytes = 32) } }
        a.close(); b.close()
    }

    @Test
    fun `readHead rejects tiny limits`() = runBlocking {
        val (a, b) = socketPair()
        assertFailsWith<IllegalArgumentException> { readHead(loop, b, maxBytes = 3) }
        a.close(); b.close()
    }

    @Test
    fun `writeFully survives backpressure`() = runBlocking {
        val (a, b) = socketPair()
        val payload = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }
        val reader = async(Dispatchers.IO) {
            b.configureBlocking(true)
            val out = ByteArrayOutputStream()
            val buf = ByteBuffer.allocate(64 * 1024)
            while (out.size() < payload.size) {
                buf.clear()
                val n = b.read(buf)
                if (n < 0) break
                out.write(buf.array(), 0, n)
            }
            out.toByteArray()
        }
        withTimeout(10_000) { writeFully(loop, a, payload) }
        assertContentEquals(payload, withTimeout(10_000) { reader.await() })
        a.close(); b.close()
    }

    @Test
    fun `pump copies until eof then half-closes the destination`() = runBlocking {
        val (src, srcPeer) = socketPair()
        val (dstPeer, dst) = socketPair()
        var counted = 0
        val job = async(Dispatchers.Default) { pump(loop, srcPeer, dstPeer, ByteBuffer.allocate(1024)) { counted += it } }
        src.write(bytes("hello"))
        src.shutdownOutput()
        withTimeout(2000) { job.await() }
        assertEquals("hello", dst.readAllBlocking().toString(Charsets.ISO_8859_1))
        assertEquals(5, counted)
        listOf(src, srcPeer, dstPeer, dst).forEach { it.close() }
    }
}
