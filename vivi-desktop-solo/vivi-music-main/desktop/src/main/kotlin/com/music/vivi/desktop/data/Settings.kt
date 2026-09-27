package com.music.vivi.desktop.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

object AppDirs {
    /** %APPDATA%\ViviMusic en Windows, ~/Library/Application Support/ViviMusic en macOS, ~/.local/share/vivimusic en Linux. */
    val root: File by lazy {
        val os = System.getProperty("os.name").lowercase()
        val home = System.getProperty("user.home")
        val dir = when {
            os.contains("win") -> File(System.getenv("APPDATA") ?: "$home\\AppData\\Roaming", "ViviMusic")
            os.contains("mac") -> File(home, "Library/Application Support/ViviMusic")
            else -> File(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", "vivimusic")
        }
        dir.mkdirs()
        dir
    }
    val database: File get() = File(root, "song.db")
    val settings: File get() = File(root, "settings.json")
    val cache: File get() = File(root, "cache").apply { mkdirs() }
    val logs: File get() = File(root, "logs").apply { mkdirs() }
}

enum class AudioQualitySetting { AUTO, HIGH, LOW }
enum class ThemeMode { SYSTEM, DARK, LIGHT }

/** Una eliminación local que debe prevalecer sobre respaldos más antiguos del móvil. */
@Serializable
data class Tombstone(
    /** "unlike" | "playlist" | "playlist_song" | "library" */
    val type: String,
    val key: String,
    val at: Long,
)

@Serializable
data class AppSettings(
    // Sincronización
    val syncFolder: String? = null,
    val autoSync: Boolean = true,
    val syncIntervalMinutes: Int = 5,
    val lastSyncAt: Long = 0,
    val lastExportAt: Long = 0,
    /** nombre de archivo → lastModified ya importado */
    val importedBackups: Map<String, Long> = emptyMap(),
    val tombstones: List<Tombstone> = emptyList(),

    // Reproducción
    val audioQuality: AudioQualitySetting = AudioQualitySetting.HIGH,
    val volume: Int = 80,
    val normalizeAudio: Boolean = true,
    val persistQueue: Boolean = true,

    // Apariencia
    val themeMode: ThemeMode = ThemeMode.DARK,
    val dynamicColor: Boolean = true,
    val hideExplicit: Boolean = false,

    // Cuenta YouTube Music (opcional, igual que "Iniciar sesión" en Android)
    val cookie: String? = null,
    val visitorData: String? = null,
    val contentCountry: String? = null,
    val contentLanguage: String? = null,
)

class SettingsStore(private val file: File = AppDirs.settings) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()
    val value: AppSettings get() = _state.value

    private fun load(): AppSettings = runCatching {
        if (file.exists()) json.decodeFromString(AppSettings.serializer(), file.readText()) else AppSettings()
    }.getOrElse { AppSettings() }

    @Synchronized
    fun update(transform: (AppSettings) -> AppSettings) {
        val new = transform(_state.value)
        _state.value = new
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(AppSettings.serializer(), new))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    fun addTombstone(type: String, key: String) = update { s ->
        s.copy(tombstones = (s.tombstones.filterNot { it.type == type && it.key == key } + Tombstone(type, key, System.currentTimeMillis())).takeLast(2000))
    }

    fun removeTombstone(type: String, key: String) = update { s ->
        s.copy(tombstones = s.tombstones.filterNot { it.type == type && it.key == key })
    }
}
