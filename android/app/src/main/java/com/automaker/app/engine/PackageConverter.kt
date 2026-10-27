package com.automaker.app.engine

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import com.reandroid.apk.APKLogger
import com.reandroid.apk.ApkBundle
import com.reandroid.apk.ApkModule
import com.reandroid.app.AndroidManifest
import com.reandroid.archive.ZipEntryMap
import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.chunk.SpecBlock
import com.reandroid.arsc.chunk.TypeBlock
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.container.SpecTypePair
import com.reandroid.arsc.chunk.xml.ResXmlAttribute
import com.reandroid.arsc.chunk.xml.ResXmlElement
import java.io.IOException
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.Adler32
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean

private val APPLICATION_TAGS = setOf(
    "activity",
    "activity-alias",
    "service",
    "receiver",
    "provider",
    "uses-library",
    "meta-data",
    "property",
)
private val ROOT_TAGS = setOf(
    "uses-permission",
    "permission",
    "permission-tree",
    "permission-group",
    "uses-feature",
)
private val SPLIT_META = arrayOf(
    "com.android.vending.splits.required",
    "com.android.vending.splits",
    "com.android.stamp.source",
    "com.android.stamp.type",
    "com.android.vending.derived.apk.id",
)

data class ConvertedPackage(
    val changes: List<String>,
    val packageName: String,
    val size: Long,
    val format: String,
)

class PackageConverter(private val signerFor: (packageName: String) -> ApkSigner.SignerConfig) {
    constructor(signer: ApkSigner.SignerConfig) : this({ _ -> signer })

    fun convertApk(
        input: File,
        output: File,
        log: (String) -> Unit,
        onStep: (String) -> Unit,
        cancelled: () -> Boolean = { false },
        kind: String = "game",
        navigationDex: ByteArray? = null,
        lifecycleDex: ByteArray? = null,
        carDex: ByteArray? = null,
        supportDex: ByteArray? = null,
        archDex: ByteArray? = null,
        activityDex: ByteArray? = null,
        shizukuDex: ByteArray? = null,
        keyboardDex: ByteArray? = null,
        packageSuffix: String = "",
        gamepad: Boolean = false,
        onProgress: (percent: Int, label: String) -> Unit = { _, _ -> },
    ): ConvertedPackage {
        checkCancelled(cancelled)
        val directory = output.parentFile ?: throw IllegalStateException("The session folder is missing.")
        directory.mkdirs()
        val unsigned = File(directory, "unsigned.apk")
        val reading = span(onProgress, 0, 30, "Reading the package")
        onStep("decode")
        log("Reading the package.")
        reading(0.0)
        val apk = ApkModule.loadApkFile(input)
        reading(1.0)
        try {
            checkCancelled(cancelled)
            val manifest = apk.androidManifest
                ?: throw IllegalStateException("The decoded APK has no AndroidManifest.xml.")
            val updating = span(onProgress, 30, 40, "Updating the manifest")
            onStep("patch")
            log("Updating the manifest.")
            updating(0.0)
            val changes = ManifestPatcher.patch(manifest, apk, kind, packageSuffix, gamepad)
            changes.forEach(log)
            val libraryIds = if (kind == "navigation") carLibraryIds(apk) else emptyList()
            updating(1.0)
            checkCancelled(cancelled)
            val writing = span(onProgress, 40, 88, "Writing the APK")
            onStep("build")
            log("Writing the rebuilt APK.")
            writing(0.0)
            apk.apkSignatureBlock = null
            stripSignatures(apk)
            stripInternalFiles(apk)
            if (unsigned.exists()) unsigned.delete()
            val stopWriting = watchFile(unsigned, input.length().coerceAtLeast(1L), writing)
            try {
                apk.writeApk(unsigned)
            } finally {
                stopWriting()
            }
            writing(1.0)
            if (stripCorruptOverlayables(unsigned)) {
                log("Removed a resource overlay header the phone cannot read.")
            }
            if (kind == "navigation") {
                log("Adding the navigation screen.")
                addNavigationLibraries(
                    unsigned,
                    libraryIds,
                    navigationDex,
                    lifecycleDex,
                    carDex,
                    supportDex,
                    archDex,
                    activityDex,
                    shizukuDex,
                )
            } else if (keyboardDex != null) {
                addKeyboardDex(unsigned, keyboardDex)
            }
            val packageName = manifest.packageName.orEmpty()
            checkCancelled(cancelled)
            val signing = span(onProgress, 88, 100, "Signing")
            onStep("sign")
            log("Signing the APK.")
            signing(0.0)
            alignAndSign(unsigned, output, packageName, log)
            signing(1.0)
            unsigned.delete()
            return ConvertedPackage(changes, packageName, output.length(), "apk")
        } finally {
            apk.close()
        }
    }

