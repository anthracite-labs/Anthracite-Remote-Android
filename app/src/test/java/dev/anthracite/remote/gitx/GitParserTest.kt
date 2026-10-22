package dev.anthracite.remote.gitx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GitParserTest {
    private val statusOutput = buildString {
        append("# branch.oid 1234567890abcdef\u0000")
        append("# branch.head main\u0000")
        append("# branch.upstream origin/main\u0000")
        append("# branch.ab +2 -1\u0000")
        append("1 .M N... 100644 100644 100644 aaaa bbbb src/main.kt\u0000")
        append("1 M. N... 100644 100644 100644 cccc dddd README.md\u0000")
        append("2 R. N... 100644 100644 100644 eeee ffff R100 new name.kt\u0000old name.kt\u0000")
        append("u UU N... 100644 100644 100644 100644 hhhh iiii jjjj conflict.txt\u0000")
        append("? notes with space.txt\u0000")
        append("! ignored.log\u0000")
    }

    @Test
    fun `porcelain v2 records are parsed without losing spaces`() {
        val status = GitStatusParser.parse(statusOutput)
        assertEquals(5, status.entries.size)
        assertEquals(0, status.unparsedRecords)
        assertEquals("main", status.branch?.head)
        assertEquals("origin/main", status.branch?.upstream)
        assertEquals(2, status.branch?.ahead)
        assertEquals(1, status.branch?.behind)
        assertFalse(status.branch?.detached ?: true)

        val renamed = status.entries[2]
        assertEquals("new name.kt", renamed.path)
        assertEquals("old name.kt", renamed.originalPath)
        assertTrue(renamed.staged)

        val conflict = status.entries[3]
        assertTrue(conflict.conflicted)
        assertEquals("conflict.txt", conflict.path)

        val untracked = status.entries[4]
        assertEquals("notes with space.txt", untracked.path)
        assertTrue(untracked.untracked)
        assertTrue(status.entries.none { it.path == "ignored.log" })
    }

    @Test
    fun `counts separate staged, unstaged, untracked and conflicted work`() {
        val status = GitStatusParser.parse(statusOutput)
        assertEquals(2, status.stagedCount)
        assertEquals(2, status.unstagedCount)
        assertEquals(1, status.untrackedCount)
        assertEquals(1, status.conflictCount)
        assertFalse(status.clean)
    }

    @Test
    fun `unknown records are counted, not guessed`() {
        val status = GitStatusParser.parse("X nonsense\u0000? real.txt\u0000")
        assertEquals(1, status.unparsedRecords)
        assertEquals(1, status.entries.size)
        assertEquals("real.txt", status.entries.single().path)
    }

    @Test
    fun `a malformed record is counted and does not corrupt later ones`() {
        val status = GitStatusParser.parse("1 broken\u0000? ok.txt\u0000")
        assertEquals(1, status.unparsedRecords)
        assertEquals("ok.txt", status.entries.single().path)
    }

    @Test
    fun `detached head and initial repositories are described honestly`() {
        val detached = GitStatusParser.parse("# branch.oid abcdef12\u0000# branch.head (detached)\u0000")
        assertTrue(detached.branch?.detached ?: false)
        assertTrue(detached.clean)

        val initial = GitStatusParser.parse("# branch.oid (initial)\u0000# branch.head main\u0000")
        assertNull(initial.branch?.oid)
        assertEquals("main", initial.branch?.head)

        val bare = GitStatusParser.parse("")
        assertNull(bare.branch)
        assertTrue(bare.entries.isEmpty())
    }

    @Test
    fun `refs and log lines are parsed at their field boundaries`() {
        val refs = parseGitRefs("main\t*\nfeature/login\t \norigin/main\t \n")
        assertEquals(3, refs.size)
        assertTrue(refs.first().current)
        assertEquals("feature/login", refs[1].name)
        assertEquals("origin/main", refs[2].name)

        val log = parseGitLog(
            "abcdef123456\tada\t2 hours ago\tFix the build\n" +
                "123456abcdef\tgrace\tyesterday\tAdd docs\n",
        )
        assertEquals(2, log.size)
        assertEquals("Fix the build", log[0].subject)
        assertEquals("2 hours ago", log[0].relativeDate)
        assertEquals("grace", log[1].author)
    }

    @Test
    fun `every git command pins the directory and disables helpers`() {
        val directory = "/srv/my project"
        val status = GitCommands.status(directory)
        assertTrue(status.startsWith("git -c color.ui=false -C '/srv/my project'"))
        assertTrue(status.contains("--porcelain=v2"))
        // `--no-ext-diff` is a diff option; `git status` has no such flag.
        assertFalse(status.contains("--no-ext-diff"))

        val diff = GitCommands.diff(directory, staged = false, path = "/srv/my project/a b.kt")
        assertTrue(diff.contains(" -- '/srv/my project/a b.kt'"))
        assertTrue(GitCommands.diff(directory, staged = true).contains("--cached"))

        val push = GitCommands.push(directory, force = true)
        assertTrue(push.contains("--force-with-lease"))
        assertFalse(push.contains("--force "))
        assertFalse(GitCommands.push(directory, force = false).contains("force"))

        val commit = GitCommands.commit(directory, "message with 'quotes' and \$dollars")
        assertTrue(commit.contains("-m '"))
        assertTrue(commit.contains("\$dollars"))
        assertFalse(commit.contains("\n"))
    }

    @Test
    fun `checkout refuses option-shaped refs and commit messages are bounded`() {
        assertTrue(GitCommands.checkout("/srv", "main").contains("switch -- main"))
        assertThrows(IllegalArgumentException::class.java) { GitCommands.checkout("/srv", "-f") }
        assertThrows(IllegalArgumentException::class.java) { GitCommands.commit("/srv", "  ") }
        assertThrows(IllegalArgumentException::class.java) { GitCommands.commit("/srv", "x".repeat(5000)) }

        val multiline = GitCommands.commit("/srv", "one\ntwo")
        assertFalse(multiline.contains("\n"))
    }
}
