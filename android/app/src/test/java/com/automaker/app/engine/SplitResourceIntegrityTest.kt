package com.automaker.app.engine

import com.android.apksig.ApkVerifier
import com.reandroid.apk.ApkModule
import com.reandroid.arsc.value.ValueItem
import com.reandroid.arsc.value.ValueType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Static compatibility checks; these do not install an APK or prove on-device startup. */
class SplitResourceIntegrityTest {
    private val sdk = File(System.getenv("ANDROID_SDK_ROOT") ?: System.getenv("ANDROID_HOME")
        ?: File(System.getenv("LOCALAPPDATA"), "Android/Sdk").path)
    private val suffix = if (System.getProperty("os.name").orEmpty().startsWith("Windows")) ".exe" else ""
    private val tools = File(sdk, "build-tools").listFiles().orEmpty().sortedByDescending { it.name }
        .first { File(it, "aapt2$suffix").isFile }
    private val aapt = File(tools, "aapt2$suffix")
    private val project = File("..").canonicalFile.parentFile

    @Test(timeout = 240000)
    fun preservesThemeReferencesAndConfigurationsAcrossFourSplits() = checkConversion(false)

    @Test(timeout = 240000)
    fun preservesOptimizedResourceIdsAcrossFourSplits() = checkConversion(true)

