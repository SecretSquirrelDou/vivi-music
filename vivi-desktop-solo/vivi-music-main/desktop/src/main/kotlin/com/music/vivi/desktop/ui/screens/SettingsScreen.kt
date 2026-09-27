package com.music.vivi.desktop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.music.innertube.YouTube
import com.music.vivi.desktop.LocalNavigator
import com.music.vivi.desktop.LocalServices
import com.music.vivi.desktop.data.AppDirs
import com.music.vivi.desktop.data.AudioQualitySetting
import com.music.vivi.desktop.data.ThemeMode
import com.music.vivi.desktop.playback.AudioEngine
import com.music.vivi.desktop.playback.StreamResolver
import com.music.vivi.desktop.sync.SyncManager
import com.music.vivi.desktop.ui.components.PillButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
fun SettingsScreen() {
    val services = LocalServices.current
    val nav = LocalNavigator.current
    val settings by services.settings.state.collectAsState()
    val syncStatus by services.sync.status.collectAsState()
    val scope = rememberCoroutineScope()
    val timeFmt = remember { DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm") }
    fun fmt(ms: Long) = if (ms <= 0) "nunca" else Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(timeFmt)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 28.dp, vertical = 16.dp)) {
        item { SectionTitle("Ajustes") }

        // ───────────── Sincronización ─────────────
        item {
            SettingsCard("Sincronización con Android", "Sin servidores: usa una carpeta compartida (Google Drive, OneDrive, Dropbox, Syncthing…)") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Carpeta de sincronización", style = MaterialTheme.typography.titleSmall)
                        Text(settings.syncFolder ?: "No configurada", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    PillButton("Elegir…", Icons.Rounded.FolderOpen, filled = false) {
                        chooseDirectory(settings.syncFolder)?.let { dir ->
                            services.settings.update { it.copy(syncFolder = dir.absolutePath) }
                            scope.launch { services.sync.syncNow(forceExport = true) }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                ToggleRow("Sincronizar automáticamente", "Cada ${settings.syncIntervalMinutes} min y al cerrar la app", settings.autoSync) { v ->
                    services.settings.update { it.copy(autoSync = v) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Intervalo:", style = MaterialTheme.typography.bodyMedium)
                    listOf(2, 5, 15, 30).forEach { m ->
                        FilterChip(settings.syncIntervalMinutes == m, onClick = { services.settings.update { it.copy(syncIntervalMinutes = m) } }, label = { Text("$m min") })
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    PillButton("Sincronizar ahora", Icons.Rounded.Sync) { scope.launch { services.sync.syncNow(forceExport = true) } }
                    when (val s = syncStatus) {
                        is SyncManager.Status.Running -> { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Text(s.message) }
                        is SyncManager.Status.Done -> Text("✓ ${s.message}", color = MaterialTheme.colorScheme.primary)
                        is SyncManager.Status.Error -> Text("⚠ ${s.message}", color = MaterialTheme.colorScheme.error)
                        SyncManager.Status.Idle -> Text("Última sincronización: ${fmt(settings.lastSyncAt)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "Cómo funciona:\n" +
                        "1. En Android activa VIVI → Ajustes → Copia de seguridad → Copia automática. Los respaldos se guardan en Descargas/vivimusic.\n" +
                        "2. Sincroniza esa carpeta del móvil con la nube (p. ej. la app Autosync for Google Drive, FolderSync o Syncthing) y elige aquí la misma carpeta en tu PC.\n" +
                        "3. El PC fusiona automáticamente cada respaldo del móvil (favoritos, listas, historial, álbumes, artistas, letras…) sin perder nada local.\n" +
                        "4. El PC deja en la carpeta \"auto_backup_desktop_….backup\"; en Android suele aparecer en la lista de copias automáticas (o usa Restaurar y elige el archivo).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Importar respaldo…", Icons.Rounded.FileDownload, filled = false) {
                        chooseBackupFile()?.let { f ->
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { runCatching { services.sync.importBackup(f) } }
                                nav.toast(if (r.isSuccess) "Respaldo fusionado correctamente" else "Error: ${r.exceptionOrNull()?.message}")
                            }
                        }
                    }
                    PillButton("Exportar respaldo…", Icons.Rounded.FileUpload, filled = false) {
                        chooseDirectory(settings.syncFolder)?.let { dir ->
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { runCatching { services.sync.exportTo(dir) } }
                                nav.toast(r.fold({ "Guardado: ${it.name}" }, { "Error: ${it.message}" }))
                            }
                        }
                    }
                    settings.syncFolder?.let { path ->
                        TextButton(onClick = { runCatching { Desktop.getDesktop().open(File(path)) } }) { Text("Abrir carpeta") }
                    }
                }
            }
        }

        // ───────────── Reproducción ─────────────
        item {
            SettingsCard("Reproducción") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Calidad de audio:", style = MaterialTheme.typography.bodyMedium)
                    AudioQualitySetting.entries.forEach { q ->
                        FilterChip(settings.audioQuality == q, onClick = {
                            services.settings.update { it.copy(audioQuality = q) }
                            StreamResolver.quality = q
                        }, label = { Text(when (q) { AudioQualitySetting.AUTO -> "Automática"; AudioQualitySetting.HIGH -> "Alta"; AudioQualitySetting.LOW -> "Baja" }) })
                    }
                }
                ToggleRow("Normalizar volumen", "Usa la sonoridad que informa YouTube Music", settings.normalizeAudio) { v -> services.settings.update { it.copy(normalizeAudio = v) } }
                ToggleRow("Recordar la cola al cerrar", null, settings.persistQueue) { v -> services.settings.update { it.copy(persistQueue = v) } }
                Text(
                    "Motor de audio: ${AudioEngine.vlcSource}" + (services.player.engineError?.let { "  —  $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ───────────── Apariencia ─────────────
        item {
            SettingsCard("Apariencia") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Tema:", style = MaterialTheme.typography.bodyMedium)
                    ThemeMode.entries.forEach { m ->
                        FilterChip(settings.themeMode == m, onClick = { services.settings.update { it.copy(themeMode = m) } },
                            label = { Text(when (m) { ThemeMode.SYSTEM -> "Sistema"; ThemeMode.DARK -> "Oscuro"; ThemeMode.LIGHT -> "Claro" }) })
                    }
                }
                ToggleRow("Color dinámico", "Los colores se adaptan a la carátula (Material You)", settings.dynamicColor) { v -> services.settings.update { it.copy(dynamicColor = v) } }
            }
        }

        // ───────────── Cuenta ─────────────
        item {
            var cookie by remember { mutableStateOf(settings.cookie.orEmpty()) }
            var account by remember { mutableStateOf<String?>(null) }
            SettingsCard("Cuenta de YouTube Music (opcional)", "Mejora las recomendaciones y sincroniza tus \"Me gusta\" con YouTube Music") {
                OutlinedTextField(
                    cookie, { cookie = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Cookie de music.youtube.com (debe contener SAPISID)") },
                    visualTransformation = PasswordVisualTransformation(),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    PillButton("Guardar e iniciar sesión", Icons.Rounded.Sync) {
                        val c = cookie.trim().ifEmpty { null }
                        services.settings.update { it.copy(cookie = c) }
                        services.applyCookie(c)
                        scope.launch {
                            account = withContext(Dispatchers.IO) { YouTube.accountInfo() }.fold({ "Sesión iniciada: ${it.name}" }, { "No se pudo verificar: ${it.message}" })
                        }
                    }
                    if (settings.cookie != null) TextButton(onClick = {
                        cookie = ""
                        services.settings.update { it.copy(cookie = null) }
                        services.applyCookie(null)
                        account = "Sesión cerrada"
                    }) { Text("Cerrar sesión") }
                    account?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        item {
            SettingsCard("Datos") {
                Text("Base de datos: ${AppDirs.database.absolutePath}", style = MaterialTheme.typography.bodySmall)
                Text("Formato compatible con VIVI Android (Room v34, song.db)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row {
                    TextButton(onClick = { runCatching { Desktop.getDesktop().open(AppDirs.root) } }) { Text("Abrir carpeta de datos") }
                    TextButton(onClick = { services.db.clearSearchHistory() }) { Text("Borrar historial de búsqueda") }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SettingsCard(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth().widthIn(max = 900.dp).padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, onChange)
    }
}

private fun chooseDirectory(initial: String?): File? {
    val chooser = JFileChooser(initial ?: System.getProperty("user.home")).apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "Elige la carpeta de sincronización"
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

private fun chooseBackupFile(): File? {
    val chooser = JFileChooser(System.getProperty("user.home")).apply {
        dialogTitle = "Elige un respaldo de VIVI (.backup)"
        fileFilter = FileNameExtensionFilter("Respaldo de VIVI Music", "backup", "zip")
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

