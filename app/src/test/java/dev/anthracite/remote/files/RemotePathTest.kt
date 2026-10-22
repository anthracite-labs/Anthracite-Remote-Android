package dev.anthracite.remote.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePathTest {
    @Test
    fun `only absolute, printable, bounded paths are valid`() {
        assertTrue(RemotePath.isValid("/"))
        assertTrue(RemotePath.isValid("/srv/app/main.kt"))
        assertFalse(RemotePath.isValid(""))
        assertFalse(RemotePath.isValid("relative/path"))
        assertFalse(RemotePath.isValid("/srv/\u0000"))
        assertFalse(RemotePath.isValid("/srv/\n"))
        assertFalse(RemotePath.isValid("/" + "a".repeat(RemotePath.MAX_LENGTH)))
    }

    @Test
    fun `resolve clamps traversal instead of escaping the root`() {
        assertEquals("/etc", RemotePath.resolve("/", "../../etc"))
        assertEquals("/srv/app", RemotePath.resolve("/srv/app", "."))
        assertEquals("/srv/app/sub", RemotePath.resolve("/srv/app", "sub"))
        assertEquals("/srv", RemotePath.resolve("/srv/app", ".."))
        assertEquals("/other", RemotePath.resolve("/srv/app", "/other"))
        assertEquals("/a b/c", RemotePath.resolve("/", "a b/c"))
        assertNull(RemotePath.resolve("/", ""))
        assertNull(RemotePath.resolve("/", "a".repeat(256)))
        assertNull(RemotePath.resolve("/", "bad\u0007name"))
    }

    @Test
    fun `join refuses names that would change the directory`() {
        assertEquals("/srv/app/file.txt", RemotePath.join("/srv/app", "file.txt"))
        assertEquals("/srv/app/file.txt", RemotePath.join("/srv/app/", "file.txt"))
        assertNull(RemotePath.join("/srv/app", ".."))
        assertNull(RemotePath.join("/srv/app", "."))
        assertNull(RemotePath.join("/srv/app", "nested/file"))
        assertNull(RemotePath.join("/srv/app", ""))
        assertNull(RemotePath.join("/srv/app", "bad\nname"))
    }

    @Test
    fun `parent and name handle roots and trailing slashes`() {
        assertEquals("/srv", RemotePath.parent("/srv/app"))
        assertEquals("/", RemotePath.parent("/srv"))
        assertNull(RemotePath.parent("/"))
        assertNull(RemotePath.parent("relative"))
        assertEquals("app", RemotePath.name("/srv/app"))
        assertEquals("app", RemotePath.name("/srv/app/"))
    }

    @Test
    fun `isWithin compares normalised paths`() {
        assertTrue(RemotePath.isWithin("/srv/app", "/srv/app"))
        assertTrue(RemotePath.isWithin("/srv/app", "/srv/app/sub/file"))
        assertTrue(RemotePath.isWithin("/", "/anything"))
        assertFalse(RemotePath.isWithin("/srv/app", "/srv/application"))
        assertFalse(RemotePath.isWithin("/srv/app", "/srv"))
        assertFalse(RemotePath.isWithin("/srv/app", "relative"))
    }

    @Test
    fun `permission text is the ls form users already read`() {
        assertEquals("drwxr-xr-x", formatPermissions(0x4000 or 0x1ed))
        assertEquals("-rw-r--r--", formatPermissions(0x1a4))
        assertEquals("----------", formatPermissions(0))
        assertEquals("lrwxrwxrwx", formatPermissions(0xa000 or 0x1ff))
    }
}
