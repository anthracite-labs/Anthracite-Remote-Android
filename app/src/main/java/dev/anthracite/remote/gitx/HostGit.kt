package dev.anthracite.remote.gitx

import dev.anthracite.remote.ExecResult
import dev.anthracite.remote.SshClient
import dev.anthracite.remote.host.PosixShell

/**
 * Host-local Git, driven through the host's own `git` binary.
 *
 * The phone never opens the repository, never rewrites it and never parses
 * human-readable, localized output. Structured formats are used instead:
 * `--porcelain=v2 -z` (NUL separated, paths verbatim), `--format` with explicit
 * separators, and `--no-color` diffs.
 */
internal data class GitStatusEntry(
    val path: String,
    val originalPath: String?,
    val indexStatus: Char,
    val workTreeStatus: Char,
    val untracked: Boolean,
    val conflicted: Boolean,
) {
    val staged: Boolean get() = indexStatus != '.' && indexStatus != '?' && !conflicted

    val hasWorktreeChange: Boolean get() = workTreeStatus != '.' && workTreeStatus != '?'

    val statusLabel: String
        get() = when {
            conflicted -> "conflict"
            untracked -> "untracked"
            originalPath != null -> "renamed"
            staged && hasWorktreeChange -> "staged+modified"
            staged -> "staged"
            else -> "modified"
        }
}

internal data class GitBranch(
    val head: String,
    val oid: String?,
    val upstream: String?,
    val ahead: Int,
    val behind: Int,
    val detached: Boolean,
)

internal data class GitStatus(
    val branch: GitBranch?,
    val entries: List<GitStatusEntry>,
    val unparsedRecords: Int,
) {
    val stagedCount: Int get() = entries.count { it.staged }
    val unstagedCount: Int get() = entries.count { it.hasWorktreeChange && !it.untracked }
    val untrackedCount: Int get() = entries.count { it.untracked }
    val conflictCount: Int get() = entries.count { it.conflicted }
    val clean: Boolean get() = entries.isEmpty()
}

internal data class GitCommit(val oid: String, val author: String, val relativeDate: String, val subject: String)

internal data class GitRef(val name: String, val current: Boolean)

/**
 * Parses `git status --porcelain=v2 --branch -z`.
 *
 * Records are NUL separated, so a path containing spaces, quotes, a leading
 * dash, UTF-8 or a newline is preserved exactly. Unrecognized records are
 * counted instead of being guessed at.
 */