    fun convertXapk(
        input: File,
        output: File,
        log: (String) -> Unit,
        onStep: (String) -> Unit,
        cancelled: () -> Boolean = { false },
        kind: String = "game",
        navigationDex: ByteArray? = null,
        lifecycleDex: ByteArray? = null,
        carDex: ByteArray? = null,
        supportDex: ByteArray? = null,
        archDex: ByteArray? = null,
        activityDex: ByteArray? = null,
        shizukuDex: ByteArray? = null,
        keyboardDex: ByteArray? = null,
        packageSuffix: String = "",
        gamepad: Boolean = false,
        onProgress: (percent: Int, label: String) -> Unit = { _, _ -> },
    ): ConvertedPackage {
        checkCancelled(cancelled)
        val directory = output.parentFile ?: throw IllegalStateException("The session folder is missing.")
        val extracted = File(directory, "xapk")
        val modules = File(directory, "modules")
        val merged = File(directory, "merged.apk")
        val extracting = span(onProgress, 0, 12, "Extracting the XAPK")
        onStep("decode")
        log("Extracting the XAPK.")
        extracting(0.0)
        extracted.deleteRecursively()
        modules.deleteRecursively()
        XapkPackages.extractZip(input, extracted, cancelled) { done, total ->
            extracting(if (total <= 0L) 0.0 else done.toDouble() / total.toDouble())
        }
        extracting(1.0)
        Sidecars.stash(extracted, File(directory, "sidecars"), packageSuffix)
        val apks = XapkPackages.listApks(extracted)
        if (apks.isEmpty()) throw IllegalArgumentException("The XAPK does not contain an APK.")
        modules.mkdirs()
        val collecting = span(onProgress, 12, 16, "Collecting the split APKs")
        log("Collecting the split APKs.")
        val apkBytes = apks.sumOf { it.length() }.coerceAtLeast(1L)
        var copied = 0L
        apks.forEachIndexed { index, apk ->
            checkCancelled(cancelled)
            val safe = apk.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            apk.copyTo(File(modules, "${index}_$safe"), overwrite = true)
            copied += apk.length()
            collecting(copied.toDouble() / apkBytes.toDouble())
        }
        val merging = span(onProgress, 16, 72, "Merging the XAPK")
        log("Merging the XAPK.")
        merging(0.0)
        ApkBundle().use { bundle ->
            val stopLoad = pulse(0.0, 0.55, merging)
            try {
                bundle.loadApkDirectory(modules)
            } finally {
                stopLoad()
            }
            merging(0.55)
            mergeBundle(bundle, cancelled, merging, log).use { module ->
                merging(0.90)
                mergeFeatureManifests(bundle, module)
                stripSplitMetadata(module)
                merging(0.93)
                repairResourceTable(module)
                merging(0.95)
                module.apkSignatureBlock = null
                if (merged.exists()) merged.delete()
                val stopMerging = watchFile(merged, apkBytes, { ratio -> merging(0.95 + (ratio * 0.05)) })
                try {
                    module.writeApk(merged)
                } finally {
                    stopMerging()
                }
            }
        }
        merging(1.0)
        extracted.deleteRecursively()
        modules.deleteRecursively()
        val patched = convertApk(
            merged,
            output,
            log,
            onStep,
            cancelled,
            kind,
            navigationDex,
            lifecycleDex,
            carDex,
            supportDex,
            archDex,
            activityDex,
            shizukuDex,
            keyboardDex,
            packageSuffix,
            gamepad,
        ) { percent, label ->
            onProgress(72 + (percent * 28 / 100), label)
        }
        merged.delete()
        return patched.copy(format = "apk")
    }

