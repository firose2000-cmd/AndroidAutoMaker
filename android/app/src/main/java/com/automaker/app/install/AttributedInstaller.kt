package com.automaker.app.install

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.automaker.app.engine.Sidecars
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.io.File

class AttributedInstaller(private val context: Context) {
    fun shizukuReady(): Boolean {
        if (!shizukuInstalled()) return false
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    fun requestPermission(): Boolean {
        if (!shizukuInstalled()) return false
        return try {
            if (!Shizuku.pingBinder()) return false
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return false
            Shizuku.requestPermission(SHIZUKU_REQUEST)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun openAsShell(file: File): Boolean {
        if (!shizukuReady()) return false
        return try {
            val intent = PlayStoreInstall.createShellInstallIntent(context, file)
            startInstallActivityAsShell(intent)
        } catch (error: Throwable) {
            Log.e(TAG, "Could not open the installer as shell", error)
            false
        }
    }

    fun installSidecars(root: File, packageName: String) {
        check(shizukuReady()) { "Shizuku authorization is required to copy expansion files." }
        val files = Sidecars.files(root)
        // Validate the complete batch before performing any privileged writes.
        val destinations = files.map { (_, relative) -> InstallPolicy.sidecarDestination(packageName, relative) }
        files.zip(destinations).forEach { (entry, remote) ->
            shell("mkdir -p '${remote.substringBeforeLast('/')}'")
            copyQuoted(entry.first, remote)
        }
    }

    @SuppressLint("DiscouragedPrivateApi", "PrivateApi", "BlockedPrivateApi")
    private fun startInstallActivityAsShell(intent: android.content.Intent): Boolean {
        val binder = SystemServiceHelper.getSystemService("activity_task")
            ?: SystemServiceHelper.getSystemService("activity")
            ?: return false
        val wrappedBinder = ShizukuBinderWrapper(binder)
        val atmStubClass = Class.forName("android.app.IActivityTaskManager\$Stub")
        val asInterfaceMethod = atmStubClass.getDeclaredMethod("asInterface", IBinder::class.java)
        asInterfaceMethod.isAccessible = true
        val atmInstance = asInterfaceMethod.invoke(null, wrappedBinder) ?: return false
        val method = atmInstance.javaClass.methods.firstOrNull { it.name == "startActivityAsUser" }
            ?: atmInstance.javaClass.declaredMethods.firstOrNull { it.name == "startActivityAsUser" }
            ?: return false
        method.isAccessible = true
        val args = arrayOfNulls<Any>(method.parameterTypes.size)
        for (index in method.parameterTypes.indices) {
            val paramType = method.parameterTypes[index]
            args[index] = when {
                paramType.isAssignableFrom(android.content.Intent::class.java) -> intent
                paramType == String::class.java && (index == 1 || index == 2) -> "com.android.shell"
                paramType == Int::class.javaPrimitiveType -> 0
                else -> null
            }
        }
        method.invoke(atmInstance, *args)
        return true
    }

    private fun shell(command: String): String {
        val process = remoteProcess(arrayOf("sh", "-c", command))
        val output = StringBuilder()
        val error = StringBuilder()
        val outThread = Thread { output.append(process.inputStream.bufferedReader().readText()) }
        val errThread = Thread { error.append(process.errorStream.bufferedReader().readText()) }
        outThread.start()
        errThread.start()
        val code = process.waitFor()
        outThread.join()
        errThread.join()
        val text = output.toString() + error.toString()
        if (code != 0) throw IllegalStateException(text.ifBlank { "Command failed." })
        return text
    }

    private fun copyQuoted(file: File, remote: String) {
        val process = remoteProcess(arrayOf("sh", "-c", "cat > '$remote'"))
        val error = StringBuilder()
        val errThread = Thread { error.append(process.errorStream.bufferedReader().readText()) }
        errThread.start()
        file.inputStream().use { input -> input.copyTo(process.outputStream) }
        process.outputStream.close()
        process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        errThread.join()
        if (code != 0) throw IllegalStateException(error.toString().ifBlank { "The file could not be copied." })
    }

    private fun remoteProcess(command: Array<String>): Process {
        check(shizukuReady()) { "Shizuku authorization was revoked." }
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(null, command, null, null) as Process
    }

    private fun shizukuInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    companion object {
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val TAG = "AttributedInstaller"
        const val SHIZUKU_REQUEST = 5001
    }
}
