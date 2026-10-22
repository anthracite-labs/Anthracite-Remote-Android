package dev.anthracite.remote

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.textfield.TextInputLayout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on the isolated CI emulator. No SSH server or Internet access is needed:
 * these checks exercise the real Activity views and saved-journey behavior.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingSmokeTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun resetBefore() = resetPreferences()
    @After fun resetAfter() = resetPreferences()

    private fun resetPreferences() {
        assertTrue(context.getSharedPreferences("connection", Context.MODE_PRIVATE).edit().clear().commit())
    }

    @Test fun firstLaunchWelcomeThenInvalidSetupShowsFieldErrors() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.screen_welcome).visibility)
                activity.findViewById<View>(R.id.welcome_cta).performClick()
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.screen_setup).visibility)
                activity.findViewById<View>(R.id.save_button).performClick()
                assertNotNull(activity.findViewById<TextInputLayout>(R.id.host_field).error)
                assertNotNull(activity.findViewById<TextInputLayout>(R.id.username_field).error)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.screen_setup).visibility)
            }
        }
    }

    @Test fun homeShowsPrepareBeforeInstallAndOpenForRememberedJourney() {
        val store = ConnectionStore(context)
        store.saveProfile("host.example", 22, "alice", AuthMethod.PASSWORD, 4096, "")
        store.setIntroSeen(true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.screen_home).visibility)
                assertEquals(
                    activity.getString(R.string.cta_prepare_install),
                    activity.findViewById<TextView>(R.id.home_cta).text.toString(),
                )
            }
        }
        store.saveLastMode(LaunchMode.EXISTING)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.screen_home).visibility)
                assertEquals(
                    activity.getString(R.string.cta_open),
                    activity.findViewById<TextView>(R.id.home_cta).text.toString(),
                )
            }
        }
    }

    @Test fun changedConnectionClearsJourneyButKeepsTrustForSameEndpoint() {
        val store = ConnectionStore(context)
        store.saveProfile("host.example", 22, "alice", AuthMethod.PASSWORD, 4096, "")
        val key = byteArrayOf(1, 2, 3, 4)
        store.saveHostKey(key, "ssh-ed25519", "SHA256:sample")
        store.saveLastMode(LaunchMode.EXISTING)

        // Saving an unchanged profile must preserve the last successful journey.
        store.saveProfile("host.example", 22, "alice", AuthMethod.PASSWORD, 4096, "")
        assertEquals(LaunchMode.EXISTING, store.lastMode())

        // Changing the OpenCode target invalidates the previous journey,
        // but trust in the unchanged SSH endpoint is unaffected.
        store.saveProfile("host.example", 22, "alice", AuthMethod.PASSWORD, 4097, "")
        assertNull(store.lastMode())
        assertArrayEquals(key, store.load().hostKey)

        store.saveLastMode(LaunchMode.MANAGED)
        store.saveProfile("other.example", 22, "alice", AuthMethod.PASSWORD, 4097, "")
        assertNull(store.lastMode())
        assertNull(store.load().hostKey)
    }
}
