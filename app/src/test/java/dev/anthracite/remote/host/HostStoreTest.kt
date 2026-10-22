package dev.anthracite.remote.host

import dev.anthracite.remote.AuthMethod
import dev.anthracite.remote.SavedSecrets
import dev.anthracite.remote.sha256Fingerprint
import dev.anthracite.remote.sshKeyType
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory `Preferences` that can be told to fail a commit once. */
private class FakePreferences : Preferences {
    val values = mutableMapOf<String, Any>()
    var failNextCommit = false

    override fun string(key: String): String? = values[key] as? String

    override fun int(key: String, fallback: Int): Int = values[key] as? Int ?: fallback

    override fun boolean(key: String, fallback: Boolean): Boolean = values[key] as? Boolean ?: fallback

    override fun putString(key: String, value: String) {
        values[key] = value
    }

    override fun putInt(key: String, value: Int) {
        values[key] = value
    }

    override fun putBoolean(key: String, value: Boolean) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }

    override fun commit(): Boolean {
        if (failNextCommit) {
            failNextCommit = false
            return false
        }
        return true
    }
}

private class FakeSecretStore : SecretStore {
    val blobs = mutableMapOf<String, ByteArray>()

    override fun write(name: String, bytes: ByteArray) {
        blobs[name] = bytes.copyOf()
    }

    override fun read(name: String): ByteArray? = blobs[name]?.copyOf()

    override fun delete(name: String) {
        blobs.remove(name)
    }
}

class HostStoreTest {
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun hostKey(seed: Byte = 1): ByteArray {
        val type = "ssh-ed25519".toByteArray(Charsets.US_ASCII)
        return ByteBuffer.allocate(4 + type.size + 4 + 32)
            .putInt(type.size).put(type).putInt(32).put(ByteArray(32) { seed }).array()
    }

    private fun profile(
        id: String = "0123456789abcdef",
        label: String = "Dev box",
        host: String = "example.internal",
        username: String = "ada",
        origin: HostOrigin = HostOrigin.CREATED,
        hostKey: ByteArray? = hostKey(),
    ) = HostProfile(
        id = id,
        label = label,
        host = host,
        port = 22,
        username = username,
        auth = AuthMethod.PASSWORD,
        remotePort = 4096,
        keyName = "",
        hostKey = hostKey,
        hostKeyType = hostKey?.let(::sshKeyType).orEmpty(),
        fingerprint = hostKey?.let(::sha256Fingerprint).orEmpty(),
        origin = origin,
        lastUsedAtMillis = 7,
    )

    private fun store(
        preferences: FakePreferences = FakePreferences(),
        secrets: FakeSecretStore = FakeSecretStore(),
    ) = Triple(HostStore(preferences, secrets, SecureRandom()), preferences, secrets)

    @Test
    fun `profile codec round trips labels containing separators and percent signs`() {
        val original = listOf(
            profile(label = "Dev\tbox\nline 2 100%"),
            profile(id = "fedcba9876543210", host = "second.example", origin = HostOrigin.IMPORTED_LEGACY, hostKey = null),
        )
        val decoded = ProfileCodec.decode(ProfileCodec.encode(original))
        assertNotNull(decoded)
        assertEquals(original, decoded)
        assertNull(decoded!![1].hostKey)
        assertArrayEquals(hostKey(), decoded[0].hostKey)
    }

    @Test
    fun `a corrupt record makes the whole list unreadable rather than dropping hosts`() {
        val encoded = ProfileCodec.encode(listOf(profile()))
        assertNull(ProfileCodec.decode(encoded.replace("ssh-ed25519", "ssh-ed25519\textra")))
        assertNull(ProfileCodec.decode(encoded.replace("0123456789abcdef", "not-a-valid-id")))
        assertNull(ProfileCodec.decode(encoded.replace("\t22\t", "\t70000\t")))
        assertNull(ProfileCodec.decode(encoded.replace("PASSWORD", "TELEPATHY")))
        assertNull(ProfileCodec.decode("%ZZ" + encoded))
        assertEquals(emptyList<HostProfile>(), ProfileCodec.decode(""))
    }

    @Test
    fun `an unreadable list is an error, not an empty list`() {
        val preferences = FakePreferences()
        preferences.values[HostStore.KEY_HOSTS] = "garbage"
        val (hostStore, _, _) = store(preferences = preferences)
        assertThrows(HostStoreException::class.java) { hostStore.list() }
    }

