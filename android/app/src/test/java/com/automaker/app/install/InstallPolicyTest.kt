package com.automaker.app.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class InstallPolicyTest {
    @Test fun requiresExplicitConsentLiveBinderAndShizukuGrant() {
        for (consent in listOf(false, true)) {
            for (binder in listOf(false, true)) {
                for (grant in listOf(false, true)) {
                    assertEquals(consent && binder && grant, InstallPolicy.shizukuAuthorized(consent, binder, grant))
                }
            }
        }
    }

    @Test fun allowsOnlySelectedPackageExpansionAndFiles() {
        assertEquals("/sdcard/Android/obb/com.example.maps/main.obb",
            InstallPolicy.sidecarDestination("com.example.maps", "Android/obb/com.example.maps/main.obb"))
        assertEquals("/sdcard/Android/data/com.example.maps/files/map.db",
            InstallPolicy.sidecarDestination("com.example.maps", "Android/data/com.example.maps/files/map.db"))
    }

    @Test fun rejectsCrossPackageWritesTraversalAndShellSyntax() {
        listOf("Android/obb/com.other.app/main.obb", "Android/data/com.example.maps/prefs.xml",
            "Android/obb/com.example.maps/../other/file", "/Android/obb/com.example.maps/file",
            "Android/obb/com.example.maps/file';id;echo '", "Android/obb/com.example.maps//file",
            "Android/obb/com.example.maps/./file", "Android/media/com.example.maps/file").forEach { path ->
            assertThrows(path, IllegalArgumentException::class.java) {
                InstallPolicy.sidecarDestination("com.example.maps", path)
            }
        }
    }
}
