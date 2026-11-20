package com.wifiproxy.core.io

import com.wifiproxy.core.readAllBlocking
import com.wifiproxy.core.readExactlyBlocking
import com.wifiproxy.core.socketPair
import com.wifiproxy.core.writeBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TunnelTest {
    private val loop = NioLoop()

    @AfterTest
    fun tearDown() = loop.close()

    private fun closeAll(vararg channels: SocketChannel) = channels.forEach { runCatching { it.close() } }

    @Test
    fun `copies both directions and ends when both sides close`() = runBlocking {
        val (app, clientSide) = socketPair()
        val (upSide, server) = socketPair()
        try {
            val up = AtomicInteger()
            val down = AtomicInteger()
            val run = async(Dispatchers.Default) {
                Tunnel(loop, clientSide, upSide, idleTimeoutMs = 60_000, onUp = { up.addAndGet(it) }, onDown = { down.addAndGet(it) })
                    .run(closeWhenResponseEnds = false)
            }
            app.writeBlocking("ping")
            assertEquals("ping", server.readExactlyBlocking(4))
            server.writeBlocking("pong!")
            assertEquals("pong!", app.readExactlyBlocking(5))
            app.shutdownOutput()
            server.shutdownOutput()
            val result = withTimeout(2000) { run.await() }
            assertEquals(4, up.get())
            assertEquals(5, down.get())
            assertEquals(TunnelResult(TunnelEnd.BOTH_EOF), result)
        } finally {
            closeAll(app, clientSide, upSide, server)
        }
    }

    @Test
    fun `idle tunnel ends after the timeout`() = runBlocking {
        val (app, clientSide) = socketPair()
        val (upSide, server) = socketPair()
        try {
            val run = async(Dispatchers.Default) {
                Tunnel(loop, clientSide, upSide, idleTimeoutMs = 600).run(closeWhenResponseEnds = false)
            }
            delay(250)
            assertTrue(run.isActive)
            val result = withTimeout(3000) { run.await() }
            assertEquals(TunnelEnd.IDLE, result.end)
        } finally {
            closeAll(app, clientSide, upSide, server)
        }
    }

    @Test
    fun `forward tunnel ends when the response ends`() = runBlocking {
        val (app, clientSide) = socketPair()
        val (upSide, server) = socketPair()
        try {
            val run = async(Dispatchers.Default) {
                Tunnel(loop, clientSide, upSide, idleTimeoutMs = 60_000).run(closeWhenResponseEnds = true)
            }
            val response = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"
            server.writeBlocking(response)
            server.shutdownOutput()
            val result = withTimeout(2000) { run.await() }
            assertEquals(response, app.readAllBlocking().toString(Charsets.ISO_8859_1))
            assertEquals(TunnelEnd.RESPONSE_ENDED, result.end)
        } finally {
            closeAll(app, clientSide, upSide, server)
        }
    }

    @Test
    fun `write failure toward upstream does not cancel the download`() = runBlocking {
        val (app, clientSide) = socketPair()
        val (upSide, server) = socketPair()
        try {
            upSide.shutdownOutput()
            val run = async(Dispatchers.Default) {
                Tunnel(loop, clientSide, upSide, idleTimeoutMs = 60_000).run(closeWhenResponseEnds = false)
            }
            app.writeBlocking("x")
            val payload = ByteArray(1024 * 1024) { (it % 251).toByte() }
            // Drain app concurrently: server's write below backs up until the tunnel's buffer
            // and the app<-clientSide socket are read, so the reader must already be running.
            val received = async(Dispatchers.IO) { app.readAllBlocking() }
            server.writeBlocking(payload)
            server.shutdownOutput()
            val result = withTimeout(5000) { run.await() }
            assertContentEquals(payload, withTimeout(5000) { received.await() })
            assertEquals(TunnelEnd.UPSTREAM_FAILED, result.end)
        } finally {
            closeAll(app, clientSide, upSide, server)
        }
    }

    @Test
    fun `client reset ends the tunnel promptly`() = runBlocking {
        val (app, clientSide) = socketPair()
        val (upSide, server) = socketPair()
        try {
            val run = async(Dispatchers.Default) {
                Tunnel(loop, clientSide, upSide, idleTimeoutMs = 60_000).run(closeWhenResponseEnds = false)
            }
            app.socket().setSoLinger(true, 0)
            app.close()
            val result = withTimeout(2000) { run.await() }
            assertEquals(TunnelEnd.CLIENT_FAILED, result.end)
        } finally {
            closeAll(clientSide, upSide, server)
        }
    }

    @Test
    fun `outer cancellation ends run`() = runBlocking {
        val (app, clientSide) = socketPair()
        val (upSide, server) = socketPair()
        try {
            val run = launch(Dispatchers.Default) {
                Tunnel(loop, clientSide, upSide, idleTimeoutMs = 60_000).run(closeWhenResponseEnds = false)
            }
            run.cancel()
            withTimeout(2000) { run.join() }
        } finally {
            closeAll(app, clientSide, upSide, server)
        }
    }

    @Test
    fun `half-closed client still receives the other direction`() = runBlocking {
        val (app, clientSide) = socketPair()
        val (upSide, server) = socketPair()
        try {
            val run = async(Dispatchers.Default) {
                Tunnel(loop, clientSide, upSide, idleTimeoutMs = 60_000).run(closeWhenResponseEnds = false)
            }
            app.shutdownOutput()
            server.writeBlocking("late")
            server.shutdownOutput()
            val result = withTimeout(2000) { run.await() }
            assertEquals("late", app.readAllBlocking().toString(Charsets.ISO_8859_1))
            assertEquals(TunnelResult(TunnelEnd.BOTH_EOF), result)
        } finally {
            closeAll(app, clientSide, upSide, server)
        }
    }
}
