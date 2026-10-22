package dev.anthracite.remote

/** The surfaces of the single-activity UI. View swapping is applied by the activity. */
internal enum class Screen { WELCOME, SETUP, VERIFY, HOME, PREPARE_INSTALL, PROGRESS, WEB, MANAGE }

internal sealed class BackAction {
    data class To(val screen: Screen) : BackAction()
    object WebHistory : BackAction()
    object ExitTask : BackAction()
}

/**
 * Pure screen-flow decisions: which surface a cold start lands on and where the
 * system back gesture goes from each surface. The activity applies these and
 * layers flow-specific handling on top (host-key refusal lands per journey,
 * Progress back asks for a stop confirmation first).
 */
internal object NavigationPolicy {
    fun firstScreen(hasConnection: Boolean, introSeen: Boolean): Screen = when {
        hasConnection -> Screen.HOME
        introSeen -> Screen.SETUP
        else -> Screen.WELCOME
    }

    fun backFrom(screen: Screen, webCanGoBack: Boolean, hasConnection: Boolean): BackAction = when (screen) {
        Screen.WEB -> if (webCanGoBack) BackAction.WebHistory else BackAction.To(Screen.HOME)
        Screen.SETUP -> if (hasConnection) BackAction.To(Screen.HOME) else BackAction.ExitTask
        Screen.HOME, Screen.WELCOME -> BackAction.ExitTask
        Screen.VERIFY, Screen.PREPARE_INSTALL, Screen.PROGRESS, Screen.MANAGE -> BackAction.To(Screen.HOME)
    }

    /**
     * Honors the system "Remove animations" setting: an animator duration scale
     * of 0 (or an unreadable value) disables every custom transition.
     */
    fun animationsEnabled(animatorDurationScale: Float): Boolean =
        !animatorDurationScale.isNaN() && animatorDurationScale > 0f
}
