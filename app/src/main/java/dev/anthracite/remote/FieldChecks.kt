package dev.anthracite.remote

internal fun parsePort(text: String): Int? {
    val value = text.trim()
    if (value.isEmpty() || value.any { !it.isDigit() }) return null
    return value.toIntOrNull()?.takeIf { it in 1..65535 }
}

internal fun sshHostAllowed(host: String): Boolean {
    val value = host.trim()
    if (value.isEmpty() || value.length > 253) return false
    return value.none { it.isWhitespace() || it == '/' || it == '\\' || it == '@' }
}

internal fun sshUsernameAllowed(username: String): Boolean {
    val value = username.trim()
    if (value.isEmpty() || value.length > 64) return false
    return value.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
}

internal fun webPasswordAccepted(password: String): Boolean {
    if (password.length < 12 || password.any { it.isISOControl() }) return false
    return password.any { it.isLetter() } && password.any { it.isDigit() }
}

internal fun redact(text: String, secret: String): String {
    if (secret.length < 8 || secret !in text) return text
    return text.replace(secret, "***")
}

internal fun remotePathAllowed(path: String): Boolean {
    if (path.length !in 2..400 || !path.startsWith("/")) return false
    if (".." in path) return false
    return path.none { it == '\'' || it == '\n' || it == '\r' || it == '\u0000' || it.isISOControl() }
}

internal fun uploadDirAllowed(path: String): Boolean =
    remotePathAllowed(path) && "/upload." in path

internal fun shellQuote(path: String): String? {
    if (!remotePathAllowed(path)) return null
    return "'$path'"
}
