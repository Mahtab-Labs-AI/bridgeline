package com.wifiproxy.core.content

import com.wifiproxy.core.WifiCredentials
import com.wifiproxy.core.clients.ClientRegistry
import com.wifiproxy.core.http.HttpResponse
import com.wifiproxy.core.http.ProxyRequest
import java.net.InetAddress

/**
 * Serves /<token>.pac and /<token>.mobileconfig. A successful fetch binds the slot to the peer IP.
 *
 * With [openAdmission] it also serves the fixed /proxy.pac and /proxy.mobileconfig, which admit
 * the peer through [ClientRegistry.admitOrCreate] (403 while every seat is taken). Without it,
 * those paths are 404 like any other unknown path. Under open admission an unknown token falls
 * back to [ClientRegistry.admitOrCreate] too, so an old per-device URL keeps working without
 * reserving a seat. [owner] - the listener's own (group-owner) address - is never given an auto
 * slot: the host phone's own apps must not take a seat.
 */
class LocalContent(
    private val registry: ClientRegistry,
    private val proxyHost: String,
    private val proxyPort: Int,
    private val wifi: WifiCredentials?,
    private val openAdmission: Boolean = false,
    private val owner: InetAddress? = null,
) {
    fun serve(request: ProxyRequest.Local, peer: InetAddress): HttpResponse {
        if (openAdmission) {
            FIXED_PATH.matchEntire(request.path)?.let { return serveFixed(request, it.groupValues[1], peer) }
        }
        val match = PATH.matchEntire(request.path) ?: return HttpResponse.NOT_FOUND
        if (request.method != "GET") return HttpResponse.METHOD_NOT_ALLOWED
        val (token, extension) = match.destructured
        if (extension == "mobileconfig" && wifi == null) return HttpResponse.NOT_FOUND
        if (registry.onPacFetch(token, peer) == null) {
            if (!openAdmission) return HttpResponse.NOT_FOUND
            admitOpen(peer) ?: return HttpResponse.FORBIDDEN
        }
        return render(extension, pacUrl = "http://$proxyHost:$proxyPort/$token.pac", profileSeed = token)
    }

    private fun serveFixed(request: ProxyRequest.Local, extension: String, peer: InetAddress): HttpResponse {
        if (request.method != "GET") return HttpResponse.METHOD_NOT_ALLOWED
        if (extension == "mobileconfig" && wifi == null) return HttpResponse.NOT_FOUND
        admitOpen(peer) ?: return HttpResponse.FORBIDDEN
        // The profile's UUIDs come from the group's SSID, not a per-slot token, so installing it
        // again on the same iPhone replaces the earlier copy instead of adding a second one.
        return render(extension, pacUrl = "http://$proxyHost:$proxyPort/$FIXED_NAME.pac", profileSeed = "open:${wifi?.ssid}")
    }

    /** Open admission's lookup: never creates a slot for [owner]. */
    private fun admitOpen(peer: InetAddress) =
        if (peer == owner) registry.admittedSlot(peer) else registry.admitOrCreate(peer)

    private fun render(extension: String, pacUrl: String, profileSeed: String): HttpResponse {
        val pac = PacFile.render(proxyHost, proxyPort)
        return when (extension) {
            "pac" -> HttpResponse(200, "OK", PacFile.CONTENT_TYPE, pac.toByteArray(Charsets.UTF_8))
            else -> {
                val profile = MobileConfig.render(wifi!!.ssid, wifi.password, pacUrl, profileSeed)
                HttpResponse(200, "OK", MobileConfig.CONTENT_TYPE, profile.toByteArray(Charsets.UTF_8))
            }
        }
    }

    private companion object {
        const val FIXED_NAME = "proxy"
        val PATH = Regex("^/(${ClientRegistry.TOKEN_PATTERN.pattern})\\.(pac|mobileconfig)$")
        val FIXED_PATH = Regex("^/$FIXED_NAME\\.(pac|mobileconfig)$")
    }
}
