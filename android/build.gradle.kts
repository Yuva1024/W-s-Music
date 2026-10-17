import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
}

android {
    namespace = "app.wsmusic"
    compileSdk = 37 // OkHttp 5.5 (from the shared YouTube Music code) requires it; minSdk below decides which phones can install

    defaultConfig {
        applicationId = "app.wsmusic"
        minSdk = 23 // Android 6.0
        targetSdk = 36
        versionCode = 10006 // major*10000 + minor*100 + patch
        versionName = "1.0.6"
    }

    // Release builds are signed with the permanent key described in keystore.properties (kept out of git).
    // Every version must use the same key, or Android refuses to install it over the previous one.
    val keystore = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f ->
        Properties().apply { f.inputStream().use { load(it) } }
    }
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = file(keystore.getProperty("storeFile"))
            storePassword = keystore.getProperty("storePassword")
            keyAlias = keystore.getProperty("keyAlias")
            keyPassword = keystore.getProperty("keyPassword")
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false // the shared code uses reflection (Room, serialization, NewPipe); keep everything
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true // java.time on Android versions before 8.0
    }

    buildFeatures { compose = true }

    packaging {
        resources {
            excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/INDEX.LIST", "META-INF/DEPENDENCIES", "META-INF/*.kotlin_module", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":innertube"))
    implementation(project(":stream"))
    implementation(project(":shared"))
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    implementation("androidx.compose.material:material-icons-core")

    val media3 = "1.8.0"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-session:$media3")

    val composeBom = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5") // java.time and java.nio.file on older Android
}

// The shared code is compiled against JetBrains Compose (the desktop toolkit). On Android its classes come from
// AndroidX Compose instead (same packages), at the versions of the Compose BOM above; JetBrains' Android artifacts
// for 1.12 would need Android Gradle plugin 9.1.
configurations.configureEach {
    listOf("org.jetbrains.compose.runtime", "org.jetbrains.compose.ui", "org.jetbrains.compose.foundation",
        "org.jetbrains.compose.annotation-internal", "org.jetbrains.compose.collection-internal").forEach { exclude(group = it) }
}