    @Test
    fun `legacy import refuses corrupt hosts without changing data or import marker`() {
        val (hostStore, preferences, secrets) = store()
        val corrupt = "unreadable existing profiles"
        preferences.values[HostStore.KEY_HOSTS] = corrupt
        assertThrows(HostStoreException::class.java) {
            hostStore.importLegacyIfNeeded(profile(), SavedSecrets("secret", null, null), key)
        }
        assertEquals(corrupt, preferences.values[HostStore.KEY_HOSTS])
        assertFalse(preferences.boolean(HostStore.KEY_LEGACY_IMPORTED, false))
        assertTrue(secrets.blobs.isEmpty())
        // After an explicit recovery of the saved list, import is still possible.
        preferences.values[HostStore.KEY_HOSTS] = ProfileCodec.encode(listOf(profile()))
        hostStore.importLegacyIfNeeded(profile(), null, key)
        assertEquals(2, hostStore.list().size)
    }

    @Test
    fun `malformed or inconsistent trust never becomes first use or overwrites another host`() {
        val (hostStore, preferences, secrets) = store()
        val first = profile()
        val second = profile(id = "fedcba9876543210", host = "other.example", hostKey = null)
        hostStore.save(first, SavedSecrets("existing-secret", null, null), key)
        hostStore.save(second, null, key)
        val original = preferences.string(HostStore.KEY_HOSTS)!!
        val records = original.split('\n').toMutableList()
        val fields = records.first().split('\t').toMutableList()
        for ((index, corrupt) in listOf(
            13 to "not-base64%", 13 to java.util.Base64.getEncoder().encodeToString(byteArrayOf()),
            9 to "ssh-rsa", 10 to "SHA256:wrong",
        )) {
            val modified = fields.toMutableList().also { it[index] = corrupt }
            preferences.values[HostStore.KEY_HOSTS] = (listOf(modified.joinToString("\t")) + records.drop(1)).joinToString("\n")
            assertThrows(HostStoreException::class.java) { hostStore.list() }
            assertThrows(HostStoreException::class.java) { hostStore.save(second, null, key) }
            assertEquals(modified.joinToString("\t"), preferences.string(HostStore.KEY_HOSTS)!!.substringBefore('\n'))
            assertNotNull(secrets.blobs[first.id + ".bin"])
        }
        preferences.values[HostStore.KEY_HOSTS] = original
        assertEquals("existing-secret", hostStore.secretsFor(first, key).password)
        assertEquals(2, hostStore.list().size)
        assertNull(ProfileCodec.decode(ProfileCodec.encode(listOf(second.copy(fingerprint = "SHA256:stale")))))
        assertNull(ProfileCodec.decode("   "))
    }

    @Test
    fun `failed host key commit never establishes trust and can be retried`() {
        val (hostStore, preferences, _) = store()
        val host = profile(hostKey = null)
        hostStore.save(host, SavedSecrets("keep", null, null), key)
        val approved = hostKey(3)
        preferences.failNextCommit = true
        assertThrows(HostStoreException::class.java) {
            hostStore.updateHostKey(host.id, approved, sshKeyType(approved), sha256Fingerprint(approved), host.endpoint)
        }
        assertNull(hostStore.find(host.id)!!.hostKey)
        assertEquals("keep", hostStore.secretsFor(host, key).password)
        assertThrows(HostStoreException::class.java) {
            hostStore.updateHostKey(host.id, approved, sshKeyType(approved), sha256Fingerprint(approved), "other@host:22")
        }
        assertThrows(IllegalArgumentException::class.java) {
            hostStore.updateHostKey(host.id, approved, "ssh-rsa", sha256Fingerprint(approved), host.endpoint)
        }
        assertNull(hostStore.find(host.id)!!.hostKey)
        val saved = hostStore.updateHostKey(host.id, approved, sshKeyType(approved), sha256Fingerprint(approved), host.endpoint)
        assertArrayEquals(approved, saved.hostKey)
        assertThrows(HostStoreException::class.java) {
            val replacement = hostKey(4)
            hostStore.updateHostKey(host.id, replacement, sshKeyType(replacement), sha256Fingerprint(replacement))
        }
        assertArrayEquals(approved, hostStore.find(host.id)!!.hostKey)
        assertThrows(HostStoreException::class.java) {
            hostStore.save(saved.copy(hostKey = null, hostKeyType = "", fingerprint = ""), null, key)
        }
        assertArrayEquals(approved, hostStore.find(host.id)!!.hostKey)
    }

