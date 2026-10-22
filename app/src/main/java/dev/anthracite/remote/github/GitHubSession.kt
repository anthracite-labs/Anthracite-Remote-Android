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
    /**
     * Returns the stored token, or null when none is stored.
     *
     * A blob that exists but cannot be opened is *not* reported as "signed out":
     * that would hide tampering or a rotated Keystore key behind an innocent
     * state. It throws instead, and the caller clears the unusable blob and asks
     * the user to sign in again.
     */
    fun read(): String? {
        val blob = secrets.read(name) ?: return null
        val opened = try {
            CredentialCrypto.open(blob, key)
        } catch (error: Exception) {
            throw GitHubTokenException("The stored GitHub token could not be decrypted", error)
        }
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
        secrets.delete(name)
    }

    companion object {
        /** Valid under [dev.anthracite.remote.host.SecretNames]; asserted by a unit test. */
        const val DEFAULT_NAME = "github_token.v1"
    }
}

internal class GitHubTokenException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
