package com.wifiproxy.core.content

/** The link has no DNS, so the PAC must never call dnsResolve/isInNet. */
object PacFile {
    const val CONTENT_TYPE = "application/x-ns-proxy-autoconfig"

    fun render(proxyHost: String, proxyPort: Int): String =
        "function FindProxyForURL(url, host) {\n  return \"PROXY $proxyHost:$proxyPort\";\n}\n"
}