    @Test
    fun `save seals the credential and forget erases it before the list`() {
        val secrets = FakeSecretStore()
        val (hostStore, _, _) = store(secrets = secrets)
        val host = profile()
        hostStore.save(host, SavedSecrets("hunter2", null, null), key)
        assertEquals(host, hostStore.find(host.id))
        assertNotNull(secrets.blobs[host.id + ".bin"])

        val opened = hostStore.secretsFor(host, key)
        assertEquals("hunter2", opened.password)

        hostStore.forget(host.id)
        assertNull(hostStore.find(host.id))
        assertNull(secrets.blobs[host.id + ".bin"])
    }

    @Test
    fun `a failed commit restores the previous credential`() {
        val secrets = FakeSecretStore()
        val (hostStore, preferences, _) = store(secrets = secrets)
        val host = profile()
        hostStore.save(host, SavedSecrets("original", null, null), key)
        val prior = secrets.blobs[host.id + ".bin"]!!.copyOf()

        preferences.failNextCommit = true
        assertThrows(HostStoreException::class.java) {
            hostStore.save(host.copy(label = "Renamed"), SavedSecrets("replacement", null, null), key)
        }
        assertArrayEquals(prior, secrets.blobs[host.id + ".bin"])
        assertEquals("Dev box", hostStore.find(host.id)?.label)
        assertEquals("original", hostStore.secretsFor(host, key).password)
    }

    @Test
    fun `the host cap is enforced`() {
        val (hostStore, _, _) = store()
        for (index in 0 until HostStore.MAX_HOSTS) {
            val id = "%016x".format(index + 1)
            hostStore.save(profile(id = id, host = "host$index"), null, key)
        }
        assertEquals(HostStore.MAX_HOSTS, hostStore.list().size)
        assertThrows(HostStoreException::class.java) {
            hostStore.save(profile(id = "%016x".format(999), host = "extra"), null, key)
        }
        // Re-saving an existing host is allowed at the cap.
        hostStore.save(profile(id = "%016x".format(1), host = "host0"), null, key)
    }

    @Test
    fun `legacy import happens once and keeps the legacy record read only`() {
        val (hostStore, preferences, _) = store()
        val legacy = profile(origin = HostOrigin.IMPORTED_LEGACY)
        val imported = hostStore.importLegacyIfNeeded(legacy, SavedSecrets("legacy-pass", null, null), key)
        assertNotNull(imported)
        assertEquals(1, hostStore.list().size)
        assertEquals(HostOrigin.IMPORTED_LEGACY, hostStore.list().single().origin)
        assertEquals("legacy-pass", hostStore.secretsFor(hostStore.list().single(), key).password)
        assertTrue(preferences.values[HostStore.KEY_LEGACY_IMPORTED] as Boolean)

        assertNull(hostStore.importLegacyIfNeeded(legacy, SavedSecrets("legacy-pass", null, null), key))
        assertEquals(1, hostStore.list().size)
    }

    @Test
    fun `a legacy host without usable credentials is still imported and asks again`() {
        val (hostStore, _, _) = store()
        hostStore.importLegacyIfNeeded(profile(origin = HostOrigin.IMPORTED_LEGACY), null, key)
        val stored = hostStore.list().single()
        assertEquals("example.internal", stored.host)
        // No credential is stored, so the UI must ask instead of attempting an empty login.
        assertNull(hostStore.secretsFor(stored, key).password)
        assertNull(hostStore.secretsFor(stored, key).privateKey)
        assertFalse(hostStore.hasSecrets(stored.id))
    }

    @Test
    fun `host key updates are exact and preserve everything else`() {
        val (hostStore, _, _) = store()
        val host = profile(hostKey = null)
        hostStore.save(host, null, key)
        val approved = hostKey(9)
        hostStore.updateHostKey(host.id, approved, sshKeyType(approved), sha256Fingerprint(approved))
        val updated = hostStore.find(host.id)!!
        assertArrayEquals(approved, updated.hostKey)
        assertEquals("ssh-ed25519", updated.hostKeyType)
        assertEquals(sha256Fingerprint(approved), updated.fingerprint)
        assertEquals(host.label, updated.label)
    }

    @Test
    fun `markUsed only touches the last-used stamp`() {
        val (hostStore, _, _) = store()
        val host = profile()
        hostStore.save(host, null, key)
        hostStore.markUsed(host.id, 99)
        assertEquals(99, hostStore.find(host.id)!!.lastUsedAtMillis)
        assertEquals(host, hostStore.find(host.id)!!.copy(lastUsedAtMillis = 7))
    }

    @Test
    fun `a profile with an id outside the grammar is refused before any write`() {
        val (hostStore, preferences, _) = store()
        assertThrows(IllegalArgumentException::class.java) {
            hostStore.save(profile(id = "not-hex"), null, key)
        }
        assertNull(preferences.values[HostStore.KEY_HOSTS])
    }
}
