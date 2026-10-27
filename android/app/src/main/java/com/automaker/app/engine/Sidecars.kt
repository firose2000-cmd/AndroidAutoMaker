package com.automaker.app.engine

import org.json.JSONObject
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object Sidecars {
    fun stash(extracted: File, destRoot: File, packageSuffix: String) {
        if (!extracted.isDirectory) return
        destRoot.deleteRecursively()
        val oldName = packageName(extracted)
        val newName = if (packageSuffix.isBlank() || oldName.isBlank()) oldName else PackageNames.withSuffix(oldName, packageSuffix)
        var copied = 0
        fun walk(dir: File) {
            for (child in dir.listFiles().orEmpty()) {
                if (child.isDirectory) {
                    walk(child)
                    continue
                }
                val relative = extracted.canonicalFile.toPath().relativize(child.canonicalFile.toPath()).toString().replace('\\', '/')
                if (relative == "manifest.json" || relative.endsWith(".apk")) continue
                val renamed = rewritePackageSegment(relative, oldName, newName)
                if (renamed.contains("..")) continue
                val target = File(destRoot, renamed)
                target.parentFile?.mkdirs()
                child.copyTo(target, overwrite = true)
                copied += 1
            }
        }
        walk(extracted)
        if (copied == 0) destRoot.deleteRecursively()
    }

    fun files(root: File): List<Pair<File, String>> {
        if (!root.isDirectory) return emptyList()
        val found = ArrayList<Pair<File, String>>()
        fun walk(dir: File) {
            for (child in dir.listFiles().orEmpty()) {
                if (child.isDirectory) walk(child)
                else {
                    val relative = root.canonicalFile.toPath().relativize(child.canonicalFile.toPath()).toString().replace('\\', '/')
                    if (!relative.startsWith("Android/") || relative.contains("..")) continue
                    found.add(child to relative)
                }
            }
        }
        walk(root)
        return found
    }

    fun storeZip(sourceDir: File, dest: File) {
        if (dest.exists()) dest.delete()
        val files = ArrayList<File>()
        fun walk(dir: File) {
            for (child in dir.listFiles().orEmpty().sortedBy { it.name }) {
                if (child.isDirectory) walk(child) else files.add(child)
            }
        }
        walk(sourceDir)
        ZipOutputStream(dest.outputStream()).use { zip ->
            for (file in files) {
                val name = sourceDir.canonicalFile.toPath().relativize(file.canonicalFile.toPath()).toString().replace('\\', '/')
                val crc = CRC32()
                file.inputStream().use { input ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        crc.update(buffer, 0, count)
                    }
                }
                val entry = ZipEntry(name)
                entry.method = ZipEntry.STORED
                entry.size = file.length()
                entry.compressedSize = file.length()
                entry.crc = crc.value
                zip.putNextEntry(entry)
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        if (!dest.isFile) throw IllegalStateException("The installed app could not be packed.")
    }

    private fun packageName(extracted: File): String {
        val manifest = File(extracted, "manifest.json")
        if (manifest.isFile) {
            val name = runCatching { JSONObject(manifest.readText().removePrefix("\uFEFF")).optString("package_name") }.getOrDefault("")
            if (name.isNotBlank()) return name
        }
        return XapkPackages.listBundle(extracted).obbs.firstOrNull()?.packageName.orEmpty()
    }

    private fun rewritePackageSegment(relative: String, oldName: String, newName: String): String {
        if (oldName.isBlank() || oldName == newName) return relative
        return relative.split('/').joinToString("/") { segment ->
            if (segment == oldName) newName else segment
        }
    }
}
