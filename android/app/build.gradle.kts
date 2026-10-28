plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

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
        create("hardenedTest") {
            storeFile = file(providers.environmentVariable("AUTOMAKER_RELEASE_TEST_KEYSTORE")
                .orElse("${System.getProperty("user.home")}/.android/automaker-security-test-release.keystore").get())
            storePassword = "android"
            keyAlias = "AndroidDebugKey"
            keyPassword = "android"
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
        debug {
            applicationIdSuffix = ".hardenedtest"
            versionNameSuffix = "-security-test"
            // Use a fresh test identity without replacing the original or the normal debug key.
            signingConfig = signingConfigs.getByName("debug").apply {
                storeFile = file(providers.environmentVariable("AUTOMAKER_TEST_KEYSTORE")
                    .orElse("${System.getProperty("user.home")}/.android/automaker-security-test.keystore").get())
            }
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        create("hardenedRelease") {
            initWith(getByName("release"))
            isDebuggable = false
            isJniDebuggable = false
            signingConfig = signingConfigs.getByName("hardenedTest")
            applicationIdSuffix = ".hardenedtestrelease"
            versionNameSuffix = "-security-test-release"
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
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                if (advanced) "AndroidAutoMaker-Advanced-$version.apk" else "AndroidAutoMaker-$version.apk"
        }
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
