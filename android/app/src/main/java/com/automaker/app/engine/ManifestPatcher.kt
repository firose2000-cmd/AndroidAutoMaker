package com.automaker.app.engine

import com.reandroid.apk.ApkModule
import com.reandroid.app.AndroidManifest
import com.reandroid.archive.ByteInputSource
import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.io.BlockReader
import com.reandroid.arsc.value.ValueType

object ManifestPatcher {
    const val AUTOMOTIVE_FEATURE = "android.hardware.type.automotive"
    const val GAMEPAD_FEATURE = "android.hardware.gamepad"
    const val LAUNCHER = "android.intent.category.LAUNCHER"
    const val CAR_LAUNCHER = "android.intent.category.CAR_LAUNCHER"
    const val MAIN = "android.intent.action.MAIN"
    const val DERIVED_APK_ID = "com.android.vending.derived.apk.id"
    private val INTERNAL_META = arrayOf(
        "com.android.vending.splits.required",
        "com.android.vending.splits",
        "SPDE.build.signature",
    )
    const val DISTRACTION = "distractionOptimized"
    const val GMS_CAR = "com.google.android.gms.car.application"
    const val APP_MAPS = "android.intent.category.APP_MAPS"
    const val CAR_NAVIGATION = "androidx.car.app.category.NAVIGATION"
    const val CAR_APP_SERVICE = "androidx.car.app.CarAppService"
    const val MIN_CAR_API = "androidx.car.app.minCarApiLevel"
    const val NAVIGATION_XML = "res/xml/automotive_app_desc.xml"
    const val TOUCH_BRIDGE_XML = "res/xml/automaker_touch_bridge.xml"
    const val NAV_ACTIVITY = "com.automaker.nav.NavigationActivity"
    private val NAVIGATION_USES = arrayOf("template")

    private const val ID_APP_CATEGORY = 0x01010545
    private const val ID_IS_GAME = 0x010103f4
    private const val ID_REQUIRED = 0x0101028e
    private const val ID_CONFIG_CHANGES = 0x0101001f
    private const val CONFIG_KEYBOARD = 0x0010
    private const val CONFIG_KEYBOARD_HIDDEN = 0x0020
    private val CATEGORIES = arrayOf(
        "game",
        "audio",
        "video",
        "image",
        "social",
        "news",
        "maps",
        "productivity",
        "accessibility",
    )

    fun patch(
        manifest: AndroidManifestBlock,
        apk: ApkModule,
        kind: String = "game",
        packageSuffix: String = "",
        gamepad: Boolean = false,
    ): List<String> {
        val navigation = kind == "navigation"
        val changes = ArrayList<String>()
        val application = manifest.applicationElement
            ?: throw IllegalStateException("AndroidManifest.xml has no <application> element.")
        if (packageSuffix.isNotBlank()) renamePackage(manifest, packageSuffix, changes)
        if (removeInternalStamps(manifest) || markStandalone(application)) {
            changes.add("Removed the internal build stamp so the resigned package can be installed.")
        }
        removeNavigationHost(manifest)
        if (navigation) removeKeyboardProvider(application)
        if (navigation) {
            declareMaps(application, changes)
            ensurePermission(
                manifest,
                "androidx.car.app.NAVIGATION_TEMPLATES",
                changes,
                "Added the navigation template permission.",
            )
            ensurePermission(
                manifest,
                "androidx.car.app.ACCESS_SURFACE",
                changes,
                "Added the car surface permission.",
            )
            ensurePermission(
                manifest,
                "android.permission.SYSTEM_ALERT_WINDOW",
                changes,
                "Added the overlay permission so taps on the app picture can be forwarded.",
            )
            ensureNavigationService(manifest, application, changes)
            ensureTouchBridge(apk, application, changes)
            ensureMinCarApi(application, changes, "Set the app car API level to 5.")
            ensureShizuku(manifest, application, changes)
            separateLauncherFromCar(manifest)
            allowOnVirtualDisplay(application)
            ensureNavigationDescriptor(apk, application, changes)
            ensureCarLibraryResources(apk)
        } else {
            if (removeMeta(manifest, DISTRACTION, changes, null) || removeDistractionAttributes(manifest)) {
                changes.add("Removed distraction-optimized flags so Android Auto keeps the app in the parked category.")
            }
            declareGame(application, changes)
            keepKeyboard(application)
        }
        ensureAutomotiveFeature(manifest, changes)
        if (gamepad) ensureGamepadFeature(manifest, changes)
        if (!navigation) {
            ensureCarLauncher(manifest, changes, false)
            ensureExported(manifest, changes)
            ensureKeyboardProvider(manifest, application, changes)
        }
        manifest.fixNamespaces()
        manifest.fixAttributeNames()
        val summary = ArrayList<String>()
        summary.add(
            if (navigation) "Prepared as navigation. The app screen stays available while driving."
            else "Prepared as a parked game.",
        )
        summary.addAll(changes.filter { it.startsWith("Renamed the package") })
        return summary
    }

    private fun renamePackage(manifest: AndroidManifestBlock, suffix: String, changes: MutableList<String>) {
        val oldName = manifest.packageName?.trim().orEmpty()
        if (oldName.isEmpty()) return
        val newName = PackageNames.withSuffix(oldName, suffix)
        if (newName == oldName) return
        manifest.ensureFullClassNames()
        expandBareComponentNames(manifest, oldName)
        rewriteInstallIdentity(manifest, oldName, newName)
        suffixOwnedNames(manifest, newName, suffix)
        suffixAuthorities(manifest, suffix)
        manifest.manifestElement?.searchAttributeByName("sharedUserId")?.removeSelf()
        manifest.setPackageName(newName)
        changes.add("Renamed the package from $oldName to $newName so it can be installed next to the original.")
    }

