import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.application")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/** Permanent store id (Play applicationId / iOS bundle id). Never change after the first release. */
val appId = "se.viccom.anylap"

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.components.resources)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            // Supabase (accounts + sync). 3.2.x is the last line built for Kotlin 2.2; bump with Kotlin.
            implementation("io.github.jan-tennert.supabase:postgrest-kt:3.2.6")
            implementation("io.github.jan-tennert.supabase:auth-kt:3.2.6")
            // Car-recognition crops shared to improve the model (Storage bucket anylap-crops)
            implementation("io.github.jan-tennert.supabase:storage-kt:3.2.6")
            implementation("io.ktor:ktor-client-core:3.3.1")
        }
        iosMain.dependencies {
            implementation("io.ktor:ktor-client-darwin:3.3.1")
        }
        androidMain.dependencies {
            implementation("androidx.activity:activity-compose:1.10.1")
            implementation("androidx.core:core-ktx:1.16.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
            implementation("io.ktor:ktor-client-okhttp:3.3.1")
            // Join a race by QR: Google's code scanner (no camera permission, UI from Play services)
            implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
            // Auto-update (VICCOM rule): Play In-App Updates for store builds
            implementation("com.google.android.play:app-update-ktx:2.1.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        val androidUnitTest by getting {
            dependencies {
                // Screenshot tests (Robolectric renders the real Compose UI on the JVM).
                implementation("junit:junit:4.13.2")
                implementation("org.robolectric:robolectric:4.16")
                implementation("androidx.test:core:1.6.1")
                implementation("androidx.compose.ui:ui-test-junit4:1.9.4")
                implementation("io.github.takahirom.roborazzi:roborazzi:1.52.0")
                implementation("io.github.takahirom.roborazzi:roborazzi-compose:1.52.0")
            }
        }
    }
}

compose.resources {
    packageOfResClass = "app.anylap.resources"
    publicResClass = false
    generateResClass = always
}

android {
    namespace = "app.anylap"
    compileSdk = 36

    defaultConfig {
        applicationId = appId
        minSdk = 29
        targetSdk = 36
        // Store builds (release.yml) set VERSION_CODE; test builds (build.yml) use the CI run number,
        // so every test build installs over the previous one (Android refuses a lower or equal code).
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull()
            ?: System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: providers.gradleProperty("appVersionName").get()
    }
    signingConfigs {
        // One fixed key for test builds (public test key, never used for the store), so a new CI build
        // installs on top of the old one and keeps racers, calibrations and sessions.
        getByName("debug") {
            storeFile = file("test-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Play upload key, only in the release workflow (secrets → env, see RELEASING.md). Never in the repo.
        val uploadKeystore = System.getenv("ANDROID_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }
        if (uploadKeystore != null) {
            create("upload") {
                storeFile = uploadKeystore
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            signingConfigs.findByName("upload")?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("roborazzi.test.record", "true")
                it.maxHeapSize = "3g"
            }
        }
    }
}

dependencies {
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.9.4")
}
