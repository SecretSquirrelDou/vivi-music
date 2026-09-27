package com.music.vivi.desktop.sync

import com.music.vivi.desktop.data.AppDirs
import com.music.vivi.desktop.data.MusicDatabase
import com.music.vivi.desktop.data.SettingsStore
import com.music.vivi.desktop.data.Tombstone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.sql.Connection
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Sincronización PC ↔ Android SIN servidor, usando una carpeta compartida
 * (Google Drive para escritorio, OneDrive, Dropbox, Syncthing…).
 *
 * Formato: exactamente el mismo archivo `.backup` que genera VIVI en Android
 * (ZIP con `song.db` de Room v34 y opcionalmente `settings.preferences_pb`).
 *
 *  • IMPORTAR: cada `.backup` nuevo que aparezca en la carpeta (creado por el móvil)
 *    se FUSIONA con la base local: favoritos, biblioteca, listas, historial,
 *    contadores, búsquedas y letras. Nunca se pierde nada local.
 *  • EXPORTAR: el escritorio escribe `auto_backup_desktop_<fecha>.backup` con la
 *    base ya fusionada. Como su nombre empieza por `auto_backup_`, la app Android
 *    lo muestra en su lista de copias automáticas y se restaura con un toque.
 *  • BORRADOS: lo que se elimina en el PC (quitar "me gusta", borrar listas…) se
 *    guarda como "lápida" para que un respaldo antiguo del móvil no lo resucite.
 */
