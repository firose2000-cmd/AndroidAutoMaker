package com.automaker.app.install

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Installation uses the actual caller identity and a temporary read-only URI grant. */
object PlayStoreInstall {
    const val APK_MIME_TYPE = "application/vnd.android.package-archive"

    private fun sharedFile(context: Context, source: File): File {
        val name = java.security.MessageDigest.getInstance("SHA-256")
            .digest(source.canonicalPath.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(context.cacheDir, "install-share").apply { mkdirs() }, "$name.apk")
    }

    @Suppress("DEPRECATION")
    fun createInstallIntent(context: Context, file: File): Intent {
        val shared = sharedFile(context, file)
        file.copyTo(shared, overwrite = true)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", shared)
        return Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
    }

    fun revoke(context: Context, source: File) {
        val shared = sharedFile(context, source)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", shared)
        context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        shared.delete()
    }

    fun createShellInstallIntent(context: Context, file: File): Intent =
        createInstallIntent(context, file).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Shell only receives this APK, never the whole sessions tree.
            context.grantUriPermission("com.android.shell", requireNotNull(data), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
}