    @Test(timeout = 240000)
    fun suppliedYouTubeTablesRemainSearchableAfterConversion() {
        val location = System.getenv("YOUTUBE_RESOURCE_TABLES")
        assumeTrue("Set YOUTUBE_RESOURCE_TABLES to the five extracted diagnostic files", location != null)
        val source = File(location!!)
        val root = Files.createTempDirectory("youtube-resource-tables").toFile()
        try {
            // Diagnostic shells contain the exact tables and supplied converted manifest,
            // not the executable app or its resource files. Never install these shells.
            val tables = listOf("Original_Base_resources.arsc", "Original_English_resources.arsc", "Original_XXHDPI_resources.arsc")
            val inputs = tables.mapIndexed { index, name ->
                File(root, if (index == 0) "base.apk" else "config.$index.apk").also { apk ->
                    ZipOutputStream(apk.outputStream()).use { zip ->
                        zip.putNextEntry(ZipEntry("resources.arsc")); zip.write(File(source, name).readBytes()); zip.closeEntry()
                        zip.putNextEntry(ZipEntry("AndroidManifest.xml")); zip.write(File(source, "Converted_AndroidManifest.xml").readBytes()); zip.closeEntry()
                    }
                }
            }
            val expected = inputs.flatMap { rawResources(it).entries }.associate { it.toPair() }
            assertTrue(expected.keys.any { it.startsWith("7f060dfd/") })
            assertTrue(inputs.sumOf { assertSparseIndexes(it) } > 0)
            val broken = File(root, "known-converted.apk")
            ZipOutputStream(broken.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("resources.arsc"))
                zip.write(File(source, "Converted_resources.arsc").readBytes())
                zip.closeEntry()
            }
            val failure = assertThrows(AssertionError::class.java) { assertSparseIndexes(broken) }
            assertTrue(failure.message.orEmpty().contains("strictly ascending sparse IDs"))
            val bundle = File(root, "tables.xapk")
            ZipOutputStream(bundle.outputStream()).use { zip ->
                for (input in inputs) { zip.putNextEntry(ZipEntry(input.name)); zip.write(input.readBytes()); zip.closeEntry() }
            }
            val output = File(root, "out/signed.apk")
            output.parentFile!!.mkdirs()
            val converter = PackageConverter(PackageConverter.signerFrom(File("src/test/resources/signing.p12")))
            converter.convertXapk(bundle, output, {}, {}, packageSuffix = PackageNames.SUFFIX)
            assertSparseIndexes(output)
            assertEquals("All supplied ID/configuration/type/value tuples", expected, rawResources(output))
            assertTrue(ApkVerifier.Builder(output).build().verify().isVerified)
            println("YouTube Android: ${expected.size} preserved records; ${assertSparseIndexes(output)} valid sparse chunks; ${tableSize(output)} resource bytes")
            val classes = File(root, "desktop-classes").also { it.mkdirs() }
            val javaHome = File(requireNotNull(System.getProperty("java.home")))
            val library = File(project, "tools/lib/ARSCLib-1.3.8.jar")
            run(File(javaHome, "bin/javac$suffix"), "-cp", library.path, "-d", classes.path,
                File(project, "tools/src/com/automaker/merge/MergeXapk.java").path)
            val desktop = File(root, "desktop/merged.apk")
            run(File(javaHome, "bin/java$suffix"), "-cp", classes.path + File.pathSeparator + library.path,
                "com.automaker.merge.MergeXapk", bundle.path, desktop.path)
            assertSparseIndexes(desktop)
            assertEquals("Desktop merge preserves supplied resource tuples", expected, rawResources(desktop))
            println("YouTube desktop: ${expected.size} preserved records; ${assertSparseIndexes(desktop)} valid sparse chunks; ${tableSize(desktop)} resource bytes")
        } finally {
            root.deleteRecursively()
        }
    }

    private fun checkConversion(optimized: Boolean) {
        val root = Files.createTempDirectory("split-resources").toFile()
        try {
            val inputs = fixture(root, optimized)
            val sparseChunks = inputs.sumOf { assertSparseIndexes(it) }
            if (optimized) assertTrue("Fixture must exercise sparse indexes", sparseChunks > 0)
            val expected = sortedMapOf<String, String>()
            for (input in inputs) {
                for ((key, value) in resources(input)) {
                    val previous = expected.put(key, value)
                    assertTrue("Conflicting fixture resource $key", previous == null || previous == value)
                }
            }
            assertTrue(expected.keys.any { it.endsWith("/en") })
            assertTrue(expected.keys.any { it.endsWith("/xxhdpi") || it.endsWith("/xxhdpi-v4") })
            assertTrue(expected.values.any { it.contains("parent=0x01030241") })
            val payloads = inputs.flatMap { payloadHashes(it).entries }.associate { it.toPair() }
            val rawValues = inputs.flatMap { rawResources(it).entries }.associate { it.toPair() }
            val bundle = File(root, "installed.xapk")
            ZipOutputStream(bundle.outputStream()).use { zip ->
                for (input in inputs) {
                    zip.putNextEntry(ZipEntry(input.name))
                    input.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            val converter = PackageConverter(PackageConverter.signerFrom(File("src/test/resources/signing.p12")))
            val keyboard = File(project, "tools/keyboard.dex").readBytes()
            for (rename in listOf(false, true)) {
                val output = File(root, "output-$rename/signed.apk")
                output.parentFile!!.mkdirs()
                val result = converter.convertXapk(bundle, output, {}, {}, keyboardDex = keyboard,
                    packageSuffix = if (rename) PackageNames.SUFFIX else "")
                assertSparseIndexes(output)
                assertEquals("resource ID/configuration/value tuples, optimized=$optimized rename=$rename",
                    expected, resources(output))
                assertEquals("Raw types, reference IDs, style parents and attribute IDs", rawValues, rawResources(output))
                for ((name, hash) in payloads) assertEquals("Original payload $name", hash, payloadHashes(output)[name])
                assertTrue(ApkVerifier.Builder(output).build().verify().isVerified)
                val badging = run(aapt, "dump", "badging", output.path)
                assertTrue(badging.contains("native-code: 'arm64-v8a'"))
                assertTrue(badging.contains("minSdkVersion:'26'"))
                assertTrue(badging.contains("targetSdkVersion:'35'"))
                ApkModule.loadApkFile(output).use { apk ->
                    val manifest = apk.androidManifest
                    assertEquals(if (rename) "$PACKAGE.patched" else PACKAGE, manifest.packageName)
                    assertEquals(result.packageName, manifest.packageName)
                    assertFalse(manifest.isSplit)
                    assertTrue(manifest.manifestElement.searchAttributeByName("sharedUserId") == null)
                    val providers = manifest.applicationElement.getElements("provider").asSequence().toList()
                    val keyboardProvider = providers.first { it.searchAttributeByName("name")?.valueAsString == "com.automaker.game.KeyboardStay" }
                    assertEquals(0, keyboardProvider.searchAttributeByName("exported").data)
                }
                val xml = run(aapt, "dump", "xmltree", output.path, "--file", "AndroidManifest.xml")
                assertTrue(xml.contains("android.intent.category.CAR_LAUNCHER"))
                assertFalse(xml.contains("distractionOptimized"))
                assertFalse(xml.contains("uses-split"))
                assertFalse(xml.contains("isSplitRequired"))
                assertTrue(xml.contains(if (rename) "$PACKAGE.patched.files" else "$PACKAGE.files"))
                assertEquals("No additional permissions", permissions(inputs.first()), permissions(output))
                println("Resource table bytes: optimized=$optimized rename=$rename inputTotal=${inputs.sumOf { tableSize(it) }} output=${tableSize(output)}")
                val originalLayout = run(aapt, "dump", "xmltree", inputs.first().path, "--file", "res/layout/main.xml")
                assertEquals("Compiled layout attribute IDs", originalLayout,
                    run(aapt, "dump", "xmltree", output.path, "--file", "res/layout/main.xml"))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    // aapt2 is independent of the resource library used by the converter. Compare semantics,
    // not table byte length, entry offsets, or string-pool ordering.
    private fun resources(apk: File): Map<String, String> {
        val result = sortedMapOf<String, String>()
        var id = ""
        var name = ""
        var key: String? = null
        for (line in run(aapt, "dump", "resources", apk.path).lineSequence()) {
            val text = line.trim()
            val resource = Regex("resource (0x[0-9a-f]+) (.+)").matchEntire(text)
            val configuration = Regex("\\(([^)]*)\\) (.*)").matchEntire(text)
            when {
                resource != null -> { id = resource.groupValues[1]; name = resource.groupValues[2]; key = null }
                text.startsWith("type ") || text.startsWith("Package ") -> key = null
                configuration != null -> {
                    key = "$id/${configuration.groupValues[1]}"
                    check(!result.containsKey(key)) { "Duplicate resource configuration $key" }
                    result[key!!] = "$name ${configuration.groupValues[2]}"
                }
                key != null && text.isNotEmpty() -> result[key!!] = result.getValue(key!!) + "\n" + text
            }
        }
        return result
    }

    private fun permissions(apk: File): Set<String> = run(aapt, "dump", "permissions", apk.path)
        .lineSequence().map { it.trim() }.filter { it.startsWith("uses-permission:") || it.startsWith("permission:") }.toSet()

    // Also compare numeric reference targets: aapt2 displays symbolic references, which
    // alone cannot distinguish different targets when resource names are collapsed.
    private fun rawResources(apk: File): Map<String, List<String>> = ApkModule.loadApkFile(apk).use { module ->
        val result = sortedMapOf<String, List<String>>()
        fun value(item: ValueItem): String = "${item.valueType}:" +
            if (item.valueType == ValueType.STRING) item.valueAsString else item.data.toString(16)
        val entries = module.tableBlock?.resources?.asSequence().orEmpty()
        for (resource in entries) for (entry in resource) {
            if (entry.isNull) continue
            result["${entry.resourceId.toString(16)}/${entry.resConfig.qualifiers}"] = if (entry.isComplex) {
                val bag = entry.resTableMapEntry
                listOf("parent:${bag.parentId.toString(16)}") + bag.map { "${it.nameId.toString(16)}=${value(it)}" }.sorted()
            } else listOf(value(entry.resValue))
        }
        result
    }

    private fun payloadHashes(apk: File): Map<String, String> = ZipFile(apk).use { zip ->
        zip.entries().asSequence().filter { it.name.matches(Regex("classes\\d*\\.dex")) || it.name.startsWith("lib/") }
            .associate { entry -> entry.name to zip.getInputStream(entry).use { stream ->
                MessageDigest.getInstance("SHA-256").digest(stream.readBytes()).joinToString("") { "%02x".format(it) }
            } }
    }

    private fun tableSize(apk: File): Long = ZipFile(apk).use { it.getEntry("resources.arsc")?.size ?: 0L }

    private fun assertSparseIndexes(apk: File): Int = ZipFile(apk).use { zip ->
        val entry = zip.getEntry("resources.arsc") ?: return@use 0
        val bytes = zip.getInputStream(entry).use { it.readBytes() }
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var sparseCount = 0
        fun u16(offset: Int) = data.getShort(offset).toInt() and 0xffff
        fun scan(start: Int, end: Int) {
            var offset = start
            while (offset < end) {
                assertTrue("Truncated chunk", offset + 8 <= end)
                val type = u16(offset)
                val header = u16(offset + 2)
                val size = data.getInt(offset + 4)
                assertTrue("Invalid chunk bounds", header >= 8 && size >= header && size <= end - offset)
                if (type == 0x0002 || type == 0x0200) scan(offset + header, offset + size)
                if (type == 0x0201 && bytes[offset + 9].toInt() and 1 != 0) {
                    sparseCount++
                    val count = data.getInt(offset + 12)
                    val entriesStart = data.getInt(offset + 16)
                    assertTrue(count >= 0 && count.toLong() * 4 <= entriesStart - header)
                    var previous = -1
                    for (index in 0 until count) {
                        val id = u16(offset + header + index * 4)
                        assertTrue("Android binary search requires strictly ascending sparse IDs: ${apk.name} type=${bytes[offset + 8]} index=$index previous=$previous id=$id",
                            id > previous)
                        previous = id
                        val relative = u16(offset + header + index * 4 + 2) * 4
                        assertTrue("Sparse entry points outside chunk", entriesStart + relative + 8 <= size)
                    }
                }
                offset += size
            }
        }
        scan(0, bytes.size)
        sparseCount
    }

    private fun fixture(root: File, optimized: Boolean): List<File> {
        fun write(path: String, text: String): File = File(root, path).also { it.parentFile!!.mkdirs(); it.writeText(text) }
        val manifest = write("AndroidManifest.xml", """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$PACKAGE" android:versionCode="1">
              <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35"/>
              <uses-permission android:name="android.permission.INTERNET"/>
              <application android:label="@string/title" android:theme="@style/AppTheme" android:hasCode="true">
                <activity android:name="android.app.Activity" android:exported="true">
                  <intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter>
                </activity>
                <provider android:name="android.content.ContentProvider" android:authorities="$PACKAGE.files" android:exported="false"/>
              </application>
            </manifest>
        """.trimIndent())
        write("res/values/values.xml", """
            <resources>
              <attr name="brandColor" format="color"/>
              <color name="accent">#123456</color><color name="alias">@color/accent</color>
              <color name="background">#ffffff</color><string name="title">Default</string>
              <style name="AppTheme" parent="android:style/Theme.Material.Light.NoActionBar">
                <item name="android:colorAccent">@color/alias</item>
                <item name="android:windowBackground">@color/background</item>
                <item name="brandColor">@color/accent</item>
              </style>
            </resources>
        """.trimIndent())
        write("res/values-en/values.xml", "<resources><string name=\"title\">English</string></resources>")
        write("res/values/padding.xml", "<resources>" + (0 until 64).joinToString("") {
            "<color name=\"padding_$it\">#123456</color>"
        } + "</resources>")
        write("res/values-night/values.xml", "<resources><color name=\"accent\">#f0f0f0</color><color name=\"background\">#000000</color></resources>")
        for (directory in listOf("drawable", "drawable-xxhdpi")) {
            write("res/$directory/image.xml", """<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle"><solid android:color="@color/alias"/></shape>""")
        }
        write("res/layout/main.xml", """<View xmlns:android="http://schemas.android.com/apk/res/android" android:layout_width="match_parent" android:layout_height="match_parent" android:background="?attr/brandColor"/>""")
        val compiled = File(root, "compiled.zip")
        run(aapt, "compile", "--dir", File(root, "res").path, "-o", compiled.path)
        val base = File(root, "base.apk")
        val english = File(root, "split_config.en.apk")
        val density = File(root, "split_config.xxhdpi.apk")
        val separator = if (suffix.isEmpty()) ":" else ";"
        run(aapt, "link", "-o", base.path, "-I", File(sdk, "platforms/android-36/android.jar").path,
            "--manifest", manifest.path, "--split", "${english.path}${separator}en",
            "--split", "${density.path}${separator}xxhdpi", compiled.path)
        val abiManifest = write("abi.xml", """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$PACKAGE" split="config.arm64_v8a" android:versionCode="1"><uses-sdk android:minSdkVersion="26"/><application android:hasCode="false"/></manifest>""")
        val abi = File(root, "split_config.arm64_v8a.apk")
        run(aapt, "link", "-o", abi.path, "-I", File(sdk, "platforms/android-36/android.jar").path, "--manifest", abiManifest.path)
        val inputs = listOf(base, abi, english, density)
        if (optimized) for (input in listOf(base, english, density)) {
            val optimizedFile = File(root, "optimized.apk")
            run(aapt, "optimize", "--collapse-resource-names", "--force-sparse-encoding", "--deduplicate-entry-values",
                "-o", optimizedFile.path, input.path)
            optimizedFile.copyTo(input, overwrite = true)
            optimizedFile.delete()
        }
        // Opaque fixture payloads exercise byte preservation; the synthetic app is not installed.
        addEntries(base, mapOf("classes.dex" to File(project, "tools/keyboard.dex").readBytes()))
        addEntries(abi, mapOf("lib/arm64-v8a/libfixture.so" to byteArrayOf(0x7f, 0x45, 0x4c, 0x46, 1, 2, 3)))
        return inputs
    }

    private fun addEntries(apk: File, entries: Map<String, ByteArray>) {
        val temp = File(apk.parentFile, "payload.apk")
        ZipFile(apk).use { zip -> ZipOutputStream(temp.outputStream()).use { out ->
            for (entry in zip.entries().asSequence()) {
                out.putNextEntry(ZipEntry(entry.name))
                zip.getInputStream(entry).use { it.copyTo(out) }
                out.closeEntry()
            }
            for ((name, bytes) in entries) { out.putNextEntry(ZipEntry(name)); out.write(bytes); out.closeEntry() }
        } }
        temp.copyTo(apk, overwrite = true)
        temp.delete()
    }

    private fun run(tool: File, vararg arguments: String): String {
        val process = ProcessBuilder(listOf(tool.path) + arguments).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "${tool.name} ${arguments.joinToString(" ")} failed:\n$text" }
        return text
    }

    companion object { private const val PACKAGE = "com.example.resources" }
}
