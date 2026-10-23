package dev.anthracite.remote.files

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpATTRS
import com.jcraft.jsch.SftpException
import dev.anthracite.remote.SshClient
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Remote path handling for the SFTP browser.
 *
 * The browser is not a sandbox: the SSH user's own permissions decide what may
 * be touched. What this policy does guarantee is that navigation cannot escape
 * through `..`, that names containing NUL or control characters are rejected,
 * and that recursive deletion never follows a symlink.
 */
internal object RemotePath {
    const val MAX_LENGTH = 4096

    fun isValid(path: String): Boolean {
        if (path.isEmpty() || path.length > MAX_LENGTH) return false
        if (!path.startsWith("/")) return false
        return path.none { it == '\u0000' || it.code < 0x20 || it.code == 0x7f }
    }

    /** Resolves [target] against [current]; returns null for an invalid result. */
    fun resolve(current: String, target: String): String? {
        if (target.isEmpty()) return null
        val base = if (target.startsWith("/")) {
            target
        } else {
            val root = if (current.endsWith("/")) current else "$current/"
            root + target
        }
        val absolute = base.startsWith("/")
        val parts = ArrayDeque<String>()
        for (segment in base.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> {
                    if (segment.length > 255) return null
                    if (segment.any { it == '\u0000' || it.code < 0x20 || it.code == 0x7f }) return null
                    parts.addLast(segment)
                }
            }
        }
        val normalized = "/" + parts.joinToString("/")
        return if (absolute) normalized else null
    }

    fun join(directory: String, name: String): String? {
        if (name.isEmpty() || name == "." || name == "..") return null
        if (name.contains('/') || name.any { it == '\u0000' || it.code < 0x20 }) return null
        val base = if (directory.endsWith("/")) directory else "$directory/"
        return resolve("/", base + name)
    }

    fun parent(path: String): String? {
        if (!isValid(path) || path == "/") return null
        val trimmed = path.trimEnd('/')
        val index = trimmed.lastIndexOf('/')
        return if (index <= 0) "/" else trimmed.substring(0, index)
    }

    fun name(path: String): String {
        val trimmed = path.trimEnd('/')
        val index = trimmed.lastIndexOf('/')
        return if (index < 0) trimmed else trimmed.substring(index + 1)
    }

    /** True when [candidate] is [base] or inside it, after normalisation. */
    fun isWithin(base: String, candidate: String): Boolean {
        val resolvedBase = resolve("/", base) ?: return false
        val resolvedCandidate = resolve("/", candidate) ?: return false
        if (resolvedCandidate == resolvedBase) return true
        val prefix = if (resolvedBase == "/") "/" else "$resolvedBase/"
        return resolvedCandidate.startsWith(prefix)
    }
}

/** Consecutive zero-length reads tolerated before the transfer is called stalled. */
private const val MAX_EMPTY_READS = 64

/**
 * Reads a stream into memory but never past [maxBytes].
 *
 * This is the bound that matters: `stat` is only a hint, and a hostile or busy
 * host can hand back more bytes than it advertised. The buffer grows only with
 * bytes actually read, so a stream that keeps producing data cannot push the
 * process past the cap, and a stream that stops making progress is reported
 * instead of spinning forever on the caller's thread.
 */
