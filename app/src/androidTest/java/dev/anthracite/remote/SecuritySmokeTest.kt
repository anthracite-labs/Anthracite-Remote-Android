package dev.anthracite.remote

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore

@RunWith(AndroidJUnit4::class)
class SecuritySmokeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun secretsCannotBeBackedUpByAndroid() {
        val flags = context.applicationInfo.flags
        assertEquals(0, flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }

    @Test fun broadStoragePermissionsAreNotRequested() {
        val permissions = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()
        assertFalse("MANAGE_EXTERNAL_STORAGE is forbidden", "android.permission.MANAGE_EXTERNAL_STORAGE" in permissions)
        assertFalse("READ_EXTERNAL_STORAGE is forbidden", "android.permission.READ_EXTERNAL_STORAGE" in permissions)
        assertFalse("WRITE_EXTERNAL_STORAGE is forbidden", "android.permission.WRITE_EXTERNAL_STORAGE" in permissions)
    }

    @Test fun AndroidKeystoreIsAvailable() {
        val keystore = KeyStore.getInstance("AndroidKeyStore")
        keystore.load(null)
        assertNotNull(keystore)
    }
}
