package dev.anthracite.remote.github

import dev.anthracite.remote.CredentialCrypto
import dev.anthracite.remote.SavedSecrets
import dev.anthracite.remote.host.SecretStore
import javax.crypto.SecretKey

/**
 * The GitHub token, sealed with the same Keystore-backed key as SSH credentials.
 *
 * The token is never written to a host, never logged, and never included in a bug
 * report; [LogRedactor] is not involved because the token never reaches host
 * output in the first place. A token GitHub rejects (HTTP 401) is dropped instead
 * of being retried.
 */
internal class GitHubTokenStore(
    private val secrets: SecretStore,
    private val key: SecretKey,
    private val name: String = DEFAULT_NAME,
) {
    fun read(): String? {
        val blob = runCatching { secrets.read(name) }.getOrNull() ?: return null
        val opened = runCatching { CredentialCrypto.open(blob, key) }.getOrNull() ?: return null
        return opened.password?.takeIf { it.isNotBlank() }
    }

    fun write(token: String) {
        require(token.isNotBlank()) { "token is empty" }
        val sealed = CredentialCrypto.seal(SavedSecrets(token.trim(), null, null), key)
        try {
            secrets.write(name, sealed)
        } finally {
            sealed.fill(0)
        }
    }

    fun clear() {
        runCatching { secrets.delete(name) }
    }

    private companion object {
        const val DEFAULT_NAME = "github_token.v1"
    }
}
