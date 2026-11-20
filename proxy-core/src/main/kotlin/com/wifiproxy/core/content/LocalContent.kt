package com.wifiproxy.core.content

import com.wifiproxy.core.WifiCredentials
import com.wifiproxy.core.clients.ClientRegistry
import com.wifiproxy.core.http.HttpResponse
import com.wifiproxy.core.http.ProxyRequest
import java.net.InetAddress

/** Serves /<token>.pac and /<token>.mobileconfig. A successful fetch binds the slot to the peer IP. */
class LocalContent(
    private val registry: ClientRegistry,
    private val proxyHost: String,
    private val proxyPort: Int,
    private val wifi: WifiCredentials?,
) {
    fun serve(request: ProxyRequest.Local, peer: InetAddress): HttpResponse {
        val match = PATH.matchEntire(request.path) ?: return HttpResponse.NOT_FOUND
        if (request.method != "GET") return HttpResponse.METHOD_NOT_ALLOWED
        val (token, extension) = match.destructured
        if (extension == "mobileconfig" && wifi == null) return HttpResponse.NOT_FOUND
        registry.onPacFetch(token, peer) ?: return HttpResponse.NOT_FOUND

        val pac = PacFile.render(proxyHost, proxyPort)
        return when (extension) {
            "pac" -> HttpResponse(200, "OK", PacFile.CONTENT_TYPE, pac.toByteArray(Charsets.UTF_8))
            else -> {
                val pacUrl = "http://$proxyHost:$proxyPort/$token.pac"
                val profile = MobileConfig.render(wifi!!.ssid, wifi.password, pacUrl, token)
                HttpResponse(200, "OK", MobileConfig.CONTENT_TYPE, profile.toByteArray(Charsets.UTF_8))
            }
        }
    }

    private companion object {
        val PATH = Regex("^/(${ClientRegistry.TOKEN_PATTERN.pattern})\\.(pac|mobileconfig)$")
    }
}
