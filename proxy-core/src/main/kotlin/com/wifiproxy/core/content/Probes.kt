package com.wifiproxy.core.content

import com.wifiproxy.core.http.HttpResponse
import com.wifiproxy.core.http.ProxyRequest

/** Connectivity-check URLs the proxy answers itself while the cellular egress is validated. */
object Probes {
    private val NO_CONTENT = setOf(
        "connectivitycheck.gstatic.com" to "/generate_204",
        "connectivitycheck.android.com" to "/generate_204",
        "clients3.google.com" to "/generate_204",
        "play.googleapis.com" to "/generate_204",
        "www.google.com" to "/gen_204",
    )

    private val APPLE_SUCCESS =
        "<HTML><HEAD><TITLE>Success</TITLE></HEAD><BODY>Success</BODY></HTML>".toByteArray(Charsets.UTF_8)

    fun match(request: ProxyRequest.Forward): HttpResponse? {
        if (request.method != "GET" && request.method != "HEAD") return null
        val host = request.host.lowercase()
        val path = request.target.substringBefore('?')
        return when {
            (host to path) in NO_CONTENT -> HttpResponse(204, "No Content")
            host == "captive.apple.com" ||
                (host == "www.apple.com" && path == "/library/test/success.html") ->
                HttpResponse(200, "OK", "text/html", APPLE_SUCCESS)
            else -> null
        }
    }
}
