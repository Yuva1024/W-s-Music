pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
        maven { setUrl("https://jitpack.io") }
        maven { setUrl("https://maven.aliyun.com/repository/public") }
    }
}

rootProject.name = "echo-pc-poc"
include(":innertube", ":metadata", ":lrclib", ":kugou", ":betterlyrics", ":simpmusic", ":youlyplus", ":librarydb", ":app")
// The phone app is built only where an Android SDK is set up, so desktop-only machines are not affected.
val hasAndroidSdk = System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }
if (hasAndroidSdk) include(":android")



