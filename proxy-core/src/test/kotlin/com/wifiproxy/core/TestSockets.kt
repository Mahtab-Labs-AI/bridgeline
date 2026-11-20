package com.wifiproxy.core

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel

/** Two connected loopback channels, both non-blocking. */
fun socketPair(): Pair<SocketChannel, SocketChannel> {
    ServerSocketChannel.open().use { server ->
        server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        val a = SocketChannel.open(server.localAddress)
        val b = server.accept()
        a.configureBlocking(false)
        b.configureBlocking(false)
        return a to b
    }
}

// The helpers below switch the channel to blocking mode; only use them on channels never awaited via NioLoop.

fun SocketChannel.writeBlocking(text: String) = writeBlocking(text.toByteArray(Charsets.ISO_8859_1))

fun SocketChannel.writeBlocking(bytes: ByteArray) {
    configureBlocking(true)
    val buf = ByteBuffer.wrap(bytes)
    while (buf.hasRemaining()) write(buf)
}

fun SocketChannel.readExactlyBlocking(n: Int): String {
    configureBlocking(true)
    val buf = ByteBuffer.allocate(n)
    while (buf.hasRemaining()) {
        if (read(buf) < 0) break
    }
    return String(buf.array(), 0, buf.position(), Charsets.ISO_8859_1)
}

fun SocketChannel.readAllBlocking(): ByteArray {
    configureBlocking(true)
    val out = ByteArrayOutputStream()
    val buf = ByteBuffer.allocate(64 * 1024)
    while (true) {
        buf.clear()
        val n = read(buf)
        if (n < 0) break
        out.write(buf.array(), 0, n)
    }
    return out.toByteArray()
}
