package dev.anthracite.remote

import java.security.MessageDigest
import java.util.Base64

internal enum class HostKeyDecision { MATCH, UNKNOWN, CHANGED }

internal fun hostKeyDecision(saved: ByteArray?, presented: ByteArray): HostKeyDecision = when {
    saved == null -> HostKeyDecision.UNKNOWN
    saved.contentEquals(presented) -> HostKeyDecision.MATCH
    else -> HostKeyDecision.CHANGED
}

/** OpenSSH SHA256 fingerprint of the raw host-key blob. */
internal fun sha256Fingerprint(key: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(key)
    val encoded = Base64.getEncoder().withoutPadding().encodeToString(digest)
    return "SHA256:$encoded"
}

internal fun sshKeyType(blob: ByteArray): String {
    if (blob.size < 5) return "unknown"
    val length = ((blob[0].toInt() and 0xff) shl 24) or
        ((blob[1].toInt() and 0xff) shl 16) or
        ((blob[2].toInt() and 0xff) shl 8) or
        (blob[3].toInt() and 0xff)
    if (length <= 0 || length > blob.size - 4 || length > 64) return "unknown"
    return blob.copyOfRange(4, 4 + length).toString(Charsets.US_ASCII)
}