internal object GitStatusParser {
    fun parse(stdout: String): GitStatus {
        var head: String? = null
        var oid: String? = null
        var upstream: String? = null
        var ahead = 0
        var behind = 0
        val entries = mutableListOf<GitStatusEntry>()
        var unparsed = 0

        val records = stdout.split('\u0000')
        var index = 0
        while (index < records.size) {
            val record = records[index]
            index++
            if (record.isEmpty()) continue
            when (record[0]) {
                '#' -> {
                    val header = record.removePrefix("# ").trim()
                    when {
                        header.startsWith("branch.oid ") -> oid = header.removePrefix("branch.oid ").trim()
                        header.startsWith("branch.head ") -> head = header.removePrefix("branch.head ").trim()
                        header.startsWith("branch.upstream ") ->
                            upstream = header.removePrefix("branch.upstream ").trim()
                        header.startsWith("branch.ab ") -> {
                            val parts = header.removePrefix("branch.ab ").trim().split(' ')
                            ahead = parts.getOrNull(0)?.removePrefix("+")?.toIntOrNull() ?: 0
                            behind = parts.getOrNull(1)?.removePrefix("-")?.toIntOrNull() ?: 0
                        }
                    }
                }
                '1' -> {
                    val fields = record.split(' ', limit = 9)
                    if (fields.size < 9) {
                        unparsed++
                        continue
                    }
                    val chars = statusChars(fields[1])
                    if (chars == null) {
                        unparsed++
                        continue
                    }
                    entries += GitStatusEntry(
                        path = fields[8],
                        originalPath = null,
                        indexStatus = chars.first,
                        workTreeStatus = chars.second,
                        untracked = false,
                        conflicted = false,
                    )
                }
                '2' -> {
                    val fields = record.split(' ', limit = 10)
                    if (fields.size < 10) {
                        unparsed++
                        continue
                    }
                    val chars = statusChars(fields[1])
                    if (chars == null) {
                        unparsed++
                        continue
                    }
                    val original = records.getOrNull(index)
                    if (original == null) {
                        unparsed++
                        continue
                    }
                    index++
                    entries += GitStatusEntry(
                        path = fields[9],
                        originalPath = original,
                        indexStatus = chars.first,
                        workTreeStatus = chars.second,
                        untracked = false,
                        conflicted = false,
                    )
                }
                'u' -> {
                    val fields = record.split(' ', limit = 11)
                    if (fields.size < 11) {
                        unparsed++
                        continue
                    }
                    val chars = statusChars(fields[1])
                    if (chars == null) {
                        unparsed++
                        continue
                    }
                    entries += GitStatusEntry(
                        path = fields[10],
                        originalPath = null,
                        indexStatus = chars.first,
                        workTreeStatus = chars.second,
                        untracked = false,
                        conflicted = true,
                    )
                }
                '?' -> entries += GitStatusEntry(
                    path = record.removePrefix("? "),
                    originalPath = null,
                    indexStatus = '?',
                    workTreeStatus = '?',
                    untracked = true,
                    conflicted = false,
                )
                '!' -> Unit
                else -> unparsed++
            }
        }

        val branch = if (head == null && oid == null) {
            null
        } else {
            val name = head ?: ""
            GitBranch(
                head = name,
                oid = oid?.takeIf { it != "(initial)" },
                upstream = upstream,
                ahead = ahead,
                behind = behind,
                detached = name == "(detached)",
            )
        }
        return GitStatus(branch, entries, unparsed)
    }

    private fun statusChars(value: String): Pair<Char, Char>? {
        if (value.length != 2) return null
        return value[0] to value[1]
    }
}

/** Parses `git branch --format=%(refname:short)%09%(HEAD)` lines. */
internal fun parseGitRefs(stdout: String): List<GitRef> = stdout.lineSequence()
    .filter { it.isNotBlank() }
    .mapNotNull { line ->
        val fields = line.split('\t')
        if (fields.size < 2) return@mapNotNull null
        val name = fields[0].trim()
        if (name.isEmpty()) return@mapNotNull null
        GitRef(name, fields[1].trim() == "*")
    }
    .toList()

/** Parses `git log --pretty=format:%H%x09%an%x09%ar%x09%s` output. */
internal fun parseGitLog(stdout: String): List<GitCommit> = stdout.lineSequence()
    .filter { it.isNotBlank() }
    .mapNotNull { line ->
        val fields = line.split('\t', limit = 4)
        if (fields.size < 4) return@mapNotNull null
        GitCommit(fields[0], fields[1], fields[2], fields[3])
    }
    .toList()

/**
 * Command grammar for every Git operation. Each builder takes validated tokens
 * (refs, hashes, paths) and quotes them exactly once.
 */
internal object GitCommands {
    private fun base(directory: String): String {
        require(PosixShell.isQuotablePath(directory)) { "invalid working directory" }
        return "git -C ${PosixShell.quote(directory)}"
    }

    fun status(directory: String): String = "${base(directory)} status --porcelain=v2 --branch -z"

    fun diff(directory: String, staged: Boolean, path: String? = null): String {
        val scope = if (staged) " --cached" else ""
        val target = path?.let { pathValue ->
            require(PosixShell.isQuotablePath(pathValue)) { "invalid path" }
            " -- ${PosixShell.quote(pathValue)}"
        }.orEmpty()
        return "${base(directory)} diff$scope --no-color --no-ext-diff --unified=3$target"
    }

