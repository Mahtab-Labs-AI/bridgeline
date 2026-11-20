package com.wifiproxy.core.io

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

fun monotonicMs(): Long = System.nanoTime() / 1_000_000

/** Why a [Tunnel] stopped pumping. */
enum class TunnelEnd { BOTH_EOF, RESPONSE_ENDED, IDLE, CLIENT_FAILED, UPSTREAM_FAILED }

data class TunnelResult(val end: TunnelEnd, val error: IOException? = null, val responseComplete: Boolean = false)

/**
 * Pumps client<->upstream until both directions reach EOF, the tunnel is idle for
 * [idleTimeoutMs], or (when closeWhenResponseEnds) the upstream finishes its response.
 *
 * Failure policy is per direction: a read failure means that side's peer is dead, so it takes
 * the other direction down with it (CLIENT_FAILED/UPSTREAM_FAILED). A write failure only means
 * the destination stopped accepting data - the source side can still have readable data queued
 * (observed on Linux/Android even after the peer RSTs the connection) - so the opposite
 * direction is left alone to drain on its own; the failure is recorded but nothing is cancelled.
 * The first recorded failure wins the result, then IDLE if the watchdog fired, then
 * RESPONSE_ENDED, otherwise BOTH_EOF.
 *
 * [TunnelResult.responseComplete] is true once the upstream->client pump reaches EOF: every
 * byte upstream sent was written to the client, followed by a shutdownOutput. It can be true
 * together with CLIENT_FAILED or UPSTREAM_FAILED - e.g. the client's write failed only after
 * it had already received the whole response - which matters to a caller deciding whether an
 * abort would discard a complete response still sitting in the client socket's send buffer.
 *
 * [run] throws only on cancellation (e.g. an outer revoke) or a non-I/O bug.
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
    init {
        require(idleTimeoutMs > 0) { "idleTimeoutMs must be positive" }
    }

    suspend fun run(closeWhenResponseEnds: Boolean): TunnelResult = coroutineScope {
        val lastActivity = AtomicLong(clock())
        val failure = AtomicReference<TunnelResult?>()
        val idled = AtomicBoolean(false)
        val responseEnded = AtomicBoolean(false)
        val responseComplete = AtomicBoolean(false)

        // Declared lazily and started together below so each side can cancel the other by
        // referencing a Job that is guaranteed to be assigned before either one actually runs.
        lateinit var up: Job
        lateinit var down: Job

        up = launch(start = CoroutineStart.LAZY) {
            when (val result = pump(loop, client, upstream, ByteBuffer.allocate(BUFFER_SIZE)) { n ->
                lastActivity.set(clock())
                onUp(n)
            }) {
                is PumpResult.ReadFailed -> {
                    failure.compareAndSet(null, TunnelResult(TunnelEnd.CLIENT_FAILED, result.error))
                    down.cancel()
                }
                is PumpResult.WriteFailed ->
                    failure.compareAndSet(null, TunnelResult(TunnelEnd.UPSTREAM_FAILED, result.error))
                PumpResult.Eof -> {}
            }
        }
        down = launch(start = CoroutineStart.LAZY) {
            when (val result = pump(loop, upstream, client, ByteBuffer.allocate(BUFFER_SIZE)) { n ->
                lastActivity.set(clock())
                onDown(n)
            }) {
                PumpResult.Eof -> {
                    responseComplete.set(true)
                    if (closeWhenResponseEnds) {
                        responseEnded.set(true)
                        up.cancel()
                    }
                }
                is PumpResult.ReadFailed -> {
                    failure.compareAndSet(null, TunnelResult(TunnelEnd.UPSTREAM_FAILED, result.error))
                    up.cancel()
                }
                is PumpResult.WriteFailed ->
                    failure.compareAndSet(null, TunnelResult(TunnelEnd.CLIENT_FAILED, result.error))
            }
        }
        val idle = launch {
            while (true) {
                val remaining = lastActivity.get() + idleTimeoutMs - clock()
                if (remaining <= 0) {
                    idled.set(true)
                    up.cancel()
                    down.cancel()
                    break
                }
                delay(remaining)
            }
        }

        up.start()
        down.start()

        up.join()
        down.join()
        idle.cancel()

        val outcome = failure.get() ?: when {
            idled.get() -> TunnelResult(TunnelEnd.IDLE)
            responseEnded.get() -> TunnelResult(TunnelEnd.RESPONSE_ENDED)
            else -> TunnelResult(TunnelEnd.BOTH_EOF)
        }
        outcome.copy(responseComplete = responseComplete.get())
    }

    companion object {
        const val BUFFER_SIZE = 16 * 1024
    }
}
