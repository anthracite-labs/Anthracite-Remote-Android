package dev.anthracite.remote

import java.net.URI

// Adapted from anthracite-labs/OpenCode-Android (MIT). Local-runtime code was not copied.

internal enum class PickerMode { OPEN, OPEN_MULTIPLE, SAVE, OPEN_FOLDER }

internal fun pickerAction(mode: PickerMode): String = when (mode) {
    PickerMode.OPEN, PickerMode.OPEN_MULTIPLE -> "android.intent.action.OPEN_DOCUMENT"
    PickerMode.SAVE -> "android.intent.action.CREATE_DOCUMENT"
    PickerMode.OPEN_FOLDER -> "android.intent.action.OPEN_DOCUMENT_TREE"
}

/** ACTION_CREATE_DOCUMENT requires one concrete MIME type, even without a file type hint. */
internal fun saveDocumentMimeType(acceptTypes: List<String>): String =
    acceptTypes.asSequence()
        .map { it.trim() }
        .firstOrNull { type ->
            val slash = type.indexOf('/')
            slash > 0 && slash < type.lastIndex && type.lastIndexOf('/') == slash &&
                '*' !in type && type.none { it.isWhitespace() }
        } ?: "application/octet-stream"

/** Policy for the picker trust boundary; access is still verified by Android before delivery. */
internal fun pickerUriAllowed(
    value: String,
    appPackage: String,
    mode: PickerMode,
    hasReadGrant: Boolean,
    hasWriteGrant: Boolean,
): Boolean {
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    if (uri.scheme != "content" || uri.rawUserInfo != null || uri.port != -1) return false
    val authority = uri.rawAuthority ?: return false
    if (authority.isBlank() || authority.any { it in "@:/?#" }) return false
    if (authority == appPackage || authority.startsWith("$appPackage.")) return false
    return when (mode) {
        PickerMode.OPEN, PickerMode.OPEN_MULTIPLE -> hasReadGrant
        PickerMode.SAVE -> hasWriteGrant
        PickerMode.OPEN_FOLDER -> hasReadGrant && uri.rawPath?.startsWith("/tree/") == true
    }
}