    fun show(directory: String, commit: String): String {
        require(PosixShell.isCommitish(commit)) { "invalid commit" }
        return "${base(directory)} show --no-color --no-ext-diff --format=fuller ${PosixShell.quote(commit)}"
    }

    fun log(directory: String, limit: Int = 30): String {
        val count = limit.coerceIn(1, 200)
        return "${base(directory)} log -n $count --no-color --pretty=format:%H%x09%an%x09%ar%x09%s"
    }

    fun branches(directory: String): String =
        "${base(directory)} branch --no-color --format=%(refname:short)%09%(HEAD)"

    fun stage(directory: String, path: String): String {
        require(PosixShell.isQuotablePath(path)) { "invalid path" }
        return "${base(directory)} add -- ${PosixShell.quote(path)}"
    }

    fun unstage(directory: String, path: String): String {
        require(PosixShell.isQuotablePath(path)) { "invalid path" }
        return "${base(directory)} restore --staged -- ${PosixShell.quote(path)}"
    }

    fun commit(directory: String, message: String): String {
        val cleaned = message.replace('\n', ' ').replace('\r', ' ').trim()
        require(cleaned.isNotEmpty()) { "commit message is empty" }
        require(cleaned.length <= 2000) { "commit message is too long" }
        require(!cleaned.contains('\u0000')) { "commit message contains NUL" }
        return "${base(directory)} commit -m ${PosixShell.quote(cleaned)}"
    }

    fun fetch(directory: String): String = "${base(directory)} fetch --prune --no-color"

    fun pull(directory: String): String = "${base(directory)} pull --ff-only --no-color"

    /** Force is only ever `--force-with-lease`, and only after explicit confirmation. */
    fun push(directory: String, force: Boolean): String {
        val flags = if (force) "--force-with-lease --no-color" else "--no-color"
        return "${base(directory)} push $flags"
    }

    fun checkout(directory: String, ref: String): String {
        require(PosixShell.isGitRef(ref)) { "invalid branch name" }
        return "${base(directory)} switch -- ${PosixShell.quote(ref)}"
    }

    fun isRepository(directory: String): String =
        "${base(directory)} rev-parse --is-inside-work-tree"

    fun topLevel(directory: String): String = "${base(directory)} rev-parse --show-toplevel"
}

internal class GitClient(private val ssh: SshClient) {
    private fun run(command: String, timeoutMs: Long = TIMEOUT_MS): ExecResult =
        ssh.exec(command, timeoutMs = timeoutMs)

    fun status(directory: String): GitStatus = GitStatusParser.parse(run(GitCommands.status(directory)).stdout)

    fun diff(directory: String, staged: Boolean, path: String? = null): String =
        run(GitCommands.diff(directory, staged, path)).stdout

    fun show(directory: String, commit: String): String = run(GitCommands.show(directory, commit)).stdout

    fun log(directory: String): List<GitCommit> = parseGitLog(run(GitCommands.log(directory)).stdout)

    fun branches(directory: String): List<GitRef> = parseGitRefs(run(GitCommands.branches(directory)).stdout)

    fun stage(directory: String, path: String): ExecResult = run(GitCommands.stage(directory, path))

    fun unstage(directory: String, path: String): ExecResult = run(GitCommands.unstage(directory, path))

    fun commit(directory: String, message: String): ExecResult = run(GitCommands.commit(directory, message))

    fun fetch(directory: String): ExecResult = run(GitCommands.fetch(directory), 120_000)

    fun pull(directory: String): ExecResult = run(GitCommands.pull(directory), 120_000)

    fun push(directory: String, force: Boolean): ExecResult = run(GitCommands.push(directory, force), 120_000)

    fun checkout(directory: String, ref: String): ExecResult = run(GitCommands.checkout(directory, ref))

    fun isRepository(directory: String): Boolean = run(GitCommands.isRepository(directory)).stdout.trim() == "true"

    fun topLevel(directory: String): String? =
        run(GitCommands.topLevel(directory)).stdout.trim().ifBlank { null }

    private companion object {
        const val TIMEOUT_MS = 30_000L
    }
}
