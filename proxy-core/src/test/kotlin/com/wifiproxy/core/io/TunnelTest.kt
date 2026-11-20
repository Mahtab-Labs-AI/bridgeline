package com.wifiproxy.core.io

import com.wifiproxy.core.readExactlyBlocking
import com.wifiproxy.core.socketPair
import com.wifiproxy.core.writeBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TunnelTest {
    private val loop = NioLoop()

    @AfterTest
    fun tearDown() = loop.close()

    @Test
    fun `copies both directions and ends when both sides close`() = runBlocking {
        val (app, clientSide) = socketPair()
        val (upSide, server) = socketPair()
        val up = AtomicInteger()
        val down = AtomicInteger()
        val run = launch(Dispatchers.Default) {
            Tunnel(loop, clientSide, upSide, idleTimeoutMs = 60_000, onUp = { up.addAndGet(it) }, onDown = { down.addAndGet(it) })
                .run(closeWhenResponseEnds = false)
        }
        app.writeBlocking("ping")
        assertEquals("ping", server.readExactlyBlocking(4))
        server.writeBlocking("pong!")
        assertEquals("pong!", app.readExactlyBlocking(5))
        app.shutdownOutput()
        server.shutdownOutput()
        withTimeout(2000) { run.join() }
        assertEquals(4, up.get())
        assertEquals(5, down.get())
        listOf(app, clientSide, upSide, server).forEach { it.close() }
    }

    @Test
    fun `idle tunnel ends after the timeout`() = runBlocking {
        val (app, clientSide) = socketPair()
        val (upSide, server) = socketPair()
        val run = launch(Dispatchers.Default) {
            Tunnel(loop, clientSide, upSide, idleTimeoutMs = 200).run(closeWhenResponseEnds = false)
        }
        withTimeout(3000) { run.join() }
        listOf(app, clientSide, upSide, server).forEach { it.close() }
    }

    @Test
    fun `forward tunnel ends when the response ends`() = runBlocking {
        val (app, clientSide) = socketPair()
        val (upSide, server) = socketPair()
        val run = launch(Dispatchers.Default) {
            Tunnel(loop, clientSide, upSide, idleTimeoutMs = 60_000).run(closeWhenResponseEnds = true)
        }
        server.writeBlocking("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n")
        server.shutdownOutput()
        withTimeout(2000) { run.join() }
        listOf(app, clientSide, upSide, server).forEach { it.close() }
    }
}
