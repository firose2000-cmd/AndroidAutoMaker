package com.automaker.app.install

/** Never let an uploaded archive choose another application's privileged storage destination. */
object InstallPolicy {
    private val packageNamePattern = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")
    private val safePath = Regex("[A-Za-z0-9_./-]+")

    fun sidecarDestination(packageName: String, relative: String): String {
        require(packageNamePattern.matches(packageName)) { "Invalid package name." }
        require(safePath.matches(relative) && relative.split('/').none { it == "." || it == ".." || it.isEmpty() }) {
            "Unsafe expansion file path."
        }
        require(relative.startsWith("Android/obb/$packageName/") ||
            relative.startsWith("Android/data/$packageName/files/")) {
            "Expansion files must belong to the converted package."
        }
        return "/sdcard/$relative"
    }
}