    private fun mergeBundle(
        bundle: ApkBundle,
        cancelled: () -> Boolean,
        merging: (Double) -> Unit,
        log: (String) -> Unit,
    ): ApkModule {
        val modules = bundle.apkModuleList
        if (modules.isEmpty()) throw IOException("Nothing to merge, empty modules")
        val base = bundle.baseModule ?: modules.maxByOrNull { module ->
            module.tableBlock?.headerBlock?.chunkSize ?: 0
        } ?: modules.first()
        val ordered = ArrayList<ApkModule>(modules.size)
        ordered.add(base)
        for (module in modules) {
            if (module !== base) ordered.add(module)
        }
        val weights = ordered.map { moduleWeight(it) }
        val total = weights.sum().coerceAtLeast(1L)
        val merged = ApkModule("merged", ZipEntryMap())
        merged.setLoadDefaultFramework(false)
        merged.setAPKLogger(object : APKLogger {
            override fun logMessage(message: String?) {
                checkCancelled(cancelled)
            }

            override fun logError(message: String?, error: Throwable?) {}

            override fun logVerbose(message: String?) {
                checkCancelled(cancelled)
            }
        })
        var signature = base.apkSignatureBlock
        var done = 0L
        for ((index, module) in ordered.withIndex()) {
            checkCancelled(cancelled)
            val weight = weights[index]
            val start = 0.55 + (0.33 * done.toDouble() / total.toDouble())
            val end = 0.55 + (0.33 * (done + weight).toDouble() / total.toDouble())
            val name = module.moduleName?.takeIf { it.isNotBlank() } ?: "split ${index + 1}"
            log("Merging $name.")
            val stop = pulse(start, end, merging)
            try {
                merged.merge(module, false)
            } finally {
                stop()
            }
            if (module !== base && signature == null) signature = module.apkSignatureBlock
            done += weight
            merging(end)
        }
        merged.apkSignatureBlock = signature
        if (merged.hasTableBlock()) {
            merged.tableBlock?.sortPackages()
            merged.tableBlock?.refresh()
        }
        merged.zipEntryMap.autoSortApkFiles()
        return merged
    }

    private fun moduleWeight(module: ApkModule): Long {
        val table = module.tableBlock?.headerBlock?.chunkSize?.toLong() ?: 0L
        val files = module.inputSources.size.toLong()
        return (table.coerceAtLeast(0L) + files).coerceAtLeast(1L)
    }

