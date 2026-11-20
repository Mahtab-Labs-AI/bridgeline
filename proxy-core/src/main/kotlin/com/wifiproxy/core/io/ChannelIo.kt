package com.wifiproxy.core.io

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

/** Copies [src] to [dst] until EOF, then half-closes [dst]. [onBytes] runs after each chunk is written. */
suspend fun pump(loop: NioLoop, src: SocketChannel, dst: SocketChannel, buf: ByteBuffer, onBytes: (Int) -> Unit) {
    while (true) {
        buf.clear()
        val n = src.read(buf)
        if (n < 0) {
            runCatching { dst.shutdownOutput() }
            return
        }
        if (n == 0) {
            loop.await(src, SelectionKey.OP_READ)
            continue
        }
        buf.flip()
        while (buf.hasRemaining()) {
            if (dst.write(buf) == 0) loop.await(dst, SelectionKey.OP_WRITE)
        }
        onBytes(n)
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
