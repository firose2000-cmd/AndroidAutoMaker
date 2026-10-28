package com.automaker.app.engine

import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Files
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveLimitsTest {
    @Test fun rejectsExcessiveExpandedSizeBeforeWritingFiles() {
        val root = Files.createTempDirectory("archive-limit").toFile()
        val zip = File(root, "input.xapk")
        ZipOutputStream(zip.outputStream()).use {
            it.putNextEntry(ZipEntry("large"))
            it.write(ByteArray(1000))
            it.closeEntry()
        }
        assertThrows(IllegalArgumentException::class.java) {
            XapkPackages.extractZip(zip, File(root, "out"), limits = XapkPackages.ExtractionLimits(maxBytes = 100))
        }
    }

    @Test fun rejectsExcessiveEntryCounts() {
        val root = Files.createTempDirectory("archive-entries").toFile()
        val zip = File(root, "input.xapk")
        ZipOutputStream(zip.outputStream()).use {
            repeat(3) { index -> it.putNextEntry(ZipEntry("entry$index")); it.closeEntry() }
        }
        assertThrows(IllegalArgumentException::class.java) {
            XapkPackages.extractZip(zip, File(root, "out"), limits = XapkPackages.ExtractionLimits(maxEntries = 2))
        }
    }
}
