package dev.anthracite.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationPolicyTest {
    @Test
    fun `cold start lands on welcome only for a fresh install`() {
        assertEquals(Screen.WELCOME, NavigationPolicy.firstScreen(hasConnection = false, introSeen = false))
        assertEquals(Screen.SETUP, NavigationPolicy.firstScreen(hasConnection = false, introSeen = true))
        assertEquals(Screen.HOME, NavigationPolicy.firstScreen(hasConnection = true, introSeen = false))
        assertEquals(Screen.HOME, NavigationPolicy.firstScreen(hasConnection = true, introSeen = true))
    }

    @Test
    fun `back inside the webui walks history before leaving`() {
        assertEquals(BackAction.WebHistory, NavigationPolicy.backFrom(Screen.WEB, webCanGoBack = true, hasConnection = true))
        assertEquals(BackAction.To(Screen.HOME), NavigationPolicy.backFrom(Screen.WEB, webCanGoBack = false, hasConnection = true))
    }

    @Test
    fun `back from setup returns home only when a connection exists`() {
        assertEquals(BackAction.To(Screen.HOME), NavigationPolicy.backFrom(Screen.SETUP, webCanGoBack = false, hasConnection = true))
        assertEquals(BackAction.ExitTask, NavigationPolicy.backFrom(Screen.SETUP, webCanGoBack = false, hasConnection = false))
    }

    @Test
    fun `back from hub screens exits the task`() {
        assertEquals(BackAction.ExitTask, NavigationPolicy.backFrom(Screen.HOME, webCanGoBack = false, hasConnection = true))
        assertEquals(BackAction.ExitTask, NavigationPolicy.backFrom(Screen.WELCOME, webCanGoBack = false, hasConnection = false))
    }

    @Test
    fun `back from transient and management screens returns home`() {
        for (screen in listOf(Screen.VERIFY, Screen.PREPARE_INSTALL, Screen.PROGRESS, Screen.MANAGE)) {
            assertEquals(
                "back from $screen",
                BackAction.To(Screen.HOME),
                NavigationPolicy.backFrom(screen, webCanGoBack = false, hasConnection = true),
            )
        }
    }

    @Test
    fun `animations honor the system remove-animations setting`() {
        assertFalse(NavigationPolicy.animationsEnabled(0f))
        assertFalse(NavigationPolicy.animationsEnabled(Float.NaN))
        assertFalse(NavigationPolicy.animationsEnabled(-1f))
        assertTrue(NavigationPolicy.animationsEnabled(1f))
        assertTrue(NavigationPolicy.animationsEnabled(0.5f))
    }
}
