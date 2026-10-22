package dev.anthracite.remote

import java.net.URI

/** The only authority used for the device-side SSH forward and embedded WebUI. */
internal const val LOOPBACK = "127.0.0.1"

internal enum class WebNavigation { LOAD, EXTERNAL, BLOCK }

/** Response observed through an SSH direct-tcpip channel before any local listener is created. */
internal data class RemoteHttpProbe(val statusCode: Int, val authenticateHeaders: List<String>)

private fun trustedForwardedUri(value: String, localPort: Int, allowedSchemes: Set<String>): URI? {
    if (localPort !in 1..65535) return null
    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    if (
        uri.scheme !in allowedSchemes ||
        uri.rawUserInfo != null ||
        uri.host != LOOPBACK ||
        uri.port != localPort
    ) return null
    return uri
}

/** True only for the one explicit HTTP origin that belongs to the active SSH forward. */
internal fun loopbackHttp(value: String, localPort: Int): Boolean =
    trustedForwardedUri(value, localPort, setOf("http")) != null

/** WebSockets are allowed only over the active cleartext tunnel origin, never another loopback alias. */
internal fun loopbackWebSocket(value: String, localPort: Int): Boolean =
    trustedForwardedUri(value, localPort, setOf("ws")) != null

/**
 * Main-frame and subresource policy for the forwarded OpenCode origin.
 *
 * localhost and [::1] are intentionally not aliases here. A WebView is always
 * loaded with the numeric IPv4 authority, so accepting aliases would expand the
 * cookie and HTTP-auth protection space without a transport need.
 */
internal fun classifyNavigation(value: String, localPort: Int): WebNavigation {
    if (loopbackHttp(value, localPort) || loopbackWebSocket(value, localPort)) return WebNavigation.LOAD
    val uri = runCatching { URI(value) }.getOrNull() ?: return WebNavigation.BLOCK
    if (uri.rawUserInfo != null) return WebNavigation.BLOCK
    return if (uri.scheme == "https") WebNavigation.EXTERNAL else WebNavigation.BLOCK
}

/**
 * Whether an HTTP Basic challenge may receive the WebUI password.
 *
 * [android.webkit.HttpAuthHandler] reports a host, not a port or full URL. The
 * page URL therefore supplies the port and scheme; both must identify the one
 * pinned forwarded origin.
 */
internal fun authReleaseAllowed(
    pageUrl: String?,
    intendedUrl: String?,
    challengeHost: String?,
    localPort: Int,
): Boolean {
    if (challengeHost != LOOPBACK) return false
    val page = when {
        pageUrl.isNullOrBlank() || pageUrl == "about:blank" -> intendedUrl
        else -> pageUrl
    } ?: return false
    return loopbackHttp(page, localPort)
}

/** A tunnel is usable only if an unauthenticated probe receives a Basic challenge. */
internal fun authenticatedTunnelReady(statusCode: Int, authenticateHeaders: List<String>): Boolean =
    statusCode == 401 && authenticateHeaders.any { header ->
        BASIC_CHALLENGE.containsMatchIn(header)
    }

/**
 * Download redirects must stay at the exact forwarded authority. This is checked
 * before a new request is opened, so WebUI cookies and Basic authorization can
 * never be sent to an alternate origin.
 */
internal fun downloadRedirectAllowed(from: String, to: String, localPort: Int): Boolean =
    loopbackHttp(from, localPort) && loopbackHttp(to, localPort)

/**
 * Authentication state is scoped to the server endpoint and its approved raw SSH
 * host key. The fingerprint includes the host-key algorithm bytes, so a key-type
 * substitution cannot retain a previous WebUI session.
 */
internal fun tunnelIdentity(
    host: String,
    sshPort: Int,
    username: String,
    remotePort: Int,
    approvedHostKey: ByteArray?,
): String = listOf(
    host.trim(),
    sshPort.toString(),
    username.trim(),
    remotePort.toString(),
    approvedHostKey?.let(::sha256Fingerprint) ?: "unapproved",
).joinToString("|")

private val BASIC_CHALLENGE = Regex("(?:^|,)\\s*Basic(?:\\s|$)", RegexOption.IGNORE_CASE)
