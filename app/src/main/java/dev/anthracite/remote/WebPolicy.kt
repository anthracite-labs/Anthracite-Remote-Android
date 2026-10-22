package dev.anthracite.remote

import java.net.URI

internal const val LOOPBACK = "127.0.0.1"

internal enum class WebNavigation { LOAD, EXTERNAL, BLOCK }

/** Main-frame navigation policy for the forwarded OpenCode origin. */
internal fun classifyNavigation(value: String, localPort: Int): WebNavigation {
    if (localPort !in 1..65535) return WebNavigation.BLOCK
    val uri = runCatching { URI(value) }.getOrNull() ?: return WebNavigation.BLOCK
    if (uri.rawUserInfo != null) return WebNavigation.BLOCK
    val host = uri.host ?: return WebNavigation.BLOCK
    val loopback = host == LOOPBACK || host == "localhost"
    if (uri.scheme == "http" && loopback) {
        return if (uri.port == localPort) WebNavigation.LOAD else WebNavigation.BLOCK
    }
    if (uri.scheme == "https" || (uri.scheme == "http" && !loopback)) return WebNavigation.EXTERNAL
    return WebNavigation.BLOCK
}

internal fun loopbackHttp(value: String, localPort: Int): Boolean =
    classifyNavigation(value, localPort) == WebNavigation.LOAD
