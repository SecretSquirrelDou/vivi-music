import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.3.10"
    kotlin("plugin.serialization") version "2.3.10"
    kotlin("plugin.compose") version "2.3.10"
    id("org.jetbrains.compose") version "1.9.0"
}

group = "com.music.vivi"
version = "1.0.0"

kotlin {
    jvmToolchain(21)
}

// ─────────────────────────────────────────────────────────────────────────────
//  Código reutilizado 1:1 de la app Android (sin copiarlo):
//   • ../innertube        → API de YouTube Music (búsqueda, home, álbumes, player…)
//   • ../lyricsProvider   → LrcLib (letras sincronizadas)
//  Ambos módulos son Kotlin puro (ktor + kotlinx.serialization + NewPipe), por lo
//  que compilan directamente en la JVM de escritorio.
// ─────────────────────────────────────────────────────────────────────────────
sourceSets {
    main {
        kotlin.srcDir("../innertube/src/main/kotlin")
        kotlin.srcDir("../lyricsProvider/src/main/kotlin/com/music/lrclib")
    }
}

val ktorVersion = "3.4.0"

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")

    // Red (mismas librerías que el módulo innertube de Android)
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-client-encoding:$ktorVersion")
    implementation("com.squareup.okhttp3:okhttp:5.1.0")
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.1")

    // Base de datos: mismo archivo SQLite (song.db) que usa Room en Android
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")

    // Motor de audio nativo (reemplaza ExoPlayer/Media3)
    implementation("uk.co.caprica:vlcj:4.8.3")

    implementation("org.slf4j:slf4j-simple:2.0.16")
}

compose.desktop {
    application {
        mainClass = "com.music.vivi.desktop.MainKt"
        jvmArgs += listOf("-Dfile.encoding=UTF-8", "-Xss4m")

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb, TargetFormat.Dmg)
            packageName = "VIVI Music"
            packageVersion = "1.0.0"
            description = "VIVI Music para escritorio"
            vendor = "VIVI Music"
            copyright = "GPL-3.0"
            includeAllModules = true

            // VLC portable se empaqueta aquí (ver scripts/fetch-vlc.ps1 y el workflow de CI)
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))

            windows {
                iconFile.set(project.file("src/main/resources/icon.ico"))
                menuGroup = "VIVI Music"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "5b0f8f3e-6c1e-4a57-9a0b-7d1e3c2a9f41"
            }
            linux {
                iconFile.set(project.file("src/main/resources/icon.png"))
            }
        }
    }
}
