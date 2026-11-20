package com.wifiproxy.core.http

/** A complete, self-contained response; the proxy always closes the connection after sending it. */
class HttpResponse(
    val status: Int,
    val reason: String,
    val contentType: String? = null,
    val body: ByteArray = ByteArray(0),
) {
    fun toBytes(): ByteArray {
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            if (contentType != null) append("Content-Type: ").append(contentType).append("\r\n")
            if (status != 204) append("Content-Length: ").append(body.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        return head.toByteArray(Charsets.ISO_8859_1) + body
    }

    companion object {
        val BAD_REQUEST = HttpResponse(400, "Bad Request")
        val FORBIDDEN = HttpResponse(403, "Forbidden")
        val NOT_FOUND = HttpResponse(404, "Not Found")
        val METHOD_NOT_ALLOWED = HttpResponse(405, "Method Not Allowed")
        val BAD_GATEWAY = HttpResponse(502, "Bad Gateway")
        val SERVICE_UNAVAILABLE = HttpResponse(503, "Service Unavailable")
        val GATEWAY_TIMEOUT = HttpResponse(504, "Gateway Timeout")

        val CONNECTION_ESTABLISHED: ByteArray =
            "HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
    }
}