class SyncManager(
    private val db: MusicDatabase,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {
    sealed interface Status {
        data object Idle : Status
        data class Running(val message: String) : Status
        data class Done(val message: String, val at: Long) : Status
        data class Error(val message: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val mutex = Mutex()
    private var loopJob: Job? = null

    val folder: File? get() = settings.value.syncFolder?.let(::File)?.takeIf { it.isDirectory }

    fun start() {
        loopJob?.cancel()
        loopJob = scope.launch(Dispatchers.IO) {
            delay(3_000)
            while (isActive) {
                if (settings.value.autoSync && folder != null) syncNow()
                delay(settings.value.syncIntervalMinutes.coerceAtLeast(1) * 60_000L)
            }
        }
    }

    /** Importa lo nuevo del móvil y exporta el estado fusionado. */
    suspend fun syncNow(forceExport: Boolean = false): Status = withContext(Dispatchers.IO) {
        mutex.withLock {
            val dir = folder ?: return@withLock Status.Error("Configura primero la carpeta de sincronización").also { _status.value = it }
            try {
                _status.value = Status.Running("Buscando respaldos del móvil…")
                val imported = importNew(dir)
                val needExport = forceExport || imported > 0 || db.lastWriteAt > settings.value.lastExportAt
                if (needExport) {
                    _status.value = Status.Running("Exportando respaldo para Android…")
                    exportTo(dir)
                }
                settings.update { it.copy(lastSyncAt = System.currentTimeMillis()) }
                val msg = buildString {
                    append(if (imported > 0) "$imported respaldo(s) del móvil fusionado(s)" else "Sin cambios del móvil")
                    if (needExport) append(" · respaldo exportado")
                }
                Status.Done(msg, System.currentTimeMillis()).also { _status.value = it }
            } catch (e: Exception) {
                e.printStackTrace()
                Status.Error(e.message ?: e.javaClass.simpleName).also { _status.value = it }
            }
        }
    }

    // ───────────────────────── importación ─────────────────────────

    private fun candidates(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".backup", ignoreCase = true) && !f.name.startsWith(DESKTOP_PREFIX) }
            .orEmpty()
            .sortedBy { it.lastModified() }

    private fun importNew(dir: File): Int {
        val done = settings.value.importedBackups
        val pending = candidates(dir).filter { done[it.name] != it.lastModified() }
        var count = 0
        for (backup in pending) {
            _status.value = Status.Running("Fusionando ${backup.name}…")
            val ok = runCatching { importBackup(backup) }.onFailure { it.printStackTrace() }.isSuccess
            settings.update { s -> s.copy(importedBackups = (s.importedBackups + (backup.name to backup.lastModified())).entries.toList().takeLast(200).associate { it.toPair() }) }
            if (ok) count++
        }
        return count
    }

    /** Importa (fusiona) un archivo `.backup` concreto. También sirve para "Importar respaldo…" manual. */
    fun importBackup(backup: File) {
        val tmp = File(AppDirs.cache, "import_${System.nanoTime()}.db")
        try {
            var found = false
            ZipInputStream(backup.inputStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == DB_ENTRY) {
                        tmp.outputStream().use { zip.copyTo(it) }
                        found = true
                    }
                    entry = zip.nextEntry
                }
            }
            require(found) { "El archivo no contiene $DB_ENTRY" }
            mergeFrom(tmp)
        } finally {
            tmp.delete()
            File(tmp.path + "-wal").delete()
            File(tmp.path + "-shm").delete()
        }
    }

    private fun mergeFrom(other: File) {
        val tombstones = settings.value.tombstones
        // ATTACH/DETACH y PRAGMA foreign_keys no se permiten dentro de una transacción.
        db.read { c ->
            c.createStatement().use { st ->
                st.execute("PRAGMA foreign_keys = OFF")
                st.execute("ATTACH DATABASE '${other.absolutePath.replace("'", "''")}' AS ext")
            }
        }
        try {
            db.write { c ->
                Merger(c).mergeAll()
                applyTombstones(c, tombstones)
            }
        } finally {
            db.read { c ->
                c.createStatement().use { st ->
                    runCatching { st.execute("DETACH DATABASE ext") }
                    st.execute("PRAGMA foreign_keys = ON")
                }
            }
        }
    }

    private class Merger(private val c: Connection) {
        private fun cols(schema: String, table: String): List<String> =
            c.createStatement().use { st ->
                st.executeQuery("PRAGMA $schema.table_info(`$table`)").use { rs ->
                    val out = ArrayList<String>()
                    while (rs.next()) out.add(rs.getString("name"))
                    out
                }
            }

        private fun exec(sql: String) = c.createStatement().use { it.executeUpdate(sql) }

        private fun hasTable(table: String) = cols("ext", table).isNotEmpty()

        /** INSERT OR IGNORE copiando solo las columnas que existen en ambas versiones del esquema. */
        private fun copyIgnore(table: String, where: String = "1", exclude: Set<String> = emptySet()) {
            if (!hasTable(table)) return
            val common = cols("main", table).intersect(cols("ext", table).toSet()) - exclude
            if (common.isEmpty()) return
            val list = common.joinToString(", ") { "`$it`" }
            exec("INSERT OR IGNORE INTO main.`$table` ($list) SELECT $list FROM ext.`$table` e WHERE $where")
        }

        fun mergeAll() {
            // Entidades base
            copyIgnore("artist")
            copyIgnore("album")
            copyIgnore("song")
            copyIgnore("format")
            copyIgnore("lyrics")
            copyIgnore("set_video_id")
            copyIgnore("speed_dial_item")

            // Mapas (solo si las referencias existen)
            copyIgnore("song_artist_map", "EXISTS (SELECT 1 FROM main.song s WHERE s.id = e.songId) AND EXISTS (SELECT 1 FROM main.artist a WHERE a.id = e.artistId)")
            copyIgnore("song_album_map", "EXISTS (SELECT 1 FROM main.song s WHERE s.id = e.songId) AND EXISTS (SELECT 1 FROM main.album a WHERE a.id = e.albumId)")
            copyIgnore("album_artist_map", "EXISTS (SELECT 1 FROM main.album s WHERE s.id = e.albumId) AND EXISTS (SELECT 1 FROM main.artist a WHERE a.id = e.artistId)")

            // Favoritos / biblioteca: gana lo más reciente; los "me gusta" se unen.
            exec(
                """UPDATE main.song SET liked = 1,
                     likedDate = (SELECT e.likedDate FROM ext.song e WHERE e.id = song.id)
                   WHERE id IN (SELECT e.id FROM ext.song e WHERE e.liked = 1)
                     AND (liked = 0 OR COALESCE(likedDate, 0) < (SELECT COALESCE(e.likedDate, 0) FROM ext.song e WHERE e.id = song.id))""",
            )
            exec(
                """UPDATE main.song SET inLibrary = (SELECT e.inLibrary FROM ext.song e WHERE e.id = song.id)
                   WHERE inLibrary IS NULL AND id IN (SELECT e.id FROM ext.song e WHERE e.inLibrary IS NOT NULL)""",
            )
            exec(
                """UPDATE main.song SET totalPlayTime = (SELECT e.totalPlayTime FROM ext.song e WHERE e.id = song.id)
                   WHERE id IN (SELECT e.id FROM ext.song e WHERE e.totalPlayTime > song.totalPlayTime)""",
            )
            // Artistas seguidos / álbumes guardados
            exec(
                """UPDATE main.artist SET bookmarkedAt = (SELECT e.bookmarkedAt FROM ext.artist e WHERE e.id = artist.id)
                   WHERE bookmarkedAt IS NULL AND id IN (SELECT e.id FROM ext.artist e WHERE e.bookmarkedAt IS NOT NULL)""",
            )
            exec(
                """UPDATE main.album SET bookmarkedAt = (SELECT e.bookmarkedAt FROM ext.album e WHERE e.id = album.id)
                   WHERE bookmarkedAt IS NULL AND id IN (SELECT e.id FROM ext.album e WHERE e.bookmarkedAt IS NOT NULL)""",
            )

            mergePlaylists()

            // Historial de reproducción (sin duplicar)
            if (hasTable("event")) {
                exec(
                    """INSERT INTO main.event (songId, timestamp, playTime)
                       SELECT e.songId, e.timestamp, e.playTime FROM ext.event e
                       WHERE EXISTS (SELECT 1 FROM main.song s WHERE s.id = e.songId)
                         AND NOT EXISTS (SELECT 1 FROM main.event m WHERE m.songId = e.songId AND m.timestamp = e.timestamp)""",
                )
            }
            if (hasTable("playCount")) {
                copyIgnore("playCount")
                exec(
                    """UPDATE main.playCount SET count = (SELECT e.count FROM ext.playCount e
                         WHERE e.song = playCount.song AND e.year = playCount.year AND e.month = playCount.month)
                       WHERE EXISTS (SELECT 1 FROM ext.playCount e WHERE e.song = playCount.song AND e.year = playCount.year
                         AND e.month = playCount.month AND e.count > playCount.count)""",
                )
            }
            if (hasTable("search_history")) {
                exec("INSERT OR IGNORE INTO main.search_history (query) SELECT e.query FROM ext.search_history e ORDER BY e.id")
            }
            if (hasTable("recognition_history")) {
                copyIgnore(
                    "recognition_history",
                    "NOT EXISTS (SELECT 1 FROM main.recognition_history m WHERE m.trackId = e.trackId AND m.recognizedAt = e.recognizedAt)",
                    exclude = setOf("id"),
                )
            }
        }

        private fun mergePlaylists() {
            if (!hasTable("playlist")) return
            copyIgnore("playlist")
            // Nombre / metadatos: gana la versión modificada más recientemente
            exec(
                """UPDATE main.playlist SET name = (SELECT e.name FROM ext.playlist e WHERE e.id = playlist.id),
                     lastUpdateTime = (SELECT e.lastUpdateTime FROM ext.playlist e WHERE e.id = playlist.id)
                   WHERE id IN (SELECT e.id FROM ext.playlist e
                     WHERE COALESCE(e.lastUpdateTime, 0) > COALESCE(playlist.lastUpdateTime, 0))""",
            )
            if (!hasTable("playlist_song_map")) return
            // Canciones: unión. Las que faltan se añaden al final respetando el orden del móvil.
            val missing = c.createStatement().use { st ->
                st.executeQuery(
                    """SELECT e.playlistId, e.songId, e.setVideoId FROM ext.playlist_song_map e
                       WHERE EXISTS (SELECT 1 FROM main.playlist p WHERE p.id = e.playlistId)
                         AND EXISTS (SELECT 1 FROM main.song s WHERE s.id = e.songId)
                         AND NOT EXISTS (SELECT 1 FROM main.playlist_song_map m WHERE m.playlistId = e.playlistId AND m.songId = e.songId)
                       ORDER BY e.playlistId, e.position""",
                ).use { rs ->
                    val out = ArrayList<Triple<String, String, String?>>()
                    while (rs.next()) out.add(Triple(rs.getString(1), rs.getString(2), rs.getString(3)))
                    out
                }
            }
            c.prepareStatement(
                """INSERT INTO main.playlist_song_map (playlistId, songId, position, setVideoId)
                   VALUES (?, ?, (SELECT COALESCE(MAX(position), -1) + 1 FROM main.playlist_song_map WHERE playlistId = ?), ?)""",
            ).use { ps ->
                missing.forEach { (pid, sid, setId) ->
                    ps.setString(1, pid); ps.setString(2, sid); ps.setString(3, pid); ps.setString(4, setId)
                    ps.executeUpdate()
                }
            }
        }
    }

    /** Vuelve a aplicar los borrados hechos en el PC que el respaldo del móvil aún no conocía. */
    private fun applyTombstones(c: Connection, tombstones: List<Tombstone>) {
        fun roomTime(realMillis: Long): Long =
            LocalDateTime.ofInstant(Instant.ofEpochMilli(realMillis), ZoneId.systemDefault()).toInstant(ZoneOffset.UTC).toEpochMilli()

        tombstones.forEach { t ->
            val rt = roomTime(t.at)
            when (t.type) {
                "unlike" -> c.prepareStatement("UPDATE main.song SET liked = 0, likedDate = NULL WHERE id = ? AND COALESCE(likedDate, 0) <= ?").use {
                    it.setString(1, t.key); it.setLong(2, rt); it.executeUpdate()
                }
                "library" -> c.prepareStatement("UPDATE main.song SET inLibrary = NULL WHERE id = ? AND COALESCE(inLibrary, 0) <= ?").use {
                    it.setString(1, t.key); it.setLong(2, rt); it.executeUpdate()
                }
                "playlist" -> {
                    c.prepareStatement("DELETE FROM main.playlist_song_map WHERE playlistId = ?").use { it.setString(1, t.key); it.executeUpdate() }
                    c.prepareStatement("DELETE FROM main.playlist WHERE id = ?").use { it.setString(1, t.key); it.executeUpdate() }
                }
                "playlist_song" -> {
                    val (pid, sid) = t.key.split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                    c.prepareStatement("DELETE FROM main.playlist_song_map WHERE playlistId = ? AND songId = ?").use {
                        it.setString(1, pid); it.setString(2, sid); it.executeUpdate()
                    }
                }
                "artist" -> c.prepareStatement("UPDATE main.artist SET bookmarkedAt = NULL WHERE id = ? AND COALESCE(bookmarkedAt, 0) <= ?").use {
                    it.setString(1, t.key); it.setLong(2, rt); it.executeUpdate()
                }
                "album" -> c.prepareStatement("UPDATE main.album SET bookmarkedAt = NULL, inLibrary = NULL WHERE id = ? AND COALESCE(bookmarkedAt, 0) <= ?").use {
                    it.setString(1, t.key); it.setLong(2, rt); it.executeUpdate()
                }
            }
        }
    }

    // ───────────────────────── exportación ─────────────────────────

    /** Escribe un `.backup` compatible con la app Android en [dir]. Devuelve el archivo creado. */
    fun exportTo(dir: File): File {
        dir.mkdirs()
        val snapshot = File(AppDirs.cache, "export_${System.nanoTime()}.db")
        try {
            db.snapshotTo(snapshot)
            val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
            val target = File(dir, "$DESKTOP_PREFIX$stamp.backup")
            val partial = File(dir, "$DESKTOP_PREFIX$stamp.partial")
            ZipOutputStream(partial.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(DB_ENTRY))
                snapshot.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            partial.renameTo(target)
            // Conserva solo los 3 respaldos de escritorio más recientes
            dir.listFiles { f -> f.name.startsWith(DESKTOP_PREFIX) && f.name.endsWith(".backup") }
                .orEmpty().sortedByDescending { it.lastModified() }.drop(3).forEach { it.delete() }
            settings.update { it.copy(lastExportAt = System.currentTimeMillis()) }
            return target
        } finally {
            snapshot.delete()
        }
    }

    companion object {
        const val DB_ENTRY = "song.db"
        const val DESKTOP_PREFIX = "auto_backup_desktop_"
    }
}
