package dev.anthracite.remote.terminal

/**
 * Terminal tabs survive activity recreation but not process death.
 *
 * That is deliberate and is stated in the UI: the host owns durable work, and a
 * `tmux` session is what the user reattaches after Android kills the app. Keeping
 * the tab list in a singleton avoids a foreground-service promise the platform
 * cannot keep on iOS, and avoids pretending a dropped socket equals a stopped
 * process.
 */
internal object TerminalTabs {
    data class Tab(val session: TerminalSession, val label: String)

    private val entries = mutableListOf<Tab>()

    fun all(): List<Tab> = entries.toList()

    fun size(): Int = entries.size

    fun add(session: TerminalSession, label: String): Int {
        entries += Tab(session, label)
        return entries.lastIndex
    }

    fun get(index: Int): Tab? = entries.getOrNull(index)

    fun remove(index: Int) {
        val tab = entries.getOrNull(index) ?: return
        entries.removeAt(index)
        tab.session.close("Tab closed")
    }

    fun replace(index: Int, tab: Tab) {
        if (index in entries.indices) entries[index] = tab
    }

    fun indexOf(session: TerminalSession): Int = entries.indexOfFirst { it.session === session }

    fun clear() {
        for (tab in entries) tab.session.close("Workspace disconnected")
        entries.clear()
    }
}
