package dev.anthracite.remote.terminal

import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.anthracite.remote.R
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalLifecycleTest {
    private class FakeChannel : TerminalChannel {
        private val incoming = PipedInputStream(8192)
        private val sender = PipedOutputStream(incoming)
        override val input: InputStream = incoming
        override val output: OutputStream = ByteArrayOutputStream()
        @Volatile private var open = true
        override val isOpen: Boolean get() = open
        override val exitStatus: Int get() = -1
        override val exitSignal: String? get() = null
        override fun resize(cols: Int, rows: Int, widthPx: Int, heightPx: Int) = Unit
        fun send(text: String) { sender.write(text.toByteArray()); sender.flush() }
        override fun close() { open = false; sender.close(); incoming.close() }
    }

    @Before fun reset() { TerminalTabs.clear() }
    @After fun cleanup() { TerminalTabs.clear() }

    private fun tab(channel: FakeChannel, kind: TerminalSession.Kind, name: String, id: String? = null): TerminalSession =
        TerminalSession(kind, name, TerminalEmulator(80, 24), channel,
            tmuxSession = if (kind == TerminalSession.Kind.TMUX) name else null, tmuxId = id)

    private fun await(condition: () -> Boolean) {
        repeat(100) {
            if (condition()) return
            Thread.sleep(20)
        }
        assertTrue("background PTY reader did not finish", condition())
    }

    @Test fun retainedTabsRebindAfterNavigationAndRecreation() {
        val shellChannel = FakeChannel()
        val tmuxChannel = FakeChannel()
        val shell = tab(shellChannel, TerminalSession.Kind.SHELL, "Shell")
        val tmux = tab(tmuxChannel, TerminalSession.Kind.TMUX, "work", "\$1")
        TerminalTabs.add(shell, "Shell")
        TerminalTabs.add(tmux, "work")
        var oldListener: TerminalSession.Listener? = null
        ActivityScenario.launch(TerminalActivity::class.java).use { first ->
            first.onActivity { activity ->
                assertNotNull(shell.listener)
                assertNotNull(tmux.listener)
                assertEquals(3, activity.findViewById<LinearLayout>(R.id.tab_row).childCount)
                oldListener = tmux.listener
                activity.findViewById<LinearLayout>(R.id.tab_row).getChildAt(1).performClick()
            }
            shellChannel.send("before")
            await { shell.emulator.screenText().contains("before") }
            first.moveToState(Lifecycle.State.CREATED)
            assertNull(shell.listener)
            assertNull(tmux.listener)
            shellChannel.send("while stopped")
            await { shell.emulator.screenText().contains("while stopped") }
            first.moveToState(Lifecycle.State.RESUMED)
            first.onActivity { assertNotNull(shell.listener); assertNotNull(tmux.listener) }
            first.recreate()
            first.onActivity { activity ->
                assertNotSame(oldListener, tmux.listener)
                assertNotNull(shell.listener)
                assertNotNull(tmux.listener)
                assertTrue(activity.findViewById<TextView>(R.id.terminal_status).text.contains("work"))
            }
        }
        assertNull(shell.listener)
        assertNull(tmux.listener)
        oldListener?.onOutput() // queued callback must not reach a destroyed screen
        oldListener = null
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertTrue("UI recreation must not close tmux", tmuxChannel.isOpen)
        tmuxChannel.send("during absence")
        await { tmux.emulator.screenText().contains("during absence") }
        ActivityScenario.launch(TerminalActivity::class.java).use { second ->
            second.onActivity { activity ->
                assertNotNull(shell.listener)
                assertNotNull(tmux.listener)
                assertTrue(tmux.emulator.screenText().contains("during absence"))
                assertEquals(3, activity.findViewById<LinearLayout>(R.id.tab_row).childCount)
                activity.findViewById<LinearLayout>(R.id.tab_row).getChildAt(1).performClick()
                assertTrue(activity.findViewById<TextView>(R.id.terminal_status).text.contains("work"))
            }
        }
        assertNull(shell.listener)
        assertNull(tmux.listener)
        assertTrue(tmuxChannel.isOpen)
        assertEquals(2, TerminalTabs.size())
    }

    @Test fun exitWhileNoActivityIsReportedOnReturnAndRepeatedVisitsDoNotAccumulateObservers() {
        val channel = FakeChannel()
        val session = tab(channel, TerminalSession.Kind.SHELL, "Shell")
        TerminalTabs.add(session, "Shell")
        repeat(3) {
            ActivityScenario.launch(TerminalActivity::class.java).use { scenario ->
                scenario.onActivity { assertSame(session, TerminalTabs.get(0)?.session) }
                assertNotNull(session.listener)
            }
            assertNull(session.listener)
        }
        ActivityScenario.launch(TerminalActivity::class.java).use { paused ->
            paused.moveToState(Lifecycle.State.CREATED)
            assertNull(session.listener)
            channel.close()
            await { session.lastCloseState != null }
            paused.moveToState(Lifecycle.State.RESUMED)
            paused.onActivity { activity ->
                assertTrue(activity.findViewById<TextView>(R.id.terminal_status).text
                    .contains(session.lastCloseState!!.reason))
            }
        }
        ActivityScenario.launch(TerminalActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<TextView>(R.id.terminal_status).text
                    .contains(session.lastCloseState!!.reason))
            }
        }
        assertNull(session.listener)
        assertFalse(channel.isOpen)
        assertEquals(1, TerminalTabs.size()) // a closed tab is not another tab's removal
    }
}
