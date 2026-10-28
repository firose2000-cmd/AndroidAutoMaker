package com.automaker.app.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class SecurityManifestTest {
    private val project = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).canonicalFile) { it.parentFile }
        .first { File(it, "tools/apktool.jar").isFile }
    private val android = "http://schemas.android.com/apk/res/android"

    @Test fun makerRequestsOnlyConversionAndInstallationPermissions() {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val manifest = factory.newDocumentBuilder().parse(File(project, "android/app/src/main/AndroidManifest.xml"))
        val nodes = manifest.getElementsByTagName("uses-permission")
        val permissions = (0 until nodes.length).map {
            (nodes.item(it) as org.w3c.dom.Element).getAttributeNS(android, "name")
        }.toSet()
        assertEquals(setOf("android.permission.POST_NOTIFICATIONS", "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_DATA_SYNC", "android.permission.REQUEST_INSTALL_PACKAGES"), permissions)
        val application = manifest.getElementsByTagName("application").item(0) as org.w3c.dom.Element
        assertEquals("false", application.getAttributeNS(android, "allowBackup"))
        assertEquals("false", application.getAttributeNS(android, "usesCleartextTraffic"))
        val paths = factory.newDocumentBuilder().parse(File(project, "android/app/src/main/res/xml/file_paths.xml"))
        assertEquals(0, paths.getElementsByTagName("files-path").length)
        val cache = paths.getElementsByTagName("cache-path").item(0) as org.w3c.dom.Element
        assertEquals("install-share/", cache.getAttribute("path"))
    }

    @Test fun packagedBridgeMatchesHardenedRuntimeAndDoesNotGrantSpecialAccess() {
        val asset = File(project, "android/app/src/main/assets/navigation.dex").readBytes()
        assertArrayEquals(asset, File(project, "tools/navigation.dex").readBytes())
        val strings = asset.toString(Charsets.ISO_8859_1)
        assertTrue(strings.contains("Active navigation session and Shizuku consent required"))
        assertTrue(strings.contains("fdb00c43dbde8b51cb312aa81d3b5fa17713adb94b28f598d77f8eb89daceedf"))
        assertFalse(strings.contains("ALLOW_ALL_HOSTS_VALIDATOR"))
        assertFalse(strings.contains("settings put secure"))
        assertFalse(strings.contains("appops set"))
        assertFalse(strings.contains("setHiddenApiExemptions"))
    }
}
