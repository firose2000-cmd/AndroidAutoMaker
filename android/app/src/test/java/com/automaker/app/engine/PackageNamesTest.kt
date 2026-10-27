package com.automaker.app.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class PackageNamesTest {
    @Test
    fun addsPatchedOnce() {
        assertEquals("com.example.game.patched", PackageNames.withSuffix("com.example.game"))
        assertEquals("com.example.game.patched", PackageNames.withSuffix("com.example.game.patched"))
    }

    @Test
    fun rewritesAPackagePrefixWithoutTouchingOtherNames() {
        assertEquals("com.example.game.patched", PackageNames.replacePrefix("com.example.game", "com.example.game", "com.example.game.patched"))
        assertEquals(
            "com.example.game.patched.permission.C2D",
            PackageNames.replacePrefix("com.example.game.permission.C2D", "com.example.game", "com.example.game.patched"),
        )
        assertEquals("com.other.app", PackageNames.replacePrefix("com.other.app", "com.example.game", "com.example.game.patched"))
    }

    @Test
    fun rewritesEachAuthority() {
        assertEquals(
            "com.example.game.patched.provider; com.example.game.patched.files",
            PackageNames.replaceList(
                "com.example.game.provider; com.example.game.files",
                "com.example.game",
                "com.example.game.patched",
            ),
        )
    }
}
