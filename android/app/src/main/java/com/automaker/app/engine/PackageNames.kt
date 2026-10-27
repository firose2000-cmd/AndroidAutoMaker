package com.automaker.app.engine

object PackageNames {
    const val SUFFIX = ".patched"

    private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+$")

    fun withSuffix(packageName: String, suffix: String = SUFFIX): String {
        val name = packageName.trim()
        if (name.isEmpty() || suffix.isEmpty() || name.endsWith(suffix)) return name
        val next = name + suffix
        if (!PACKAGE_NAME.matches(next)) {
            throw IllegalArgumentException("The package name cannot take $suffix.")
        }
        return next
    }

    fun replacePrefix(value: String, oldName: String, newName: String): String {
        if (oldName.isEmpty() || oldName == newName) return value
        if (value == oldName) return newName
        val prefix = "$oldName."
        if (value.startsWith(prefix)) return newName + value.removePrefix(oldName)
        return value
    }

    fun replaceList(value: String, oldName: String, newName: String): String {
        return value.split(';').joinToString(";") { part ->
            val leading = part.takeWhile { it == ' ' }
            val trailing = part.takeLastWhile { it == ' ' }
            val core = part.trim()
            leading + replacePrefix(core, oldName, newName) + trailing
        }
    }
}
