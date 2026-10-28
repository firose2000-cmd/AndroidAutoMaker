package com.automaker.app.engine

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object InstalledApps {
    data class ListedApp(
        val packageName: String,
        val label: String,
        val size: Long,
        val splitCount: Int,
    )

    fun list(context: Context): List<ListedApp> {
        val manager = context.packageManager
        return installedApplications(manager).mapNotNull { info ->
            if (info.packageName == context.packageName || !isLaunchable(manager, info.packageName)) return@mapNotNull null
            val source = info.sourceDir ?: return@mapNotNull null
            if (!File(source).isFile) return@mapNotNull null
            val system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
            val updated = info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
            if (system && !updated && !isLaunchable(manager, info.packageName)) return@mapNotNull null
            val splits = info.splitSourceDirs?.size ?: 0
            var size = File(source).length()
            info.splitSourceDirs.orEmpty().forEach { path -> size += File(path).length() }
            ListedApp(
                packageName = info.packageName,
                label = manager.getApplicationLabel(info)?.toString().orEmpty().ifBlank { info.packageName },
                size = size,
                splitCount = splits + 1,
            )
        }.sortedBy { it.label.lowercase() }
    }

    fun export(context: Context, packageName: String, destZip: File): List<String> {
        val manager = context.packageManager
        require(packageName != context.packageName && isLaunchable(manager, packageName)) {
            "Select a visible launchable application."
        }
        val info = if (Build.VERSION.SDK_INT >= 33) {
            manager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            manager.getApplicationInfo(packageName, 0)
        }
        val source = info.sourceDir ?: throw IllegalArgumentException("That app has no APK.")
        val notes = ArrayList<String>()
        val root = File(destZip.parentFile, "export-${destZip.nameWithoutExtension}")
        root.deleteRecursively()
        root.mkdirs()
        try {
            val apkDir = File(root, "apks")
            apkDir.mkdirs()
            val base = File(apkDir, "${safeFile(packageName)}.apk")
            File(source).copyTo(base, overwrite = true)
            val splits = JSONArray()
            splits.put(JSONObject().put("file", "apks/${base.name}").put("id", "base"))
            info.splitSourceDirs.orEmpty().forEachIndexed { index, path ->
                val file = File(path)
                if (!file.isFile) return@forEachIndexed
                val name = safeFile(file.name.ifBlank { "split$index.apk" })
                val stored = File(apkDir, if (name.endsWith(".apk")) name else "$name.apk")
                file.copyTo(stored, overwrite = true)
                splits.put(JSONObject().put("file", "apks/${stored.name}").put("id", stored.nameWithoutExtension))
            }
            notes.add(if (splits.length() == 1) "Copied the installed APK." else "Copied ${splits.length()} APKs, including splits.")
            notes.add("Only APKs and splits were copied. Select an XAPK document to include expansion files; private app data is never exported.")
            val label = manager.getApplicationLabel(info)?.toString().orEmpty().ifBlank { packageName }
            val manifest = JSONObject()
            manifest.put("package_name", packageName)
            manifest.put("name", label)
            manifest.put("split_apks", splits)
            File(root, "manifest.json").writeText(manifest.toString(2) + "\n")
            Sidecars.storeZip(root, destZip)
        } finally {
            root.deleteRecursively()
        }
        return notes
    }

    private fun safeFile(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return cleaned.ifBlank { "file" }
    }

    @Suppress("DEPRECATION")
    private fun isLaunchable(manager: PackageManager, packageName: String): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName)
        return if (Build.VERSION.SDK_INT >= 33) {
            manager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0)).isNotEmpty()
        } else {
            manager.queryIntentActivities(intent, 0).isNotEmpty()
        }
    }

    @Suppress("DEPRECATION")
    private fun installedApplications(manager: PackageManager): List<ApplicationInfo> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val matches = if (Build.VERSION.SDK_INT >= 33) {
            manager.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0))
        } else {
            manager.queryIntentActivities(launcher, 0)
        }
        return matches.mapNotNull { it.activityInfo?.applicationInfo }.distinctBy { it.packageName }
    }
}