    private fun expandBareComponentNames(manifest: AndroidManifestBlock, oldName: String) {
        val application = manifest.applicationElement ?: return
        val tags = setOf("application", "activity", "activity-alias", "service", "receiver", "provider")
        val elements = ArrayList<ResXmlElement>()
        elements.add(application)
        val iterator = application.recursiveElements()
        while (iterator.hasNext()) {
            val element = iterator.next() as? ResXmlElement ?: continue
            elements.add(element)
        }
        for (element in elements) {
            if (tags.none { element.equalsName(it) }) continue
            val attribute = findAttr(element, AndroidManifest.ID_name, AndroidManifest.NAME_name) ?: continue
            if (attribute.valueType != ValueType.STRING) continue
            val value = attribute.valueAsString ?: continue
            if (value.isEmpty() || value.contains('.')) continue
            attribute.setValueAsString("$oldName.$value")
        }
    }

    private fun rewriteInstallIdentity(manifest: AndroidManifestBlock, oldName: String, newName: String) {
        val permissionTags = setOf("permission", "uses-permission", "uses-permission-sdk-23", "permission-tree", "permission-group")
        val identityAttributes = arrayOf("authorities", "permission", "readPermission", "writePermission", "targetPackage", "taskAffinity", "process")
        val elements = ArrayList<ResXmlElement>()
        val iterator = manifest.recursiveElements()
        while (iterator.hasNext()) {
            val element = iterator.next() as? ResXmlElement ?: continue
            elements.add(element)
        }
        for (element in elements) {
            if (permissionTags.any { element.equalsName(it) }) {
                val name = findAttr(element, AndroidManifest.ID_name, AndroidManifest.NAME_name)
                if (name != null && name.valueType == ValueType.STRING) {
                    val value = name.valueAsString
                    if (!value.isNullOrEmpty()) name.setValueAsString(PackageNames.replacePrefix(value, oldName, newName))
                }
            }
            for (attributeName in identityAttributes) {
                val attribute = element.searchAttributeByName(attributeName) ?: continue
                if (attribute.valueType != ValueType.STRING) continue
                val value = attribute.valueAsString ?: continue
                attribute.setValueAsString(PackageNames.replaceList(value, oldName, newName))
            }
        }
    }

    private fun suffixOwnedNames(manifest: AndroidManifestBlock, newName: String, suffix: String) {
        val permissionTags = setOf("permission", "permission-tree", "permission-group")
        val declared = ArrayList<String>()
        val elements = ArrayList<ResXmlElement>()
        val iterator = manifest.recursiveElements()
        while (iterator.hasNext()) {
            val element = iterator.next() as? ResXmlElement ?: continue
            elements.add(element)
        }
        for (element in elements) {
            if (permissionTags.none { element.equalsName(it) }) continue
            val name = findAttr(element, AndroidManifest.ID_name, AndroidManifest.NAME_name) ?: continue
            if (name.valueType != ValueType.STRING) continue
            val value = name.valueAsString ?: continue
            val owned = !value.startsWith("android.") && value != newName && !value.startsWith("$newName.") && !value.endsWith(suffix)
            if (owned) declared.add(value)
        }
        if (declared.isEmpty()) return
        for (value in declared.distinct().sortedByDescending { it.length }) {
            replaceQuoted(elements, value, value + suffix)
        }
    }

    private fun suffixAuthorities(manifest: AndroidManifestBlock, suffix: String) {
        val elements = ArrayList<ResXmlElement>()
        val iterator = manifest.recursiveElements()
        while (iterator.hasNext()) {
            val element = iterator.next() as? ResXmlElement ?: continue
            elements.add(element)
        }
        for (element in elements) {
            val attribute = element.searchAttributeByName("authorities") ?: continue
            if (attribute.valueType != ValueType.STRING) continue
            val value = attribute.valueAsString ?: continue
            val next = value.split(';').joinToString(";") { part ->
                val core = part.trim()
                if (core.isEmpty() || core.endsWith(suffix) || core.contains("$suffix.")) part
                else part.replace(core, core + suffix)
            }
            if (next != value) attribute.setValueAsString(next)
        }
    }

    private fun replaceQuoted(elements: List<ResXmlElement>, from: String, to: String) {
        for (element in elements) {
            val count = element.attributeCount
            for (index in 0 until count) {
                val attribute = element.getAttributeAt(index) ?: continue
                if (attribute.valueType != ValueType.STRING) continue
                val value = attribute.valueAsString ?: continue
                if (value == from) attribute.setValueAsString(to)
                else if (value.contains(from)) {
                    attribute.setValueAsString(value.split(';').joinToString(";") { part ->
                        if (part.trim() == from) part.replace(from, to) else part
                    })
                }
            }
        }
    }

    private fun declareGame(application: ResXmlElement, changes: MutableList<String>) {
        val previous = findAttr(application, ID_APP_CATEGORY, "appCategory")
        val previousGame = findAttr(application, ID_IS_GAME, "isGame")
        val previousName = categoryName(previous)
        val already = previousName == "game" && previousGame != null && isTrue(previousGame)
        val category = application.getOrCreateAndroidAttribute("appCategory", ID_APP_CATEGORY)
        category.setTypeAndData(ValueType.DEC, 0)
        val game = application.getOrCreateAndroidAttribute("isGame", ID_IS_GAME)
        game.setValueAsBoolean(true)
        if (already) {
            changes.add("The app was already declared as a game.")
        } else if (previousName != null && previousName != "game") {
            changes.add("Changed the app category from $previousName to game so Android Auto can show it while parked.")
        } else {
            changes.add("Declared the app as a game so Android Auto can show its screen while parked.")
        }
    }

