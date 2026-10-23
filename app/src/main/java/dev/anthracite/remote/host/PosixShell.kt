package dev.anthracite.remote.host

/**
 * The single quoting and validation gate for every structured command the app
 * sends to a host.
 *
 * Rules:
 * * Nothing is ever shipped through `eval`, and no GUI field is concatenated raw
 *   into a command string.
 * * Tokens that must match a grammar (identifiers, refs, container ids) are
 *   validated before use, not escaped after the fact.
 * * Free-form values (paths, messages) are single-quoted with the POSIX
 *   `'\''` idiom after control characters are rejected.
 */
internal object PosixShell {
    /** Quote one value for a POSIX shell. Rejects NUL, which cannot be quoted. */
    fun quote(value: String): String {
        require(!value.contains('\u0000')) { "value contains NUL" }
        if (value.isEmpty()) return "''"
        if (value.all { it.isSafeBare() }) return value
        return "'" + value.replace("'", "'\\''") + "'"
    }

    private fun Char.isSafeBare(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' ||
            this == '.' || this == '_' || this == '-' || this == '/' || this == ':' ||
            this == '@' || this == '+' || this == ',' || this == '='

    /**
     * A remote path: absolute or home-relative, printable, bounded. Traversal is
     * a filesystem concern handled by [dev.anthracite.remote.files.RemotePath],
     * not by the shell layer, but control characters are rejected here because
     * they can split a command.
     */
    fun isQuotablePath(value: String): Boolean {
        if (value.isEmpty() || value.length > MAX_PATH) return false
        return value.none { it.code < 0x20 || it.code == 0x7f }
    }

    /** Identifier grammar for names that appear as command arguments. */
    fun isIdentifier(value: String): Boolean = IDENTIFIER.matches(value)

    /** Session/container names: no leading dash, no separators used by the tools. */
    fun isToolName(value: String): Boolean = TOOL_NAME.matches(value)

    /** A single executable token, no arguments, no shell syntax. */
    fun isExecutableToken(value: String): Boolean = EXECUTABLE.matches(value)

    /** Git ref names (branches/tags), conservative subset of `git check-ref-format`. */
    fun isGitRef(value: String): Boolean {
        if (value.isEmpty() || value.length > 255) return false
        if (value.startsWith("-") || value.startsWith("/") || value.endsWith("/")) return false
        if (value.endsWith(".lock") || value.contains("..") || value.contains("//")) return false
        if (value.any { it.code < 0x21 || it.code == 0x7f }) return false
        return FORBIDDEN_REF_CHARS.none { value.contains(it) }
    }

    /** Commit-ish: hex object id, or a validated ref, or HEAD-like keyword. */
    fun isCommitish(value: String): Boolean =
        value == "HEAD" || HASH.matches(value) || isGitRef(value)

    /** Docker/Podman container, image or pod identifier. */
    fun isContainerId(value: String): Boolean = CONTAINER_ID.matches(value)

    /**
     * Container image reference: `registry/owner/name:tag` or `...@sha256:...`.
     * Deliberately narrower than "anything Docker accepts", because the value is
     * interpolated into a host command.
     */
    fun isImageReference(value: String): Boolean =
        value.length <= 255 && !value.contains("..") && IMAGE_REFERENCE.matches(value)

    /** Kubernetes DNS-1123 name used for namespaces and resources. */
    fun isKubernetesName(value: String): Boolean = KUBERNETES_NAME.matches(value)

    /** Kubernetes qualified resource name (`deployment/api`, `pod/web-0`). */
    fun isKubernetesResource(value: String): Boolean {
        val parts = value.split('/')
        return parts.size == 2 && parts.all { isKubernetesName(it) }
    }

    /** A package identifier accepted by a native package manager. */
    fun isPackageName(value: String): Boolean = PACKAGE_NAME.matches(value)

    const val MAX_PATH = 4096

    private val IDENTIFIER = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    private val TOOL_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,47}")
    private val EXECUTABLE = Regex("[A-Za-z0-9_./+-]{1,200}")
    private val HASH = Regex("[0-9a-fA-F]{4,64}")
    private val CONTAINER_ID = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")
    private val IMAGE_REFERENCE =
        Regex("[A-Za-z0-9][A-Za-z0-9._/-]{0,200}(:[A-Za-z0-9._-]{1,64})?(@sha256:[a-f0-9]{64})?")
    private val KUBERNETES_NAME = Regex("[a-z0-9]([-a-z0-9.]{0,251}[a-z0-9])?")
    private val PACKAGE_NAME = Regex("[a-z0-9][a-z0-9+._-]{0,127}")
    private val FORBIDDEN_REF_CHARS = listOf(' ', '~', '^', ':', '?', '*', '[', '\\')
}
