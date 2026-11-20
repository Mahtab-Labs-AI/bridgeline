package com.wifiproxy.core.http

sealed interface ProxyRequest {
    data class Connect(val host: String, val port: Int) : ProxyRequest

    data class Forward(
        val method: String,
        val host: String,
        val port: Int,
        /** Origin-form target: path plus query, always starting with '/'. */
        val target: String,
        val version: String,
        val headers: List<Pair<String, String>>,
    ) : ProxyRequest {
        /** Head to send upstream: origin-form, hop-by-hop headers removed, one request per connection. */
        fun upstreamHead(): ByteArray {
            val listed = headers.filter { it.first.equals("Connection", ignoreCase = true) }
                .flatMap { it.second.split(',') }
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .toSet()
            val kept = headers.filter { (name, _) -> name.lowercase().let { it !in HOP_BY_HOP && it !in listed } }
            val sb = StringBuilder().append(method).append(' ').append(target).append(' ').append(version).append("\r\n")
            if (kept.none { it.first.equals("Host", ignoreCase = true) }) sb.append("Host: ").append(hostHeader()).append("\r\n")
            kept.forEach { (name, value) -> sb.append(name).append(": ").append(value).append("\r\n") }
            sb.append("Connection: close\r\n\r\n")
            return sb.toString().toByteArray(Charsets.ISO_8859_1)
        }

        private fun hostHeader(): String {
            val h = if (':' in host) "[$host]" else host
            return if (port == 80) h else "$h:$port"
        }
    }

    /** Origin-form request addressed to the proxy itself (PAC, mobileconfig). */
    data class Local(val method: String, val path: String) : ProxyRequest

    data class Malformed(val reason: String) : ProxyRequest
}

private val HOP_BY_HOP = setOf(
    "connection", "proxy-connection", "keep-alive", "proxy-authorization",
    "proxy-authenticate", "te", "trailer", "upgrade",
)

object RequestParser {
    /** [head] is the request head without the terminating blank line. */
    fun parse(head: String): ProxyRequest {
        val lines = head.split("\r\n")
        val parts = lines.first().split(' ')
        if (parts.size != 3) return ProxyRequest.Malformed("bad request line")
        val (method, target, version) = parts
        if (method.isEmpty() || !version.startsWith("HTTP/1.")) return ProxyRequest.Malformed("unsupported version")

        val headers = ArrayList<Pair<String, String>>()
        for (line in lines.drop(1)) {
            if (line.isEmpty()) continue
            val colon = line.indexOf(':')
            if (colon <= 0) return ProxyRequest.Malformed("bad header")
            headers += line.substring(0, colon).trim() to line.substring(colon + 1).trim()
        }

        return when {
            method == "CONNECT" -> parseAuthority(target, defaultPort = null)
                ?.let { (host, port) -> ProxyRequest.Connect(host, port) }
                ?: ProxyRequest.Malformed("bad CONNECT target")
            target.startsWith("http://", ignoreCase = true) -> parseAbsolute(method, target.substring(7), version, headers)
            target.startsWith("/") -> ProxyRequest.Local(method, target.substringBefore('?'))
            else -> ProxyRequest.Malformed("unsupported target")
        }
    }

    private fun parseAbsolute(
        method: String,
        rest: String,
        version: String,
        headers: List<Pair<String, String>>,
    ): ProxyRequest {
        val end = rest.indexOfFirst { it == '/' || it == '?' }.let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, end)
        if ('@' in authority) return ProxyRequest.Malformed("userinfo not allowed")
        val (host, port) = parseAuthority(authority, defaultPort = 80) ?: return ProxyRequest.Malformed("bad authority")
        val path = rest.substring(end).let {
            when {
                it.isEmpty() -> "/"
                it.startsWith("?") -> "/$it"
                else -> it
            }
        }
        return ProxyRequest.Forward(method, host, port, path, version, headers)
    }

    private fun parseAuthority(text: String, defaultPort: Int?): Pair<String, Int>? {
        val host: String
        val portText: String?
        if (text.startsWith("[")) {
            val close = text.indexOf(']')
            if (close < 0) return null
            host = text.substring(1, close)
            val after = text.substring(close + 1)
            portText = when {
                after.isEmpty() -> null
                after.startsWith(":") -> after.substring(1)
                else -> return null
            }
        } else {
            val colon = text.lastIndexOf(':')
            if (colon >= 0 && text.indexOf(':') != colon) return null
            host = if (colon >= 0) text.substring(0, colon) else text
            portText = if (colon >= 0) text.substring(colon + 1) else null
        }
        if (host.isEmpty()) return null
        val port = if (portText == null) {
            defaultPort ?: return null
        } else {
            if (portText.isEmpty() || !portText.all { it in '0'..'9' }) return null
            portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        }
        return host to port
    }
}