    private fun keepKeyboard(application: ResXmlElement) {
        for (activity in elementsNamed(application, "activity")) {
            val attr = findAttr(activity, ID_CONFIG_CHANGES, "configChanges")
            if (attr != null && attr.valueType != ValueType.DEC && attr.valueType != ValueType.HEX) continue
            val current = attr?.data ?: 0
            val next = current or CONFIG_KEYBOARD or CONFIG_KEYBOARD_HIDDEN
            if (attr != null && next == current) continue
            activity.getOrCreateAndroidAttribute("configChanges", ID_CONFIG_CHANGES)
                .setTypeAndData(ValueType.HEX, next)
        }
    }

    private fun ensureKeyboardProvider(
        manifest: AndroidManifestBlock,
        application: ResXmlElement,
        changes: MutableList<String>,
    ) {
        val present = elementsNamed(application, "provider").any { element ->
            AndroidManifestBlock.getAndroidNameValue(element)?.endsWith("KeyboardStay") == true
        }
        if (present) return
        val packageName = manifest.packageName?.trim().orEmpty().ifBlank { "com.automaker.game" }
        val provider = application.newElement("provider")
        provider.getOrCreateAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name)
            .setValueAsString("com.automaker.game.KeyboardStay")
        provider.getOrCreateAndroidAttribute("authorities", 0x01010018)
            .setValueAsString("$packageName.automaker.keyboard")
        provider.getOrCreateAndroidAttribute(AndroidManifest.NAME_exported, AndroidManifest.ID_exported)
            .setValueAsBoolean(false)
        provider.getOrCreateAndroidAttribute("enabled", 0x0101000e).setValueAsBoolean(true)
        changes.add("Kept the keyboard open when a letter is typed.")
    }

    private fun declareMaps(application: ResXmlElement, changes: MutableList<String>) {
        val previous = findAttr(application, ID_APP_CATEGORY, "appCategory")
        val previousName = categoryName(previous)
        val game = findAttr(application, ID_IS_GAME, "isGame")
        val already = previousName == "maps" && (game == null || isFalse(game))
        application.getOrCreateAndroidAttribute("appCategory", ID_APP_CATEGORY).setTypeAndData(ValueType.DEC, 6)
        application.getOrCreateAndroidAttribute("isGame", ID_IS_GAME).setValueAsBoolean(false)
        if (already) {
            changes.add("The app was already declared as maps.")
        } else if (previousName != null && previousName != "maps") {
            changes.add("Changed the app category from $previousName to maps so Android Auto can keep it open while driving.")
        } else {
            changes.add("Declared the app as maps so Android Auto can keep it open while driving.")
        }
    }

    private fun ensureAutomotiveFeature(manifest: AndroidManifestBlock, changes: MutableList<String>) {
        ensureOptionalFeature(
            manifest,
            AUTOMOTIVE_FEATURE,
            changes,
            already = "Automotive hardware was already optional, so the APK still installs on a phone.",
            marked = "Marked automotive hardware as optional so the APK still installs on a phone.",
            added = "Declared optional automotive hardware so the same APK installs on a phone and in the car.",
        )
    }

    private fun ensureGamepadFeature(manifest: AndroidManifestBlock, changes: MutableList<String>) {
        ensureOptionalFeature(
            manifest,
            GAMEPAD_FEATURE,
            changes,
            already = "Game controller support was already declared.",
            marked = "Marked game controller support as optional.",
            added = "Added game controller support.",
        )
    }

    private fun ensureOptionalFeature(
        manifest: AndroidManifestBlock,
        featureName: String,
        changes: MutableList<String>,
        already: String,
        marked: String,
        added: String,
    ) {
        val root = manifest.manifestElement
            ?: throw IllegalStateException("AndroidManifest.xml has no <manifest> element.")
        val existing = elementsNamed(root, AndroidManifest.TAG_uses_feature).firstOrNull { element ->
            AndroidManifestBlock.getAndroidNameValue(element) == featureName
        }
        if (existing != null) {
            val required = findAttr(existing, ID_REQUIRED, "required")
            if (required != null && isFalse(required)) {
                changes.add(already)
                return
            }
            existing.getOrCreateAndroidAttribute("required", ID_REQUIRED).setValueAsBoolean(false)
            changes.add(marked)
            return
        }
        val application = root.getElement(AndroidManifest.TAG_application)
        val feature = if (application != null) {
            root.newElementAt(application.index, AndroidManifest.TAG_uses_feature)
        } else {
            root.newElement(AndroidManifest.TAG_uses_feature)
        }
        feature.getOrCreateAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name)
            .setValueAsString(featureName)
        feature.getOrCreateAndroidAttribute("required", ID_REQUIRED).setValueAsBoolean(false)
        changes.add(added)
    }

    private fun removeNavigationHost(manifest: AndroidManifestBlock) {
        val root = manifest.manifestElement ?: return
        val permissions = elementsNamed(root, "uses-permission").filter { element ->
            val name = AndroidManifestBlock.getAndroidNameValue(element)
            name == "androidx.car.app.NAVIGATION_TEMPLATES" || name == "androidx.car.app.ACCESS_SURFACE"
        }
        for (element in permissions) element.parentElement?.removeElementsIf { it === element }
        removeMeta(manifest, MIN_CAR_API, ArrayList(), null)
        removeMeta(manifest, GMS_CAR, ArrayList(), null)
        val application = manifest.applicationElement ?: return
        val services = elementsNamed(application, "service").filter { element ->
            val name = AndroidManifestBlock.getAndroidNameValue(element)
            name?.endsWith("NavigationCarService") == true || name?.endsWith("TouchBridge") == true
        }
        for (element in services) element.parentElement?.removeElementsIf { it === element }
        val trampolines = elementsNamed(application, "activity").filter { element ->
            AndroidManifestBlock.getAndroidNameValue(element)?.endsWith("NavigationActivity") == true
        }
        for (element in trampolines) element.parentElement?.removeElementsIf { it === element }
    }

    private fun removeKeyboardProvider(application: ResXmlElement) {
        val keyboards = elementsNamed(application, "provider").filter { element ->
            AndroidManifestBlock.getAndroidNameValue(element)?.endsWith("KeyboardStay") == true
        }
        for (element in keyboards) element.parentElement?.removeElementsIf { it === element }
    }

    private fun clearGameDeclaration(application: ResXmlElement, changes: MutableList<String>) {
        val category = findAttr(application, ID_APP_CATEGORY, "appCategory")
        val game = findAttr(application, ID_IS_GAME, "isGame")
        val wasGame = categoryName(category) == "game" || (game != null && isTrue(game))
        if (categoryName(category) == "game") application.removeAttributesWithName("appCategory")
        if (game != null && isTrue(game)) application.removeAttributesWithName("isGame")
        changes.add(
            if (wasGame) {
                "Removed the game declaration so Android Auto can list it as navigation."
            } else {
                "Left the app undeclared as a game, the same way FS Play is declared."
            },
        )
    }

    private fun ensurePermission(
        manifest: AndroidManifestBlock,
        name: String,
        changes: MutableList<String>,
        message: String,
    ) {
        val root = manifest.manifestElement ?: return
        val present = elementsNamed(root, "uses-permission").any { element ->
            AndroidManifestBlock.getAndroidNameValue(element) == name
        }
        if (present) return
        root.newElement("uses-permission")
            .getOrCreateAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name)
            .setValueAsString(name)
        changes.add(message)
    }

    private fun ensureShizuku(
        manifest: AndroidManifestBlock,
        application: ResXmlElement,
        changes: MutableList<String>,
    ) {
        ensurePermission(
            manifest,
            "moe.shizuku.manager.permission.API_V23",
            changes,
            "Added the Shizuku permission for the patched app.",
        )
        val packageName = manifest.packageName?.trim().orEmpty()
        if (packageName.isEmpty()) return
        val present = elementsNamed(application, "provider").any { element ->
            AndroidManifestBlock.getAndroidNameValue(element) == "rikka.shizuku.ShizukuProvider"
        }
        if (!present) {
            val provider = application.newElement("provider")
            provider.getOrCreateAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name)
                .setValueAsString("rikka.shizuku.ShizukuProvider")
            provider.getOrCreateAndroidAttribute("authorities", 0x01010018)
                .setValueAsString("$packageName.shizuku")
            provider.getOrCreateAndroidAttribute(AndroidManifest.NAME_exported, AndroidManifest.ID_exported)
                .setValueAsBoolean(true)
            provider.getOrCreateAndroidAttribute("enabled", 0x0101000e).setValueAsBoolean(true)
            provider.getOrCreateAndroidAttribute("multiprocess", 0x01010013).setValueAsBoolean(false)
            provider.getOrCreateAndroidAttribute("permission", 0x01010006)
                .setValueAsString("android.permission.INTERACT_ACROSS_USERS_FULL")
            changes.add("Added Shizuku so the patched app can open its screen.")
        }
        val support = elementsNamed(application, AndroidManifest.TAG_meta_data).any { element ->
            AndroidManifestBlock.getAndroidNameValue(element) == "moe.shizuku.client.V3_SUPPORT"
        }
        if (support) return
        val meta = application.newElement(AndroidManifest.TAG_meta_data)
        meta.getOrCreateAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name)
            .setValueAsString("moe.shizuku.client.V3_SUPPORT")
        meta.getOrCreateAndroidAttribute(AndroidManifest.NAME_value, AndroidManifest.ID_value)
            .setValueAsString("true")
    }

    private fun ensureMinCarApi(parent: ResXmlElement, changes: MutableList<String>, message: String) {
        val existing = elementsNamed(parent, AndroidManifest.TAG_meta_data).firstOrNull { element ->
            AndroidManifestBlock.getAndroidNameValue(element) == MIN_CAR_API
        }
        val meta = existing ?: parent.newElement(AndroidManifest.TAG_meta_data).also { created ->
            created.getOrCreateAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name)
                .setValueAsString(MIN_CAR_API)
        }
        val value = findAttr(meta, AndroidManifest.ID_value, AndroidManifest.NAME_value)
        if (value != null && value.valueType == ValueType.DEC && value.data == 5) return
        meta.getOrCreateAndroidAttribute(AndroidManifest.NAME_value, AndroidManifest.ID_value)
            .setTypeAndData(ValueType.DEC, 5)
        changes.add(message)
    }

    private fun ensureNavigationService(
        manifest: AndroidManifestBlock,
        application: ResXmlElement,
        changes: MutableList<String>,
    ) {
        val serviceName = "com.automaker.nav.NavigationCarService"
        for (service in elementsNamed(application, "service")) {
            val name = AndroidManifestBlock.getAndroidNameValue(service)
            if (name != null && name.endsWith("NavigationCarService") && name != serviceName) {
                service.getOrCreateAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name)
                    .setValueAsString(serviceName)
            }
        }
        val existing = elementsNamed(application, "service").firstOrNull { service ->
            elementsNamed(service, AndroidManifest.TAG_intent_filter).any { filter ->
                hasNamedChild(filter, AndroidManifest.TAG_action, CAR_APP_SERVICE) &&
                    hasNamedChild(filter, AndroidManifest.TAG_category, CAR_NAVIGATION)
            }
        }
        if (existing != null) {
            ensureMinCarApi(existing, changes, "Set the navigation service car API level to 5.")
            for (filter in elementsNamed(existing, AndroidManifest.TAG_intent_filter)) allowHostBind(filter)
            changes.add("The navigation car service was already present.")
            return
        }
        val service = application.newElement("service")
        service.getOrCreateAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name)
            .setValueAsString(serviceName)
        service.getOrCreateAndroidAttribute(AndroidManifest.NAME_exported, AndroidManifest.ID_exported)
            .setValueAsBoolean(true)
        ensureMinCarApi(service, changes, "Added a navigation car service like FS Play.")
        val filter = service.newElement(AndroidManifest.TAG_intent_filter)
        allowHostBind(filter)
        filter.newElement(AndroidManifest.TAG_action)
            .getOrCreateAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name)
            .setValueAsString(CAR_APP_SERVICE)
        addCategory(filter, CAR_NAVIGATION)
    }

    private fun ensureTouchBridge(apk: ApkModule, application: ResXmlElement, changes: MutableList<String>) {
        val serviceName = "com.automaker.nav.TouchBridge"
        val present = elementsNamed(application, "service").any { element ->
            AndroidManifestBlock.getAndroidNameValue(element) == serviceName
        }
        if (present) return
        val service = application.newElement("service")
        service.getOrCreateAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name)
            .setValueAsString(serviceName)
        service.getOrCreateAndroidAttribute(AndroidManifest.NAME_exported, AndroidManifest.ID_exported)
            .setValueAsBoolean(true)
        service.getOrCreateAndroidAttribute("permission", 0x01010006)
            .setValueAsString("android.permission.BIND_ACCESSIBILITY_SERVICE")
        service.getOrCreateAndroidAttribute("isAccessibilityTool", 0x01010641)
            .setValueAsBoolean(true)
        service.newElement(AndroidManifest.TAG_intent_filter)
            .newElement(AndroidManifest.TAG_action)
            .getOrCreateAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name)
            .setValueAsString("android.accessibilityservice.AccessibilityService")
        val meta = service.newElement(AndroidManifest.TAG_meta_data)
        meta.getOrCreateAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name)
            .setValueAsString("android.accessibilityservice")
        meta.getOrCreateAndroidAttribute(AndroidManifest.NAME_resource, AndroidManifest.ID_resource)
            .setTypeAndData(ValueType.REFERENCE, touchBridgeResourceId(apk))
        changes.add("Added touch forwarding for the car screen.")
    }

    private fun touchBridgeResourceId(apk: ApkModule): Int {
        val entry = appPackage(apk).getOrCreate("", "xml", "automaker_touch_bridge")
        entry.setValueAsString(TOUCH_BRIDGE_XML)
        val document = ResXmlDocument()
        val description = appPackage(apk).getOrCreate("", "string", "automaker_touch_bridge_description")
        description.setValueAsString("Forwards touches from the car screen.")
        val root = document.getOrCreateElement("accessibility-service")
        root.getOrCreateAndroidAttribute("description", 0x01010020)
            .setTypeAndData(ValueType.REFERENCE, description.resourceId)
        root.getOrCreateAndroidAttribute("accessibilityEventTypes", 0x01010380)
            .setTypeAndData(ValueType.HEX, -1)
        root.getOrCreateAndroidAttribute("accessibilityFeedbackType", 0x01010382)
            .setTypeAndData(ValueType.HEX, 0x10)
        root.getOrCreateAndroidAttribute("accessibilityFlags", 0x01010384)
            .setTypeAndData(ValueType.HEX, 0x4052)
        root.getOrCreateAndroidAttribute("notificationTimeout", 0x01010383)
            .setTypeAndData(ValueType.DEC, 0)
        root.getOrCreateAndroidAttribute("canRetrieveWindowContent", 0x01010385)
            .setValueAsBoolean(true)
        root.getOrCreateAndroidAttribute("canPerformGestures", 0x0101050d)
            .setValueAsBoolean(true)
        root.getOrCreateAndroidAttribute("canRequestTouchExplorationMode", 0x010103d7)
            .setValueAsBoolean(true)
        document.refreshFull()
        apk.add(ByteInputSource(document.bytes, TOUCH_BRIDGE_XML))
        if (entry.resourceId == 0) {
            throw IllegalStateException("The touch bridge config could not be added to the package.")
        }
        return entry.resourceId
    }

    private fun allowHostBind(filter: ResXmlElement) {
        filter.getOrCreateAndroidAttribute("intentMatchingFlags", 0x010106a9)
            .setTypeAndData(ValueType.HEX, 4)
    }

    private fun addCategory(filter: ResXmlElement, name: String) {
        filter.newElement(AndroidManifest.TAG_category)
            .getOrCreateAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name)
            .setValueAsString(name)
    }

    private fun ensureDistractionOptimized(manifest: AndroidManifestBlock, changes: MutableList<String>) {
        var changed = false
        for (activity in activities(manifest)) {
            if (!activityContains(activity, CAR_LAUNCHER)) continue
            val existing = elementsNamed(activity, AndroidManifest.TAG_meta_data).firstOrNull { element ->
                AndroidManifestBlock.getAndroidNameValue(element) == DISTRACTION
            }
            if (existing == null) {
                val meta = activity.newElement(AndroidManifest.TAG_meta_data)
                meta.getOrCreateAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name)
                    .setValueAsString(DISTRACTION)
                meta.getOrCreateAndroidAttribute(AndroidManifest.NAME_value, AndroidManifest.ID_value)
                    .setValueAsString("true")
                changed = true
                continue
            }
            val value = findAttr(existing, AndroidManifest.ID_value, AndroidManifest.NAME_value)
            if (value == null || value.valueAsString != "true") {
                existing.getOrCreateAndroidAttribute(AndroidManifest.NAME_value, AndroidManifest.ID_value)
                    .setValueAsString("true")
                changed = true
            }
        }
        if (changed) {
            changes.add("Marked the car activity distraction-optimized so it can stay open while the car is moving.")
        } else {
            changes.add("The car activity was already distraction-optimized.")
        }
    }

    private fun ensureNavigationDescriptor(apk: ApkModule, application: ResXmlElement, changes: MutableList<String>) {
        val resourceId = navigationResourceId(apk)
        val existing = elementsNamed(application, AndroidManifest.TAG_meta_data).firstOrNull { element ->
            AndroidManifestBlock.getAndroidNameValue(element) == GMS_CAR
        }
        if (existing == null) {
            val meta = application.newElement(AndroidManifest.TAG_meta_data)
            meta.getOrCreateAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name)
                .setValueAsString(GMS_CAR)
            meta.getOrCreateAndroidAttribute(AndroidManifest.NAME_resource, AndroidManifest.ID_resource)
                .setTypeAndData(ValueType.REFERENCE, resourceId)
            changes.add("Added Android Auto navigation metadata so the car can offer it while driving.")
            return
        }
        val resource = findAttr(existing, AndroidManifest.ID_resource, AndroidManifest.NAME_resource)
        if (resource == null || resource.valueType != ValueType.REFERENCE || resource.data != resourceId) {
            existing.getOrCreateAndroidAttribute(AndroidManifest.NAME_resource, AndroidManifest.ID_resource)
                .setTypeAndData(ValueType.REFERENCE, resourceId)
            changes.add("Added Android Auto navigation metadata so the car can offer it while driving.")
        } else {
            changes.add("Android Auto navigation metadata was already present.")
        }
    }

    fun carLibraryResourceId(apk: ApkModule, type: String, name: String): Int {
        return appPackage(apk).getEntry("", type, name)?.resourceId ?: 0
    }

    private fun ensureCarLibraryResources(apk: ApkModule) {
        ensureText(apk, "car_app_library_version", "1.7.0")
        ensureDec(apk, "content_limit_grid", 6)
        ensureDec(apk, "content_limit_pane", 4)
        ensureDec(apk, "content_limit_place_list", 6)
        ensureDec(apk, "content_limit_route_list", 3)
        ensureDec(apk, "content_limit_list", 6)
    }

    private fun ensureText(apk: ApkModule, name: String, value: String) {
        val entry = appPackage(apk).getOrCreate("", "string", name)
        entry.setValueAsString(value)
    }

    private fun ensureDec(apk: ApkModule, name: String, value: Int) {
        val entry = appPackage(apk).getOrCreate("", "integer", name)
        val current = runCatching { entry.valueAsInteger }.getOrNull()
        if (current == null) entry.setValueAsRaw(ValueType.DEC, value)
    }

    private fun navigationResourceId(apk: ApkModule): Int {
        val entry = appPackage(apk).getOrCreate("", "xml", "automotive_app_desc")
        entry.setValueAsString(NAVIGATION_XML)
        val document = ResXmlDocument()
        val current = apk.getInputSource(NAVIGATION_XML)
        if (current != null) {
            try {
                current.openStream().use { document.readBytes(BlockReader(it)) }
            } catch (_: Exception) {
            }
        }
        val root = document.getOrCreateElement("automotiveApp")
        for (element in elementsNamed(root, "uses")) {
            val name = element.searchAttributeByName("name")?.valueAsString
            if (name !in NAVIGATION_USES) element.parentElement?.removeElementsIf { it === element }
        }
        val present = elementsNamed(root, "uses").mapNotNull { element ->
            element.searchAttributeByName("name")?.valueAsString
        }.toSet()
        for (name in NAVIGATION_USES) {
            if (name in present) continue
            root.newElement("uses").getOrCreateAttribute("name", 0).setValueAsString(name)
        }
        document.refreshFull()
        apk.add(ByteInputSource(document.bytes, NAVIGATION_XML))
        if (entry.resourceId == 0) {
            throw IllegalStateException("The navigation descriptor could not be added to the package.")
        }
        return entry.resourceId
    }

    private fun appPackage(apk: ApkModule): PackageBlock {
        apk.ensureTableBlock()
        val table = apk.tableBlock
        val iterator = table.listPackages().iterator()
        if (iterator.hasNext()) return iterator.next()
        return table.newPackage(0x7f, "app")
    }

    private fun separateLauncherFromCar(manifest: AndroidManifestBlock) {
        for (activity in activities(manifest)) {
            if (AndroidManifestBlock.getAndroidNameValue(activity) == NAV_ACTIVITY) continue
            for (filter in elementsNamed(activity, AndroidManifest.TAG_intent_filter)) {
                if (!hasNamedChild(filter, AndroidManifest.TAG_category, LAUNCHER)) continue
                val categories = elementsNamed(filter, AndroidManifest.TAG_category).filter { element ->
                    val name = AndroidManifestBlock.getAndroidNameValue(element)
                    name == CAR_LAUNCHER || name == APP_MAPS || name == CAR_NAVIGATION
                }
                for (element in categories) element.parentElement?.removeElementsIf { it === element }
            }
            if (!activityContains(activity, LAUNCHER)) continue
            val metas = elementsNamed(activity, AndroidManifest.TAG_meta_data).filter { element ->
                AndroidManifestBlock.getAndroidNameValue(element) == DISTRACTION
            }
            for (element in metas) element.parentElement?.removeElementsIf { it === element }
            val attribute = activity.searchAttributeByName(DISTRACTION)
            if (attribute != null && isTrue(attribute)) activity.removeAttribute(attribute)
        }
    }

    private fun ensureNavigationActivity(application: ResXmlElement, changes: MutableList<String>) {
        val existing = elementsNamed(application, "activity").any { element ->
            AndroidManifestBlock.getAndroidNameValue(element) == NAV_ACTIVITY
        }
        if (existing) {
            changes.add("The navigation activity was already present.")
            return
        }
        val activity = application.newElement("activity")
        activity.getOrCreateAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name)
            .setValueAsString(NAV_ACTIVITY)
        activity.getOrCreateAndroidAttribute(AndroidManifest.NAME_exported, AndroidManifest.ID_exported)
            .setValueAsBoolean(true)
        val filter = activity.newElement(AndroidManifest.TAG_intent_filter)
        filter.newElement(AndroidManifest.TAG_action)
            .getOrCreateAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name)
            .setValueAsString(MAIN)
        addCategory(filter, CAR_LAUNCHER)
        addCategory(filter, CAR_NAVIGATION)
        addCategory(filter, APP_MAPS)
        val meta = activity.newElement(AndroidManifest.TAG_meta_data)
        meta.getOrCreateAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name)
            .setValueAsString(DISTRACTION)
        meta.getOrCreateAndroidAttribute(AndroidManifest.NAME_value, AndroidManifest.ID_value)
            .setValueAsString("true")
        changes.add("Added a navigation activity that opens the app's main screen.")
    }

    private fun allowOnVirtualDisplay(application: ResXmlElement) {
        for (activity in elementsNamed(application, "activity")) {
            activity.getOrCreateAndroidAttribute("resizeableActivity", 0x010104f6).setValueAsBoolean(true)
            activity.getOrCreateAndroidAttribute("allowEmbedded", 0x010103f5).setValueAsBoolean(true)
            activity.getOrCreateAndroidAttribute("canDisplayOnRemoteDevices", 0x01010650).setValueAsBoolean(true)
        }
    }

    private fun ensureCarEntry(application: ResXmlElement) {
        val existing = elementsNamed(application, "activity").any { element ->
            AndroidManifestBlock.getAndroidNameValue(element) == "android.app.Activity"
        }
        if (existing) return
        val activity = application.newElement("activity")
        activity.getOrCreateAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name)
            .setValueAsString("android.app.Activity")
        activity.getOrCreateAndroidAttribute(AndroidManifest.NAME_exported, AndroidManifest.ID_exported)
            .setValueAsBoolean(true)
        val filter = activity.newElement(AndroidManifest.TAG_intent_filter)
        filter.newElement(AndroidManifest.TAG_action)
            .getOrCreateAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name)
            .setValueAsString(MAIN)
        addCategory(filter, CAR_LAUNCHER)
        addCategory(filter, CAR_NAVIGATION)
        addCategory(filter, APP_MAPS)
        val meta = activity.newElement(AndroidManifest.TAG_meta_data)
        meta.getOrCreateAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name)
            .setValueAsString(DISTRACTION)
        meta.getOrCreateAndroidAttribute(AndroidManifest.NAME_value, AndroidManifest.ID_value)
            .setValueAsString("true")
    }

    private fun ensureCarLauncher(manifest: AndroidManifestBlock, changes: MutableList<String>, navigation: Boolean) {
        var added = 0
        var addedMaps = 0
        for (activity in activities(manifest)) {
            for (filter in elementsNamed(activity, AndroidManifest.TAG_intent_filter)) {
                if (!hasNamedChild(filter, AndroidManifest.TAG_category, LAUNCHER)) continue
                if (!hasNamedChild(filter, AndroidManifest.TAG_category, CAR_LAUNCHER)) {
                    filter.newElement(AndroidManifest.TAG_category)
                        .getOrCreateAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name)
                        .setValueAsString(CAR_LAUNCHER)
                    added += 1
                }
                if (navigation && !hasNamedChild(filter, AndroidManifest.TAG_category, APP_MAPS)) {
                    addCategory(filter, APP_MAPS)
                    addedMaps += 1
                }
                if (navigation && !hasNamedChild(filter, AndroidManifest.TAG_category, CAR_NAVIGATION)) {
                    addCategory(filter, CAR_NAVIGATION)
                    addedMaps += 1
                }
            }
        }
        if (added > 0) {
            changes.add(
                if (added == 1) {
                    "Added the Android Auto car launcher category to the main activity."
                } else {
                    "Added the Android Auto car launcher category to $added activities."
                },
            )
        } else if (activities(manifest).any { activityContains(it, CAR_LAUNCHER) }) {
            changes.add("The Android Auto car launcher category was already present.")
        } else {
            val first = activities(manifest).firstOrNull { element ->
                !AndroidManifestBlock.getAndroidNameValue(element).orEmpty().startsWith("com.google.android.play.core.")
            } ?: throw IllegalStateException("AndroidManifest.xml has no activity Android Auto can open.")
            val filter = first.newElement(AndroidManifest.TAG_intent_filter)
            filter.newElement(AndroidManifest.TAG_action)
                .getOrCreateAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name)
                .setValueAsString(MAIN)
            filter.newElement(AndroidManifest.TAG_category)
                .getOrCreateAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name)
                .setValueAsString(LAUNCHER)
            filter.newElement(AndroidManifest.TAG_category)
                .getOrCreateAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name)
                .setValueAsString(CAR_LAUNCHER)
            if (navigation) {
                addCategory(filter, APP_MAPS)
                addCategory(filter, CAR_NAVIGATION)
                addedMaps += 2
            }
            changes.add("Added an Android Auto car launcher filter to the first activity.")
        }
        if (navigation) {
            changes.add(
                if (addedMaps > 0) {
                    "Added the navigation categories FS Play uses on its car activity."
                } else {
                    "The navigation categories were already present."
                },
            )
        }
    }

    private fun ensureExported(manifest: AndroidManifestBlock, changes: MutableList<String>) {
        var changed = false
        for (activity in activities(manifest)) {
            if (!activityContains(activity, CAR_LAUNCHER)) continue
            val exported = findAttr(activity, AndroidManifest.ID_exported, AndroidManifest.NAME_exported)
            if (exported != null && isTrue(exported)) continue
            activity.getOrCreateAndroidAttribute(AndroidManifest.NAME_exported, AndroidManifest.ID_exported)
                .setValueAsBoolean(true)
            changed = true
        }
        if (changed) changes.add("Marked the car activity exported so Android Auto can start it.")
    }

    private fun removeInternalStamps(manifest: AndroidManifestBlock): Boolean {
        var removed = removeMeta(manifest, DERIVED_APK_ID, ArrayList(), null)
        for (name in INTERNAL_META) {
            if (removeMeta(manifest, name, ArrayList(), null)) removed = true
        }
        for (element in listOfNotNull(manifest.manifestElement, manifest.applicationElement)) {
            removed = removeAttribute(element, AndroidManifest.ID_requiredSplitTypes, AndroidManifest.NAME_requiredSplitTypes) || removed
            removed = removeAttribute(element, AndroidManifest.ID_splitTypes, AndroidManifest.NAME_splitTypes) || removed
            removed = removeAttribute(element, AndroidManifest.ID_isSplitRequired, AndroidManifest.NAME_isSplitRequired) || removed
            val isolated = element.searchAttributeByName("isolatedSplits")
            if (isolated != null) {
                element.removeAttribute(isolated)
                removed = true
            }
        }
        return removed
    }

    private fun markStandalone(application: ResXmlElement): Boolean {
        var changed = setMetaValue(application, "com.android.stamp.type", "STAMP_TYPE_STANDALONE_APK")
        val fused = findMeta(application, "com.android.dynamic.apk.fused.modules")
        if (fused != null) {
            val value = findAttr(fused, AndroidManifest.ID_value, "value")?.valueAsString.orEmpty()
            val parts = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            if (!parts.contains("base")) {
                parts.add(0, "base")
                setMetaValue(application, "com.android.dynamic.apk.fused.modules", parts.joinToString(","))
                changed = true
            }
        }
        return changed
    }

    private fun findMeta(application: ResXmlElement, name: String): ResXmlElement? {
        val iterator = application.recursiveElements()
        while (iterator.hasNext()) {
            val element = iterator.next() as? ResXmlElement ?: continue
            if (!element.equalsName(AndroidManifest.TAG_meta_data)) continue
            if (AndroidManifestBlock.getAndroidNameValue(element) == name) return element
        }
        return null
    }

    private fun setMetaValue(application: ResXmlElement, name: String, value: String): Boolean {
        val existing = findMeta(application, name)
        val meta = existing ?: application.newElement(AndroidManifest.TAG_meta_data)
        if (existing == null) {
            meta.getOrCreateAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name).setValueAsString(name)
        }
        val attribute = meta.getOrCreateAndroidAttribute("value", AndroidManifest.ID_value)
        val changed = existing == null || attribute.valueAsString != value
        attribute.setValueAsString(value)
        return changed
    }

    private fun removeAttribute(element: ResXmlElement, id: Int, name: String): Boolean {
        val attribute = element.searchAttributeByResourceId(id) ?: element.searchAttributeByName(name) ?: return false
        element.removeAttribute(attribute)
        return true
    }

    private fun removeMeta(
        manifest: AndroidManifestBlock,
        name: String,
        changes: MutableList<String>,
        message: String?,
    ): Boolean {
        val found = ArrayList<ResXmlElement>()
        val iterator = manifest.recursiveElements()
        while (iterator.hasNext()) {
            val element = iterator.next() as? ResXmlElement ?: continue
            if (element.equalsName(AndroidManifest.TAG_meta_data) && AndroidManifestBlock.getAndroidNameValue(element) == name) {
                found.add(element)
            }
        }
        for (element in found) {
            element.parentElement?.removeElementsIf { it === element }
        }
        if (found.isNotEmpty() && message != null) changes.add(message)
        return found.isNotEmpty()
    }

    private fun removeDistractionAttributes(manifest: AndroidManifestBlock): Boolean {
        var removed = false
        val elements = ArrayList<ResXmlElement>()
        val iterator = manifest.recursiveElements()
        while (iterator.hasNext()) {
            val element = iterator.next() as? ResXmlElement ?: continue
            elements.add(element)
        }
        for (element in elements) {
            val attribute = element.searchAttributeByName(DISTRACTION) ?: continue
            if (!isTrue(attribute)) continue
            element.removeAttribute(attribute)
            removed = true
        }
        return removed
    }

    private fun activities(manifest: AndroidManifestBlock): List<ResXmlElement> {
        val list = ArrayList<ResXmlElement>()
        val iterator = manifest.getActivities(true)
        while (iterator.hasNext()) {
            val element = iterator.next() as? ResXmlElement ?: continue
            list.add(element)
        }
        return list
    }

    private fun elementsNamed(parent: ResXmlElement, name: String): List<ResXmlElement> {
        val list = ArrayList<ResXmlElement>()
        val iterator = parent.getElements(name)
        while (iterator.hasNext()) {
            val element = iterator.next() as? ResXmlElement ?: continue
            list.add(element)
        }
        return list
    }

    private fun hasNamedChild(parent: ResXmlElement, tag: String, value: String): Boolean {
        return elementsNamed(parent, tag).any { AndroidManifestBlock.getAndroidNameValue(it) == value }
    }

    private fun activityContains(activity: ResXmlElement, value: String): Boolean {
        return elementsNamed(activity, AndroidManifest.TAG_intent_filter).any { filter ->
            hasNamedChild(filter, AndroidManifest.TAG_category, value)
        }
    }

    private fun findAttr(element: ResXmlElement, id: Int, name: String) =
        element.searchAttributeByResourceId(id) ?: element.searchAttributeByName(name)

    private fun categoryName(attribute: com.reandroid.arsc.chunk.xml.ResXmlAttribute?): String? {
        if (attribute == null) return null
        if (attribute.valueType == ValueType.STRING) return attribute.valueAsString
        val index = attribute.data
        return CATEGORIES.getOrNull(index)
    }

    private fun isTrue(attribute: com.reandroid.arsc.chunk.xml.ResXmlAttribute): Boolean {
        return when (attribute.valueType) {
            ValueType.BOOLEAN -> attribute.valueAsBoolean
            ValueType.DEC, ValueType.HEX -> attribute.data != 0
            else -> attribute.valueAsString == "true"
        }
    }

    private fun isFalse(attribute: com.reandroid.arsc.chunk.xml.ResXmlAttribute): Boolean {
        return when (attribute.valueType) {
            ValueType.BOOLEAN -> !attribute.valueAsBoolean
            ValueType.DEC, ValueType.HEX -> attribute.data == 0
            else -> attribute.valueAsString == "false"
        }
    }
}
