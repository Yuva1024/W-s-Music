plugins {
    kotlin("jvm")
}
kotlin { jvmToolchain(21) }

// The app logic shared by the desktop app and the phone app: the Controller (queue, search, mixes,
// feedback, Smart Shuffle, picker), the taste engine and search ranking. No UI toolkit, no AWT, no JavaFX:
// platform pieces (log, album-art colour, lyrics, updates) come in through AppHooks.
dependencies {
    api(project(":librarydb"))
    api(project(":stream"))
    api("org.jetbrains.compose.runtime:runtime:1.12.1")
    api("org.jetbrains.compose.ui:ui-graphics:1.12.1")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.6.0") // kotlinx.serialization.json for the Spotify import
}
