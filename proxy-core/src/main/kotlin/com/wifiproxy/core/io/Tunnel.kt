package com.wifiproxy.core.io

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicLong

fun monotonicMs(): Long = System.nanoTime() / 1_000_000

/**
 * Pumps client<->upstream until both directions reach EOF, the tunnel is idle for
 * [idleTimeoutMs], or (when closeWhenResponseEnds) the upstream finishes its response.
 * The caller owns and closes both channels.
 */
class Tunnel(
    private val loop: NioLoop,
    private val client: SocketChannel,
    private val upstream: SocketChannel,
    private val idleTimeoutMs: Long,
    private val clock: () -> Long = ::monotonicMs,
    private val onUp: (Int) -> Unit = {},
    private val onDown: (Int) -> Unit = {},
) {
    suspend fun run(closeWhenResponseEnds: Boolean) = coroutineScope {
        val lastActivity = AtomicLong(clock())
        val up = launch {
            pump(loop, client, upstream, ByteBuffer.allocate(BUFFER_SIZE)) { n ->
                lastActivity.set(clock())
                onUp(n)
            }
        }
        val down = launch {
            pump(loop, upstream, client, ByteBuffer.allocate(BUFFER_SIZE)) { n ->
                lastActivity.set(clock())
                onDown(n)
            }
            if (closeWhenResponseEnds) up.cancel()
        }
        val idle = launch {
            val tick = (idleTimeoutMs / 4).coerceIn(10, 1000)
            while (isActive) {
                delay(tick)
                if (clock() - lastActivity.get() >= idleTimeoutMs) {
                    up.cancel()
                    down.cancel()
                }
            }
        }
        up.join()
        down.join()
        idle.cancel()
    }

    companion object {
        const val BUFFER_SIZE = 32 * 1024
    }
}