    private fun pulse(from: Double, until: Double, report: (Double) -> Unit): () -> Unit {
        val running = AtomicBoolean(true)
        val started = System.nanoTime()
        val width = (until - from).coerceAtLeast(0.001)
        val points = (width * 56.0).coerceAtLeast(1.0)
        val tauSec = (points / 0.22).coerceIn(6.0, 240.0)
        Thread {
            while (running.get()) {
                val elapsedSec = (System.nanoTime() - started) / 1_000_000_000.0
                val eased = (1.0 - kotlin.math.exp(-elapsedSec / tauSec)).coerceAtMost(0.98)
                report(from + (width * eased))
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.apply {
            isDaemon = true
            name = "merge-progress"
            start()
        }
        return { running.set(false) }
    }

    private fun mergeFeatureManifests(bundle: ApkBundle, merged: ApkModule) {
        val target = merged.androidManifest ?: return
        val root = target.manifestElement ?: return
        val application = target.applicationElement ?: return
        merged.refreshManifest()
        for (module in bundle.modules) {
            module.refreshManifest()
            val source = module.androidManifest ?: continue
            val sourceRoot = source.manifestElement ?: continue
            copyNamedChildren(root, sourceRoot, ROOT_TAGS)
            source.applicationElement?.let { copyNamedChildren(application, it, APPLICATION_TAGS) }
        }
        target.refresh()
    }

    private fun copyNamedChildren(destination: ResXmlElement, source: ResXmlElement, tags: Set<String>) {
        val iterator = source.elements
        while (iterator.hasNext()) {
            val element = iterator.next() as? ResXmlElement ?: continue
            if (element.name !in tags || isDistributionElement(element)) continue
            val name = AndroidManifestBlock.getAndroidNameValue(element) ?: continue
            if (hasNamedChild(destination, element.name, name)) continue
            val copy = destination.newElement(element.name)
            try {
                copyElement(copy, element)
            } catch (error: IOException) {
                copy.parentElement?.remove(copy)
            }
        }
    }

    private fun copyElement(destination: ResXmlElement, source: ResXmlElement) {
        destination.setName(source.name)
        if (source.uri != null || source.prefix != null) destination.setNamespace(source.uri, source.prefix)
        val attributes = source.attributes
        while (attributes.hasNext()) copyAttribute(destination, attributes.next())
        val children = source.elements
        while (children.hasNext()) {
            val child = children.next() as? ResXmlElement ?: continue
            if (isDistributionElement(child)) continue
            val copy = destination.newElement(child.name)
            copyElement(copy, child)
        }
    }

    private fun copyAttribute(destination: ResXmlElement, attribute: ResXmlAttribute) {
        val rawName = attribute.name
        val name = if (rawName.isNullOrEmpty() || rawName.startsWith("r0x")) attribute.decodeName(false) else rawName
        if (name.isNullOrEmpty()) return
        val type = attribute.valueType
        val stringValue = type == null || type.name == "STRING"
        val value = if (stringValue) safeValue(attribute) else null
        if (stringValue && value.isNullOrEmpty()) return
        val created = destination.newAttribute()
        created.setName(name, attribute.nameId)
        if (attribute.uri != null) created.setNamespace(attribute.uri, attribute.prefix)
        try {
            if (type?.name == "STRING") {
                val text = attribute.valueAsString ?: throw IOException("The split manifest attribute has no value.")
                created.setValueAsString(text)
            } else if (type != null) {
                created.valueType = type
                created.data = attribute.data
            } else {
                created.setValueAsString(value)
            }
        } catch (error: RuntimeException) {
            destination.removeAttribute(created)
            if (name == "name") throw IOException("The split manifest attribute could not be copied.", error)
        }
    }

    private fun safeValue(attribute: ResXmlAttribute): String? {
        try {
            val decoded = attribute.decodeValue(true)
            if (!decoded.isNullOrEmpty()) return decoded
        } catch (_: RuntimeException) {
        }
        return try {
            attribute.valueAsString
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun isDistributionElement(element: ResXmlElement): Boolean {
        return element.uri?.contains("schemas.android.com/apk/distribution") == true
    }

    private fun hasNamedChild(parent: ResXmlElement, tag: String, name: String): Boolean {
        val iterator = parent.getElements(tag)
        while (iterator.hasNext()) {
            val element = iterator.next() as? ResXmlElement ?: continue
            if (AndroidManifestBlock.getAndroidNameValue(element) == name) return true
        }
        return false
    }

    private fun stripSplitMetadata(apk: ApkModule) {
        val manifest = apk.androidManifest ?: return
        val root = manifest.manifestElement ?: return
        val application = manifest.applicationElement
        removeSplitAttributes(root)
        if (application != null) removeSplitAttributes(application)
        removeNamedChildren(root, "uses-split")
        for (name in SPLIT_META) removeMeta(manifest, name)
        manifest.refresh()
    }

    private fun removeSplitAttributes(element: ResXmlElement) {
        removeAttribute(element, AndroidManifest.ID_requiredSplitTypes, AndroidManifest.NAME_requiredSplitTypes)
        removeAttribute(element, AndroidManifest.ID_splitTypes, AndroidManifest.NAME_splitTypes)
        removeAttribute(element, AndroidManifest.ID_isSplitRequired, AndroidManifest.NAME_isSplitRequired)
        removeAttribute(element, AndroidManifest.ID_extractNativeLibs, AndroidManifest.NAME_extractNativeLibs)
        element.searchAttributeByName(AndroidManifest.NAME_split)?.let { element.removeAttribute(it) }
        element.searchAttributeByName("isolatedSplits")?.let { element.removeAttribute(it) }
    }

    private fun repairResourceTable(apk: ApkModule) {
        val table = apk.tableBlock ?: return
        val packages = table.iterator()
        while (packages.hasNext()) {
            val pkg = packages.next() as? PackageBlock ?: continue
            val pairs = pkg.specTypePairs
            while (pairs.hasNext()) {
                val pair = pairs.next() as? SpecTypePair ?: continue
                val needed = maxOf(pair.highestEntryCount, pair.highestEntryId + 1)
                if (needed < 1) continue
                val spec: SpecBlock? = pair.specBlock
                if (spec != null && spec.entryCount < needed) spec.entryCount = needed
                val types = pair.typeBlocks
                while (types.hasNext()) {
                    val type = types.next() as? TypeBlock ?: continue
                    if (type.entryArray.size() < needed) type.ensureEntriesCount(needed)
                }
            }
        }
        table.refreshFull()
    }

    private fun removeAttribute(element: ResXmlElement, id: Int, name: String) {
        val attribute = element.searchAttributeByResourceId(id) ?: element.searchAttributeByName(name)
        if (attribute != null) element.removeAttribute(attribute)
    }

    private fun removeNamedChildren(parent: ResXmlElement, tag: String) {
        val found = ArrayList<ResXmlElement>()
        val iterator = parent.getElements(tag)
        while (iterator.hasNext()) {
            val element = iterator.next() as? ResXmlElement ?: continue
            found.add(element)
        }
        for (element in found) element.parentElement?.remove(element)
    }

    private fun removeMeta(manifest: AndroidManifestBlock, name: String) {
        val found = ArrayList<ResXmlElement>()
        val iterator = manifest.recursiveElements()
        while (iterator.hasNext()) {
            val element = iterator.next() as? ResXmlElement ?: continue
            if (element.equalsName(AndroidManifest.TAG_meta_data) && AndroidManifestBlock.getAndroidNameValue(element) == name) {
                found.add(element)
            }
        }
        for (element in found) element.parentElement?.remove(element)
    }

    fun alignAndSign(input: File, output: File, packageName: String = "", log: (String) -> Unit) {
        val signer = signerFor(packageName)
        val aligned = File(output.parentFile, ".aligning-${output.name}")
        try {
            try {
                ZipAlign.align(input, aligned, pageSize = 16 * 1024, alignment = 4)
                log("Aligned uncompressed files to 4 bytes and native libraries to 16 KB.")
            } catch (error: ZipAlignException) {
                log("16 KB alignment failed. Aligning to 4 bytes.")
                error.message?.let(log)
                if (aligned.exists()) aligned.delete()
                ZipAlign.align(input, aligned, pageSize = 4096, alignment = 4)
            }
            if (output.exists()) output.delete()
            ApkSigner.Builder(listOf(signer))
                .setInputApk(aligned)
                .setOutputApk(output)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setV4SigningEnabled(false)
                .setOtherSignersSignaturesPreserved(false)
                .build()
                .sign()
            val verified = ApkVerifier.Builder(output).build().verify()
            if (!verified.isVerified) {
                val details = verified.errors.joinToString("\n") { it.toString() }
                throw IllegalStateException("The signed package did not verify.\n$details".trim())
            }
            log("Signed and verified with apksig.")
        } finally {
            if (aligned.exists()) aligned.delete()
        }
    }

    private fun span(
        onProgress: (Int, String) -> Unit,
        start: Int,
        end: Int,
        label: String,
    ): (Double) -> Unit {
        var last = -1
        val gate = Any()
        return fun(ratio: Double) {
            val percent = (start + ((end - start) * ratio.coerceIn(0.0, 1.0))).toInt().coerceIn(0, 100)
            synchronized(gate) {
                if (percent <= last) return
                last = percent
            }
            onProgress(percent, label)
        }
    }

    private fun watchFile(file: File, expected: Long, report: (Double) -> Unit, cap: Double = 0.9): () -> Unit {
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        Thread {
            while (running.get()) {
                val size = if (file.isFile) file.length() else 0L
                val ratio = (size.toDouble() / expected.coerceAtLeast(1L)).coerceIn(0.0, cap)
                if (ratio > 0.0) report(ratio)
                try {
                    Thread.sleep(400)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.apply {
            isDaemon = true
            name = "package-progress"
            start()
        }
        return { running.set(false) }
    }

    private fun stripCorruptOverlayables(apkFile: File): Boolean {
        val temp = File(apkFile.parentFile, "${apkFile.name}.overlay")
        var changed = false
        ZipFile(apkFile).use { zip ->
            val resources = zip.getEntry("resources.arsc") ?: return false
            val original = zip.getInputStream(resources).use { it.readBytes() }
            val stripped = removeShortOverlayables(original)
            if (stripped.contentEquals(original)) return false
            ZipOutputStream(temp.outputStream()).use { out ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val item = entries.nextElement()
                    val next = ZipEntry(item.name)
                    next.time = item.time
                    if (item.name == "resources.arsc") {
                        val crc = CRC32()
                        crc.update(stripped)
                        next.method = ZipEntry.STORED
                        next.size = stripped.size.toLong()
                        next.compressedSize = stripped.size.toLong()
                        next.crc = crc.value
                        out.putNextEntry(next)
                        out.write(stripped)
                    } else if (item.method == ZipEntry.STORED) {
                        next.method = ZipEntry.STORED
                        next.size = item.size
                        next.compressedSize = item.size
                        next.crc = item.crc
                        out.putNextEntry(next)
                        zip.getInputStream(item).use { it.copyTo(out) }
                    } else {
                        out.putNextEntry(next)
                        zip.getInputStream(item).use { it.copyTo(out) }
                    }
                    out.closeEntry()
                }
            }
            changed = true
        }
        if (!changed) return false
        if (!apkFile.delete() || !temp.renameTo(apkFile)) {
            temp.copyTo(apkFile, overwrite = true)
            temp.delete()
        }
        return true
    }

    private fun removeShortOverlayables(source: ByteArray): ByteArray {
        if (source.size < 12) return source
        val data = source.copyOf()
        val removals = ArrayList<Pair<Int, Int>>()
        collectOverlayables(data, 0, data.size, false, removals)
        if (removals.isEmpty()) return source
        fun u16(offset: Int) = (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)
        fun u32(offset: Int) =
            (data[offset].toInt() and 0xff) or
                ((data[offset + 1].toInt() and 0xff) shl 8) or
                ((data[offset + 2].toInt() and 0xff) shl 16) or
                ((data[offset + 3].toInt() and 0xff) shl 24)
        fun setU32(offset: Int, value: Int) {
            data[offset] = value.toByte()
            data[offset + 1] = (value ushr 8).toByte()
            data[offset + 2] = (value ushr 16).toByte()
            data[offset + 3] = (value ushr 24).toByte()
        }
        fun shrinkPackages(start: Int, end: Int) {
            var pos = start
            while (pos + 8 <= end) {
                val type = u16(pos)
                val headerSize = u16(pos + 2)
                val size = u32(pos + 4)
                if (size < 8 || pos + size > end) return
                if (type == 0x0200) {
                    val lost = removals.filter { it.first >= pos && it.first + it.second <= pos + size }.sumOf { it.second }
                    if (lost > 0) setU32(pos + 4, size - lost)
                    shrinkPackages(pos + headerSize, pos + size)
                } else if (type == 0x0002) {
                    shrinkPackages(pos + headerSize, pos + size)
                }
                pos += size
            }
        }
        shrinkPackages(0, data.size)
        setU32(4, u32(4) - removals.sumOf { it.second })
        var current = data
        for ((start, size) in removals.sortedByDescending { it.first }) {
            val next = ByteArray(current.size - size)
            System.arraycopy(current, 0, next, 0, start)
            System.arraycopy(current, start + size, next, start, current.size - start - size)
            current = next
        }
        return current
    }

    private fun collectOverlayables(
        data: ByteArray,
        start: Int,
        end: Int,
        inPackage: Boolean,
        removals: MutableList<Pair<Int, Int>>,
    ) {
        fun u16(offset: Int) = (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)
        fun u32(offset: Int) =
            (data[offset].toInt() and 0xff) or
                ((data[offset + 1].toInt() and 0xff) shl 8) or
                ((data[offset + 2].toInt() and 0xff) shl 16) or
                ((data[offset + 3].toInt() and 0xff) shl 24)
        var pos = start
        while (pos + 8 <= end) {
            val type = u16(pos)
            val headerSize = u16(pos + 2)
            val size = u32(pos + 4)
            if (size < 8 || pos + size > end) return
            when (type) {
                0x0002 -> collectOverlayables(data, pos + headerSize, pos + size, false, removals)
                0x0200 -> collectOverlayables(data, pos + headerSize, pos + size, true, removals)
                0x0204 -> if (inPackage && headerSize < 8 + 512 + 512) removals.add(pos to size)
            }
            pos += size
        }
    }

    private fun addNavigationLibraries(
        apkFile: File,
        libraryIds: List<Pair<Int, Int>>,
        navigationDex: ByteArray?,
        lifecycleDex: ByteArray?,
        carDex: ByteArray?,
        supportDex: ByteArray?,
        archDex: ByteArray?,
        activityDex: ByteArray?,
        shizukuDex: ByteArray?,
    ) {
        val navigationToAdd = navigationDex?.takeUnless { zipContains(apkFile, "Lcom/automaker/nav/NavigationCarService;") }
        val carToAdd = carDex?.takeUnless { zipContains(apkFile, "Landroidx/car/app/serialization/Bundler;") }
        val lifecycleToAdd = lifecycleDex?.takeUnless { zipContains(apkFile, "Landroidx/lifecycle/LifecycleRegistry;") }
        val archToAdd = archDex?.takeUnless { zipContains(apkFile, "Landroidx/arch/core/internal/FastSafeIterableMap;") }
        val activityToAdd = activityDex?.takeUnless { zipContains(apkFile, "Landroidx/activity/OnBackPressedDispatcher;") }
        val shizukuToAdd = shizukuDex?.takeUnless { zipContains(apkFile, "Lrikka/shizuku/ShizukuProvider;") }
        if (navigationToAdd != null) addStoredDex(apkFile, navigationToAdd)
        if (carToAdd != null) addStoredDex(apkFile, carToAdd)
        if (lifecycleToAdd != null) addStoredDex(apkFile, lifecycleToAdd)
        if (archToAdd != null) addStoredDex(apkFile, archToAdd)
        if (activityToAdd != null) addStoredDex(apkFile, activityToAdd)
        if (shizukuToAdd != null) addStoredDex(apkFile, shizukuToAdd)
        if (supportDex != null) addStoredDex(apkFile, patchCarSupport(supportDex, libraryIds))
        addStoredEntry(apkFile, "car-app-api.level", "8\n".encodeToByteArray())
    }

    private fun carLibraryIds(apk: ApkModule): List<Pair<Int, Int>> {
        val names = listOf(
            0x51100001 to ("string" to "car_app_library_version"),
            0x51100002 to ("integer" to "content_limit_grid"),
            0x51100003 to ("integer" to "content_limit_pane"),
            0x51100004 to ("integer" to "content_limit_place_list"),
            0x51100005 to ("integer" to "content_limit_route_list"),
            0x51100006 to ("integer" to "content_limit_list"),
        )
        return names.map { (sentinel, typeAndName) ->
            val id = ManifestPatcher.carLibraryResourceId(apk, typeAndName.first, typeAndName.second)
            if (id == 0) throw IllegalStateException("The car library resources could not be added.")
            sentinel to id
        }
    }

    private fun patchCarSupport(dex: ByteArray, replacements: List<Pair<Int, Int>>): ByteArray {
        val out = dex.copyOf()
        for ((sentinel, id) in replacements) {
            val from = littleEndian(sentinel)
            val to = littleEndian(id)
            var index = indexOfBytes(out, from, 0)
            if (index < 0) throw IllegalStateException("The car support library is missing a resource placeholder.")
            while (index >= 0) {
                to.copyInto(out, index)
                index = indexOfBytes(out, from, index + 4)
            }
        }
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(out, 32, out.size - 32)
        digest.digest().copyInto(out, 12)
        val adler = Adler32()
        adler.update(out, 12, out.size - 12)
        val sum = adler.value.toInt()
        out[8] = (sum and 0xff).toByte()
        out[9] = ((sum ushr 8) and 0xff).toByte()
        out[10] = ((sum ushr 16) and 0xff).toByte()
        out[11] = ((sum ushr 24) and 0xff).toByte()
        return out
    }

    private fun littleEndian(value: Int): ByteArray {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    }

    private fun indexOfBytes(bytes: ByteArray, needle: ByteArray, start: Int): Int {
        if (needle.isEmpty() || start > bytes.size - needle.size) return -1
        val last = bytes.size - needle.size
        for (index in start..last) {
            var matches = true
            for (offset in needle.indices) {
                if (bytes[index + offset] != needle[offset]) {
                    matches = false
                    break
                }
            }
            if (matches) return index
        }
        return -1
    }

    private fun addKeyboardDex(apkFile: File, dex: ByteArray) {
        if (!zipContains(apkFile, "Lcom/automaker/game/KeyboardStay;")) addStoredDex(apkFile, dex)
    }

    private fun zipContains(apkFile: File, marker: String): Boolean {
        val needle = marker.encodeToByteArray()
        ZipFile(apkFile).use { zip ->
            return zip.entries().toList().any { entry ->
                entry.name.matches(Regex("""classes\d*\.dex""")) &&
                    zip.getInputStream(entry).use { containsSequence(it.readBytes(), needle) }
            }
        }
    }

    private fun addStoredEntry(apkFile: File, name: String, bytes: ByteArray) {
        ZipFile(apkFile).use { zip ->
            if (zip.getEntry(name) != null) return
        }
        addStoredBytes(apkFile, name, bytes)
    }

    private fun addStoredDex(apkFile: File, dex: ByteArray) {
        val name = ZipFile(apkFile).use { zip ->
            val names = zip.entries().toList().map { it.name }.toSet()
            var index = 2
            while ("classes$index.dex" in names) index += 1
            "classes$index.dex"
        }
        addStoredBytes(apkFile, name, dex)
    }

    private fun addStoredBytes(apkFile: File, name: String, bytes: ByteArray) {
        ZipFile(apkFile).use { zip ->
            val entries = zip.entries().toList()
            val temp = File(apkFile.parentFile, "${apkFile.name}.dexed")
            ZipOutputStream(temp.outputStream()).use { out ->
                for (entry in entries) {
                    val next = ZipEntry(entry.name)
                    next.time = entry.time
                    if (entry.method == ZipEntry.STORED) {
                        next.method = ZipEntry.STORED
                        next.size = entry.size
                        next.compressedSize = entry.size
                        next.crc = entry.crc
                    }
                    out.putNextEntry(next)
                    zip.getInputStream(entry).use { it.copyTo(out) }
                    out.closeEntry()
                }
                val crc = CRC32()
                crc.update(bytes)
                val added = ZipEntry(name)
                added.method = ZipEntry.STORED
                added.size = bytes.size.toLong()
                added.compressedSize = bytes.size.toLong()
                added.crc = crc.value
                out.putNextEntry(added)
                out.write(bytes)
                out.closeEntry()
            }
        }
        val temp = File(apkFile.parentFile, "${apkFile.name}.dexed")
        if (temp.exists()) {
            if (!apkFile.delete() || !temp.renameTo(apkFile)) {
                temp.copyTo(apkFile, overwrite = true)
                temp.delete()
            }
        }
    }

    private fun containsSequence(bytes: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || bytes.size < needle.size) return false
        val last = bytes.size - needle.size
        for (start in 0..last) {
            var matches = true
            for (offset in needle.indices) {
                if (bytes[start + offset] != needle[offset]) {
                    matches = false
                    break
                }
            }
            if (matches) return true
        }
        return false
    }

    private fun stripSignatures(apk: ApkModule) {
        val paths = apk.inputSources.mapNotNull { source ->
            source.alias?.takeIf { it.isNotBlank() } ?: source.name
        }
        for (path in paths) {
            val normalized = path.replace('\\', '/')
            val upper = normalized.uppercase()
            val base = upper.substringAfterLast('/')
            val signature = upper.startsWith("META-INF/") && (
                base == "MANIFEST.MF" ||
                    base.endsWith(".SF") ||
                    base.endsWith(".RSA") ||
                    base.endsWith(".DSA") ||
                    base.endsWith(".EC")
                )
            if (signature) apk.removeInputSource(normalized)
        }
    }

    private fun stripInternalFiles(apk: ApkModule) {
        val paths = apk.inputSources.mapNotNull { source ->
            source.alias?.takeIf { it.isNotBlank() } ?: source.name
        }
        for (path in paths) {
            val normalized = path.replace('\\', '/')
            val internal = normalized == "SEC-INF" ||
                normalized.startsWith("SEC-INF/") ||
                normalized == "stamp-cert-sha256" ||
                normalized.endsWith("/stamp-cert-sha256")
            if (internal) apk.removeInputSource(normalized)
        }
    }

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled()) throw CancelledConversion()
    }

    companion object {
        fun signerFrom(keystore: File): ApkSigner.SignerConfig {
            val store = KeyStore.getInstance("PKCS12")
            keystore.inputStream().use { store.load(it, "android".toCharArray()) }
            val key = store.getKey("androiddebugkey", "android".toCharArray()) as PrivateKey
            val certificate = store.getCertificate("androiddebugkey") as X509Certificate
            return ApkSigner.SignerConfig.Builder("androiddebugkey", key, listOf(certificate)).build()
        }
    }
}

class CancelledConversion : RuntimeException("The conversion was cancelled.")
