package dev.anthracite.remote.workspace

import android.app.Activity
import android.content.Context
import android.widget.Toast
import dev.anthracite.remote.R
import dev.anthracite.remote.host.HostProfile

/**
 * Resolves the host a screen was opened for, using [HostPicker]'s rule, and stops
 * the screen when an explicit id does not resolve.
 *
 * Shared so every screen behaves the same way: a stale or deleted id never
 * quietly becomes "the host that happened to be active".
 */
internal fun resolveHostOrFinish(
    activity: Activity,
    requestedId: String?,
    available: List<HostProfile>,
    active: HostProfile?,
): HostProfile? {
    when (val selection = HostPicker.select(requestedId, available, active)) {
        is HostSelection.Selected -> return selection.profile
        is HostSelection.Missing -> {
            showToast(activity, activity.getString(R.string.host_no_longer_available))
            activity.finish()
            return null
        }
        HostSelection.None -> {
            showToast(activity, activity.getString(R.string.workspace_no_host))
            activity.finish()
            return null
        }
    }
}

private fun showToast(activity: Activity, text: String) {
    Toast.makeText(activity as Context, text, Toast.LENGTH_LONG).show()
}
