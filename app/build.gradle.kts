plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.fujixfer.app"
    compileSdk = 36 // Media3 1.10 asks for 36; targetSdk stays 35 (no behaviour change)

    defaultConfig {
        applicationId = "com.fujixfer.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        // Only the phone's ABI: MapLibre and the FFmpeg of NextLib ship native code for 4 ABIs each.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // One key for every build: the Docker container is thrown away after each build, and with it the debug key
    // Gradle would generate there. A new key means Android refuses to install over the old app (uninstalling
    // loses the pairing, the listing cache, the metadata and the Wi-Fi approval). Debug key only, not a secret.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Kotlin 2.2 (MapLibre 13 is built with it): kotlinOptions {} is no longer accepted, compilerOptions replaces it
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    // Map of the photos: MapLibre with OpenFreeMap (OpenStreetMap data, no API key). The only external library.
    implementation("org.maplibre.gl:android-sdk:13.6.1")
    // Video player: Media3 ExoPlayer reads MOV + 24-bit LPCM; the phone's hardware decoder is tried first, and
    // NextLib's FFmpeg decodes in software what the hardware refuses (H.265 4:2:2 10-bit of the X-M5).
    // NextLib's version is "<media3>-<nextlib>": the two must move together. NextLib is GPL-3.0.
    // 1.11.0-0.15.0 fixed the green/wrong colours of FFmpeg playback (10-bit and 4:2:2 frames, NextLib PR #199).
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("io.github.anilbeesetti:nextlib-media3ext:1.11.1-0.16.0")
    // Jetpack Compose: introduced one screen at a time (the download screen first). The rest is still Views.
    // The BOM fixes the version of every androidx.compose library; MainActivity is a ComponentActivity because
    // ComposeView needs the lifecycle and saved-state owners that a plain Activity does not provide.
    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
}
