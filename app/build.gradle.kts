import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}
kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":innertube"))
    implementation(project(":stream"))
    implementation(project(":shared"))
    implementation(project(":librarydb"))
    implementation(project(":lrclib"))
    implementation(project(":kugou"))
    implementation(project(":betterlyrics"))
    implementation(project(":simpmusic"))
    implementation(project(":youlyplus"))
    implementation("com.atilika.kuromoji:kuromoji-ipadic:0.9.0")
    implementation("com.github.promeG:tinypinyin:2.0.3")
    implementation("com.github.MetrolistGroup.innertubex:innertubex:0.7.2")
    implementation("io.ktor:ktor-client-okhttp:3.6.0")
    implementation("io.ktor:ktor-client-content-negotiation:3.6.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.6.0")
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
    implementation("com.tianscar.javasound:jaad:0.9.4") // pure-Java AAC decoder for the compatible audio engine
    implementation("net.java.dev.jna:jna-platform:5.14.0") // dark Windows title bar
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    // JavaFX Media for audio playback (AAC/m4a), no external player needed
    val fx = "21.0.5"
    val os = org.gradle.internal.os.OperatingSystem.current()
    val plat = when { os.isWindows -> "win"; os.isMacOsX -> "mac"; else -> "linux" }
    for (m in listOf("base", "graphics", "media")) implementation("org.openjfx:javafx-$m:$fx:$plat")
}

// One place for the version: the installer, the Updater (version.properties) and the release tag must agree.
val appVersion = "1.0.9"

tasks.processResources {
    inputs.property("appVersion", appVersion)
    filesMatching("version.properties") { expand("appVersion" to appVersion) }
}

compose.desktop {
    application {
        mainClass = "echopc.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "WsMusic"
            packageVersion = appVersion
            includeAllModules = true // keeps TLS (jdk.crypto.ec), java.net.http, sql etc. in the bundled runtime
            description = "W's MUSIC"
            vendor = "W"
            windows {
                menuGroup = "W's MUSIC"
                shortcut = true
                perUserInstall = true // installs into the user's profile: no administrator rights needed (school/work PCs)
                // The id Windows uses to replace an installed version with a newer one (what earlier builds got by default).
                upgradeUuid = "B797C31E-B15C-3AC1-8DDD-314B44040F14"
                iconFile.set(project.file("icon.ico"))
            }
        }
        jvmArgs += listOf(
            // Small, quiet JVM: serial GC (no extra GC threads / 40+ MB of G1 bookkeeping), a small heap that
            // shrinks after use, and the light JIT tier (about half the compile CPU, less code cache).
            "-XX:+UseSerialGC", "-Xms24m", "-Xmx512m", "-XX:MinHeapFreeRatio=10", "-XX:MaxHeapFreeRatio=25",
            "-XX:TieredStopAtLevel=1", "-XX:ReservedCodeCacheSize=48m", "-Xss512k",
            "-Djava.net.preferIPv4Stack=true", // stream URLs are tied to the IP family; IPv6 timeouts made songs fail to start
            // skiko.renderApi is chosen at startup by Device.configure(): OpenGL normally, CPU rendering after a failed start.
        )
    }
}

// Portable build: unzip anywhere and run WsMusic.exe (no installer, no admin rights). `gradlew :app:portableZip`
tasks.register<Zip>("portableZip") {
    dependsOn("createDistributable")
    from(layout.buildDirectory.dir("compose/binaries/main/app"))
    archiveFileName.set("WsMusic-portable.zip")
    destinationDirectory.set(layout.buildDirectory.dir("compose/binaries/main"))
}

tasks.register<JavaExec>("smoke") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set((project.findProperty("main") as String?) ?: "echopc.SmokeKt")
    jvmArgs("-Djava.net.preferIPv4Stack=true")
    args = (project.findProperty("q") as String?)?.let { listOf(it) } ?: listOf(project.projectDir.absolutePath)
}

dependencies {
    testImplementation(compose.desktop.uiTestJUnit4)
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
}

tasks.withType<Test> {
    systemProperty("wsmusic.nolog", "1")
    forkEvery = 1 // each test class in its own JVM: UI tests share global state (Skiko, caches) and were disturbing each other
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}





