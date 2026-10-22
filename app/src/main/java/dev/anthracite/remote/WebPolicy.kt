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

/**
 * Whether an HTTP Basic challenge may receive the WebUI password.
 *
 * [android.webkit.HttpAuthHandler] reports a host, not a port or URL. The page
 * URL must already be the forwarded origin, and every other request is blocked
 * before it can challenge. A blank or about:blank document uses the URL this
 * app loaded, not a challenge from another origin.
 */
internal fun authReleaseAllowed(
    pageUrl: String?,
    intendedUrl: String?,
    challengeHost: String?,
    localPort: Int,
): Boolean {
    if (challengeHost != LOOPBACK && challengeHost != "localhost") return false
    val page = when {
        pageUrl.isNullOrBlank() || pageUrl == "about:blank" -> intendedUrl
        else -> pageUrl
    } ?: return false
    return loopbackHttp(page, localPort)
}

internal fun tunnelIdentity(host: String, sshPort: Int, username: String, remotePort: Int): String =
    listOf(host.trim(), sshPort.toString(), username.trim(), remotePort.toString()).joinToString("|")

