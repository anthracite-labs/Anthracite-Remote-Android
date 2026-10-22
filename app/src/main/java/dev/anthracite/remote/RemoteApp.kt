package dev.anthracite.remote

import android.app.Application

/**
 * Does not install or reorder JCE providers. Android Keystore AES-GCM keys are
 * not exportable, and a Bouncy Castle provider at priority 1 makes
 * Cipher.getInstance pick an implementation that cannot use them. JSch loads
 * org.bouncycastle.crypto classes directly for the algorithms Android's Java 8
 * view cannot provide.
 */
class RemoteApp : Application()
