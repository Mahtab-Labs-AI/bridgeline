package com.wifiproxy.core.io

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.SocketChannel

const val MAX_HEAD_BYTES = 16 * 1024

class HeadTooLargeException : IOException("request head exceeds limit")

/** Request head text without the terminating blank line, plus any body bytes read past it. */
class Head(val text: String, val leftover: ByteArray)

/** Reads until CRLFCRLF. Returns null if the peer closes first. */
suspend fun readHead(loop: NioLoop, ch: SocketChannel, maxBytes: Int = MAX_HEAD_BYTES): Head? {
    require(maxBytes >= 4) { "maxBytes must be at least 4 to hold a blank line" }
    val buf = ByteBuffer.allocate(maxBytes)
    var scanFrom = 0
    while (true) {
        val n = ch.read(buf)
        if (n < 0) return null
        if (n == 0) {
            loop.await(ch, SelectionKey.OP_READ)
            continue
        }
        val end = indexOfBlankLine(buf.array(), scanFrom, buf.position())
        if (end >= 0) {
            val text = String(buf.array(), 0, end, Charsets.ISO_8859_1)
            return Head(text, buf.array().copyOfRange(end + 4, buf.position()))
        }
        if (!buf.hasRemaining()) throw HeadTooLargeException()
        scanFrom = maxOf(0, buf.position() - 3)
    }
}

suspend fun writeFully(loop: NioLoop, ch: SocketChannel, bytes: ByteArray) {
    val buf = ByteBuffer.wrap(bytes)
    while (buf.hasRemaining()) {
        if (ch.write(buf) == 0) loop.await(ch, SelectionKey.OP_WRITE)
    }
}

/** Outcome of a [pump] run: a clean EOF, or which side's I/O failed. */
sealed interface PumpResult {
    data object Eof : PumpResult
    data class ReadFailed(val error: IOException) : PumpResult
    data class WriteFailed(val error: IOException) : PumpResult
}

/**
 * Copies [src] to [dst] until EOF, then half-closes [dst]. [onBytes] runs after every write
 * that delivers bytes, with the number that write delivered.
 *
 * Returns how the copy ended instead of throwing, so the two directions of a [Tunnel] can fail
 * independently: an [PumpResult.ReadFailed] means [src] is dead, an [PumpResult.WriteFailed]
 * means [dst] is. Cancellation and non-I/O exceptions still propagate.
 */
suspend fun pump(loop: NioLoop, src: SocketChannel, dst: SocketChannel, buf: ByteBuffer, onBytes: (Int) -> Unit): PumpResult {
    while (true) {
        currentCoroutineContext().ensureActive()
        buf.clear()
        val n = try {
            src.read(buf)
        } catch (e: IOException) {
            return PumpResult.ReadFailed(e)
        }
        if (n < 0) {
            runCatching { dst.shutdownOutput() }
            return PumpResult.Eof
        }
        if (n == 0) {
            try {
                loop.await(src, SelectionKey.OP_READ)
            } catch (e: IOException) {
                return PumpResult.ReadFailed(e)
            }
            continue
        }
        buf.flip()
        while (buf.hasRemaining()) {
            val written = try {
                dst.write(buf)
            } catch (e: IOException) {
                return PumpResult.WriteFailed(e)
            }
            if (written == 0) {
                try {
                    loop.await(dst, SelectionKey.OP_WRITE)
                } catch (e: IOException) {
                    return PumpResult.WriteFailed(e)
                }
            } else {
                onBytes(written)
            }
        }
    }
}

private const val CR: Byte = 13
private const val LF: Byte = 10

internal fun indexOfBlankLine(bytes: ByteArray, from: Int, until: Int): Int {
    var i = from
    while (i + 3 < until) {
        if (bytes[i] == CR && bytes[i + 1] == LF && bytes[i + 2] == CR && bytes[i + 3] == LF) return i
        i++
    }
    return -1
}