internal fun readBounded(input: java.io.InputStream, maxBytes: Long): ByteArray {
    require(maxBytes > 0) { "transfer limit must be positive" }
    val output = ByteArrayOutputStream(minOf(maxBytes, 1L shl 20).toInt())
    val buffer = ByteArray(32 * 1024)
    var total = 0L
    var stalled = 0
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (read == 0) {
            if (++stalled > MAX_EMPTY_READS) {
                throw RemoteFileException("The transfer stalled without progress")
            }
            continue
        }
        stalled = 0
        total += read
        if (total > maxBytes) {
            throw RemoteFileException("The transfer exceeded the ${maxBytes / 1024} KB limit while reading")
        }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

internal data class RemoteEntry(
    val name: String,
    val path: String,
    val directory: Boolean,
    val symlink: Boolean,
    val sizeBytes: Long,
    val modifiedSeconds: Long,
    val permissions: Int,
) {
    val permissionText: String get() = formatPermissions(permissions)
    val hidden: Boolean get() = name.startsWith(".")
}

internal data class RemoteStat(
    val path: String,
    val directory: Boolean,
    val symlink: Boolean,
    val sizeBytes: Long,
    val modifiedSeconds: Long,
    val permissions: Int,
    val uid: Int,
    val gid: Int,
) {
    val permissionText: String get() = formatPermissions(permissions)
}

internal fun formatPermissions(permissions: Int): String {
    val builder = StringBuilder(10)
    val type = when (permissions and 0xf000) {
        0x4000 -> 'd'
        0xa000 -> 'l'
        0x2000 -> 'c'
        0x6000 -> 'b'
        0x1000 -> 'p'
        0xc000 -> 's'
        else -> '-'
    }
    builder.append(type)
    val bits = listOf(0x100, 0x80, 0x40, 0x20, 0x10, 0x8, 0x4, 0x2, 0x1)
    val letters = "rwxrwxrwx"
    for (index in bits.indices) {
        builder.append(if (permissions and bits[index] != 0) letters[index] else '-')
    }
    return builder.toString()
}

internal class RemoteFileException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * SFTP operations with bounded output and explicit failure text.
 *
 * Transfers use temporary names in the destination directory and a rename, so an
 * interrupted download or upload cannot leave a half file in place under the
 * final name.
 */
internal class SftpBrowser(private val ssh: SshClient) {
    fun list(path: String, maxEntries: Int = MAX_ENTRIES): List<RemoteEntry> {
        require(RemotePath.isValid(path)) { "invalid remote path" }
        val entries = withSftp { sftp ->
            val listing = sftp.ls(path) ?: return@withSftp emptyList()
            listing.mapNotNull { raw ->
                val entry = raw
                val name = entry.filename
                if (name == "." || name == "..") return@mapNotNull null
                val attrs = entry.attrs ?: return@mapNotNull null
                val child = RemotePath.join(path, name) ?: return@mapNotNull null
                RemoteEntry(
                    name = name,
                    path = child,
                    directory = attrs.isDir,
                    symlink = attrs.isLink,
                    sizeBytes = attrs.size,
                    modifiedSeconds = attrs.mTime.toLong(),
                    permissions = attrs.permissions,
                )
            }.sortedWith(compareByDescending<RemoteEntry> { it.directory }.thenBy { it.name.lowercase() })
        }
        if (entries.size > maxEntries) {
            throw RemoteFileException("This directory has more than $maxEntries entries; narrow it before browsing.")
        }
        return entries
    }

    fun stat(path: String, followLinks: Boolean = false): RemoteStat? {
        require(RemotePath.isValid(path)) { "invalid remote path" }
        return withSftp { sftp ->
            val attrs = try {
                if (followLinks) sftp.stat(path) else sftp.lstat(path)
            } catch (error: SftpException) {
                if (error.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) null else throw error
            } ?: return@withSftp null
            toStat(path, attrs)
        }
    }

    fun mkdir(path: String) {
        require(RemotePath.isValid(path)) { "invalid remote path" }
        withSftp { it.mkdir(path) }
    }

    fun rename(from: String, to: String) {
        require(RemotePath.isValid(from) && RemotePath.isValid(to)) { "invalid remote path" }
        if (from == to) return
        withSftp { it.rename(from, to) }
    }

    /**
     * Deletes a file, or a directory tree when [recursive] is set.
     *
     * Every entry is `lstat`-ed first: a symlink is deleted as a link and never
     * followed, so a hostile tree cannot redirect the delete outside the chosen
     * directory. The depth and entry caps bound the work.
     *
     * This is **not** an atomic operation and cannot be made one here: SFTP v3
     * (what this transport speaks) has no handle-relative remove, no `openat`,
     * and no conditional delete. The protocol offers nothing that would let the
     * server refuse to act on a path that changed since it was inspected. What
     * the code does instead is refuse to keep going when the tree stops looking
     * like the tree that was listed — see [deleteTree].
     */
    fun delete(path: String, recursive: Boolean) {
        require(RemotePath.isValid(path)) { "invalid remote path" }
        if (path == "/" || RemotePath.name(path).isEmpty()) {
            throw RemoteFileException("Refusing to delete a filesystem root")
        }
        val target = stat(path, followLinks = false)
            ?: throw RemoteFileException("That path no longer exists on the host")
        if (!target.directory || target.symlink) {
            withSftp { it.rm(path) }
            return
        }
        if (!recursive) {
            withSftp { it.rmdir(path) }
            return
        }
        deleteTree(path, depth = 0)
    }

    private fun deleteTree(path: String, depth: Int) {
        if (depth > MAX_DEPTH) throw RemoteFileException("Directory nesting is too deep to delete safely")
        var count = 0
        for (entry in list(path)) {
            count++
            if (count > MAX_ENTRIES) throw RemoteFileException("Directory has too many entries to delete safely")
            // The listing is a snapshot: only paths still inside the directory
            // being deleted are acted on, whatever the listing claimed.
            if (!RemotePath.isWithin(path, entry.path)) {
                throw RemoteFileException(
                    "A directory changed while it was being deleted; nothing further was removed",
                )
            }
            // The listing is a snapshot, and another process on the host can
            // replace an entry before it is removed — a directory with a symlink
            // to somewhere else, which `ls` would then follow into, or any entry
            // whose kind changed since it was listed. Re-read every entry with
            // `lstat` immediately before acting and stop if it is no longer the
            // entry that was listed.
            //
            // This narrows the window; it does not close it. No SFTP v3 request
            // can be made conditional on what was read, so the honest statement
            // is "we stop when we can tell", never "this cannot be redirected".
            val current = stat(entry.path, followLinks = false)
                ?: throw RemoteFileException(
                    "A directory changed while it was being deleted; nothing further was removed",
                )
            if (current.directory != entry.directory || current.symlink != entry.symlink) {
                throw RemoteFileException(
                    "A directory changed while it was being deleted; nothing further was removed",
                )
            }
            if (entry.directory && !entry.symlink) {
                deleteTree(entry.path, depth + 1)
            } else {
                withSftp { it.rm(entry.path) }
            }
        }
        withSftp { it.rmdir(path) }
    }

    /**
     * Downloads a file with the byte cap enforced *while streaming*.
     *
     * `stat` is only a hint: a file can grow between the check and the read, and a
     * server may report a size it does not honour. The reader therefore aborts as
     * soon as the cap would be exceeded, before the buffer grows past it, and the
     * partial content is discarded.
     */
    fun download(path: String, maxBytes: Long = MAX_DOWNLOAD_BYTES): ByteArray {
        require(RemotePath.isValid(path)) { "invalid remote path" }
        require(maxBytes > 0) { "download limit must be positive" }
        val attributes = stat(path, followLinks = true) ?: throw RemoteFileException("File not found")
        if (attributes.directory) throw RemoteFileException("That path is a directory")
        if (attributes.sizeBytes > maxBytes) throw tooLarge(attributes.sizeBytes, maxBytes)
        return withSftp { sftp ->
            sftp.get(path).use { input -> readBounded(input, maxBytes) }
        }
    }

    private fun tooLarge(observed: Long, maxBytes: Long): RemoteFileException =
        RemoteFileException("File is ${observed / 1024} KB; the transfer limit is ${maxBytes / 1024} KB")

    /**
     * Uploads to a unique temporary name in the destination directory and renames.
     *
     * SFTP v3 defines exclusive open, but JSch's put(InputStream) API does not
     * request it, and ordinary SFTP rename is not a portable no-replace operation.
     * The two existence checks only narrow the race; another client may create
     * the destination between the last check and rename. The unique temporary
     * name isolates partial uploads, not concurrent writes to the final path.
     *
     * There is deliberately no atomic no-replace mode here: the server cannot
     * provide one through this transport, so this method refuses to imply it.
     * Callers that need a guarantee that a concurrent creation cannot be
     * replaced must not use SFTP upload for that path. When a creation is
     * detected at either check the upload is refused and the temporary file is
     * removed; a creation in the final instant between the last check and the
     * rename cannot be detected and may be replaced by the rename.
     */
    fun upload(path: String, bytes: ByteArray, overwrite: Boolean) {
        require(RemotePath.isValid(path)) { "invalid remote path" }
        val parent = RemotePath.parent(path) ?: throw RemoteFileException("Destination has no parent directory")
        val name = RemotePath.name(path)
        if (stat(path, followLinks = false) != null && !overwrite) {
            throw RemoteFileException("A file already exists at that path")
        }
        val temporary = tempPath(parent, name)
        withSftp { sftp ->
            try {
                sftp.put(ByteArrayInputStream(bytes), temporary, ChannelSftp.OVERWRITE)
                if (!overwrite && existsOn(sftp, path)) {
                    throw RemoteFileException("A file appeared at that path while the upload was running")
                }
                sftp.rename(temporary, path)
            } catch (error: Exception) {
                runCatching { sftp.rm(temporary) }
                throw error
            }
        }
    }

    /** Same channel, no second SFTP session, no symlink following. */
    private fun existsOn(sftp: ChannelSftp, path: String): Boolean = try {
        sftp.lstat(path)
        true
    } catch (error: SftpException) {
        if (error.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) false else throw error
    }

    /** Short ASCII stem: the server's 255 limit is in bytes, not UTF-16 chars. */
    private fun tempPath(parent: String, name: String): String {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(16)
        val stem = name.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }
            .take(32).ifEmpty { "upload" }
        return "$parent/$HIDDEN_PREFIX$stem.$suffix$TEMP_SUFFIX"
    }

    private fun <T> withSftp(block: (ChannelSftp) -> T): T = ssh.withSession { session ->
        val sftp = session.openChannel("sftp") as ChannelSftp
        sftp.connect(CONNECT_TIMEOUT_MS)
        try {
            block(sftp)
        } catch (error: SftpException) {
            throw RemoteFileException(describe(error), error)
        } finally {
            runCatching { sftp.disconnect() }
        }
    }

    private fun toStat(path: String, attrs: SftpATTRS): RemoteStat = RemoteStat(
        path = path,
        directory = attrs.isDir,
        symlink = attrs.isLink,
        sizeBytes = attrs.size,
        modifiedSeconds = attrs.mTime.toLong(),
        permissions = attrs.permissions,
        uid = attrs.uId,
        gid = attrs.gId,
    )

    private fun describe(error: SftpException): String = when (error.id) {
        ChannelSftp.SSH_FX_NO_SUCH_FILE -> "Path not found on the host"
        ChannelSftp.SSH_FX_PERMISSION_DENIED -> "The SSH user is not allowed to do that"
        ChannelSftp.SSH_FX_FAILURE -> error.message ?: "The SFTP server refused the request"
        else -> error.message ?: "SFTP error ${error.id}"
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000
        const val MAX_ENTRIES = 5_000
        const val MAX_DEPTH = 32
        const val MAX_DOWNLOAD_BYTES = 8L * 1024 * 1024
        const val HIDDEN_PREFIX = "."
        const val TEMP_SUFFIX = ".anthracite.tmp"
    }
}
