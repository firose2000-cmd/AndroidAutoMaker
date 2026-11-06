import java.security.KeyStore
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// No default keystore, embedded credentials or debug-signing fallback for this candidate.
val sparseSigningVariables = listOf("AUTOMAKER_HARDENED_KEYSTORE", "AUTOMAKER_HARDENED_STORE_PASSWORD",
    "AUTOMAKER_HARDENED_KEY_ALIAS", "AUTOMAKER_HARDENED_KEY_PASSWORD")
val sparseSigningValues = sparseSigningVariables.associateWith { providers.environmentVariable(it).orNull }
val sparseSigningConfigured = sparseSigningValues.values.all { !it.isNullOrBlank() }
require(sparseSigningValues.values.all { it == null } || sparseSigningConfigured) {
    "Set all four AUTOMAKER_HARDENED signing variables, or unset all four for an unsigned candidate."
}
val installedHardenedCertificateSha256 = "1a36b68d51fe4bc945848ad3b0e82558447a9670399dc5a138f58fd9d142a2b4"

android {
    namespace = "com.automaker.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.automaker.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 17
        versionName = "1.1.15"
        multiDexEnabled = true
        vectorDrawables.useSupportLibrary = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (sparseSigningConfigured) {
            create("hardenedSparseTest") {
                storeFile = file(sparseSigningValues.getValue("AUTOMAKER_HARDENED_KEYSTORE")!!)
                storePassword = sparseSigningValues.getValue("AUTOMAKER_HARDENED_STORE_PASSWORD")
                keyAlias = sparseSigningValues.getValue("AUTOMAKER_HARDENED_KEY_ALIAS")
                keyPassword = sparseSigningValues.getValue("AUTOMAKER_HARDENED_KEY_PASSWORD")
            }
        }
        create("release") {
            storeFile = rootProject.file("release.keystore")
            storePassword = "automaker"
            keyAlias = "automaker"
            keyPassword = "automaker"
        }
    }

    flavorDimensions += "edition"
    productFlavors {
        create("standard") {
            dimension = "edition"
            isDefault = true
        }
        create("advanced") {
            dimension = "edition"
            applicationIdSuffix = ".adv"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        create("hardenedSparseRelease") {
            initWith(getByName("release"))
            isDebuggable = false
            isJniDebuggable = false
            signingConfig = if (sparseSigningConfigured) signingConfigs.getByName("hardenedSparseTest") else null
            applicationIdSuffix = ".hardenedsparseresourcetest"
            versionNameSuffix = "-hardened-sparse-test"
            matchingFallbacks += listOf("release")
        }
    }

    bundle {
        abi { enableSplit = false }
        density { enableSplit = false }
        language { enableSplit = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/NOTICE.md",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
            )
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = false
    }

    applicationVariants.configureEach {
        val advanced = flavorName == "advanced"
        val version = versionName
        outputs.configureEach {
            val candidate = if (buildType.name == "hardenedSparseRelease") "-HardenedSparseTest" else ""
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                if (advanced) "AndroidAutoMaker-Advanced-$version$candidate.apk" else "AndroidAutoMaker-$version$candidate.apk"
        }
    }
}

val verifyHardenedSparseSigningIdentity by tasks.registering {
    group = "verification"
    description = "Check the externally supplied candidate signer against the existing hardened certificate."
    doLast {
        if (sparseSigningConfigured) {
            val store = KeyStore.getInstance(
                file(sparseSigningValues.getValue("AUTOMAKER_HARDENED_KEYSTORE")!!),
                sparseSigningValues.getValue("AUTOMAKER_HARDENED_STORE_PASSWORD")!!.toCharArray())
            val certificate = store.getCertificate(sparseSigningValues.getValue("AUTOMAKER_HARDENED_KEY_ALIAS"))
                ?: error("The configured alias has no signing certificate.")
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
                .joinToString("") { "%02x".format(it) }
            require(fingerprint == installedHardenedCertificateSha256) {
                "Candidate certificate does not match the supplied existing hardened Maker fingerprint."
            }
        }
    }
}
tasks.configureEach {
    if (name.startsWith("validateSigning") && name.endsWith("HardenedSparseRelease")) {
        dependsOn(verifyHardenedSparseSigningIdentity)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.github.reandroid:ARSCLib:1.3.8")
    implementation("com.android.tools.build:apksig:8.7.3")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
