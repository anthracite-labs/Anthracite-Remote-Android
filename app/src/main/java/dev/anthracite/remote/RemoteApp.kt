package dev.anthracite.remote

import android.app.Application
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class RemoteApp : Application() {
    override fun onCreate() {
        super.onCreate()
        installCryptoProvider()
    }
}

internal fun installCryptoProvider() {
    Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
    Security.insertProviderAt(BouncyCastleProvider(), 1)
}
