plugins {
    kotlin("jvm")
}
kotlin { jvmToolchain(21) }

// Finding and downloading a song's audio from YouTube Music. Shared by the desktop app and the phone app,
// so it uses only what both platforms have (no java.net.http, no AWT, no JavaFX).
dependencies {
    api(project(":innertube"))
    api("com.github.MetrolistGroup.innertubex:innertubex:0.7.2")
    api("io.ktor:ktor-client-okhttp:3.6.0")
    implementation("io.ktor:ktor-client-content-negotiation:3.6.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.6.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}
