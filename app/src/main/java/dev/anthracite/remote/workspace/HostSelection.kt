package dev.anthracite.remote.workspace

import dev.anthracite.remote.host.HostProfile

/**
 * Which host a screen is allowed to act on.
 *
 * The screens are opened with an optional host id. Treating "the id did not
 * resolve" the same as "no id was given" is how a stale intent — a notification
 * or a restored activity pointing at a host that has since been deleted — could
 * silently fall back to whatever host happens to be connected. On a destructive
 * screen that is the wrong host.
 *
 * So the two cases are separated: an explicit id must resolve exactly, and only a
 * screen opened without one may use the active profile.
 */
internal sealed class HostSelection {
    data class Selected(val profile: HostProfile) : HostSelection()

    /** The caller named a host that no longer exists (or was never readable). */
    data class Missing(val requestedId: String) : HostSelection()

    /** No id was supplied and no profile is active. */
    object None : HostSelection()

    val profileOrNull: HostProfile?
        get() = (this as? Selected)?.profile
}

internal object HostPicker {
    fun select(
        requestedId: String?,
        available: List<HostProfile>,
        active: HostProfile?,
    ): HostSelection {
        if (requestedId != null) {
            val found = available.firstOrNull { it.id == requestedId }
            return if (found != null) HostSelection.Selected(found) else HostSelection.Missing(requestedId)
        }
        return active?.let { HostSelection.Selected(it) } ?: HostSelection.None
    }
}
