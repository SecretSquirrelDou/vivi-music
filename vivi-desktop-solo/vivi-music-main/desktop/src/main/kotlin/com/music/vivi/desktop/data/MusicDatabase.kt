package com.music.vivi.desktop.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Acceso a `song.db` con el MISMO esquema que Room usa en Android (v34).
 *
 * Las fechas se guardan igual que el TypeConverter de Android:
 * `LocalDateTime` → epoch millis tratándolo como UTC.
 */
class MusicDatabase(val file: File) {
    private val lock = Any()
    private var conn: Connection = open()

    private val _changes = MutableStateFlow(0L)
    /** Cambia cada vez que algo se escribe. La UI lo observa para recargar. */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    /** Momento (System.currentTimeMillis) de la última escritura local. */
    @Volatile
    var lastWriteAt: Long = 0L
        private set

    private fun open(): Connection {
        Class.forName("org.sqlite.JDBC")
        file.parentFile?.mkdirs()
        val c = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
        c.createStatement().use { st ->
            st.execute("PRAGMA foreign_keys = ON")
            st.execute("PRAGMA busy_timeout = 5000")
        }
        ensureSchema(c)
        return c
    }

    private fun ensureSchema(c: Connection) {
        val version = c.createStatement().use { st ->
            st.executeQuery("PRAGMA user_version").use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
        if (version == 0) {
            c.autoCommit = false
            try {
                c.createStatement().use { st ->
                    RoomSchema.statements.forEach { st.execute(it) }
                    st.execute("PRAGMA user_version = ${RoomSchema.VERSION}")
                }
                c.commit()
            } catch (e: Exception) {
                c.rollback()
                throw e
            } finally {
                c.autoCommit = true
            }
        }
    }

    fun close() = synchronized(lock) { runCatching { conn.close() } }

    /** Reemplaza el archivo de base de datos completo (restauración manual). */
    fun replaceWith(newDb: File) = synchronized(lock) {
        conn.close()
        newDb.copyTo(file, overwrite = true)
        File(file.path + "-wal").delete()
        File(file.path + "-shm").delete()
        conn = open()
        notifyChanged()
    }

    // ───────────────────────── utilidades JDBC ─────────────────────────

    fun <T> read(block: (Connection) -> T): T = synchronized(lock) { block(conn) }

    fun <T> write(block: (Connection) -> T): T = synchronized(lock) {
        val wasAuto = conn.autoCommit
        conn.autoCommit = false
        try {
            val r = block(conn)
            conn.commit()
            r
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = wasAuto
            notifyChanged()
        }
    }

    fun notifyChanged() {
        lastWriteAt = System.currentTimeMillis()
        _changes.value = _changes.value + 1
    }

    private fun Connection.exec(sql: String, vararg args: Any?): Int =
        prepareStatement(sql).use { ps -> ps.bind(args); ps.executeUpdate() }

    private fun <T> Connection.query(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> =
        prepareStatement(sql).use { ps ->
            ps.bind(args)
            ps.executeQuery().use { rs ->
                val out = ArrayList<T>()
                while (rs.next()) out.add(map(rs))
                out
            }
        }

    private fun PreparedStatement.bind(args: Array<out Any?>) {
        args.forEachIndexed { i, a ->
            val idx = i + 1
            when (a) {
                null -> setNull(idx, Types.NULL)
                is String -> setString(idx, a)
                is Int -> setInt(idx, a)
                is Long -> setLong(idx, a)
                is Boolean -> setInt(idx, if (a) 1 else 0)
                is Double -> setDouble(idx, a)
                else -> setString(idx, a.toString())
            }
        }
    }

    private fun ResultSet.longOrNull(col: String): Long? = getLong(col).let { if (wasNull()) null else it }
    private fun ResultSet.intOrNull(col: String): Int? = getInt(col).let { if (wasNull()) null else it }

    // ───────────────────────── canciones ─────────────────────────

    /** Inserta la canción (y sus artistas) si no existe; si existe, refresca metadatos. */
    fun upsertSong(t: Track) = write { it.upsertSongInternal(t) }

    private fun Connection.upsertSongInternal(t: Track) {
        exec(
            """INSERT OR IGNORE INTO song (id, title, duration, thumbnailUrl, albumId, albumName, explicit,
                 year, date, dateModified, liked, likedDate, totalPlayTime, inLibrary, dateDownload, isLocal,
                 libraryAddToken, libraryRemoveToken, lyricsOffset, romanizeLyrics, isDownloaded, isUploaded, isVideo)
               VALUES (?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, 0, NULL, 0, NULL, NULL, 0, NULL, NULL, 0, 1, 0, 0, ?)""",
            t.id, t.title, t.durationSec, t.thumbnail, t.albumId, t.albumName, t.explicit, t.isVideo,
        )
        exec(
            """UPDATE song SET title = ?,
                 thumbnailUrl = COALESCE(?, thumbnailUrl),
                 duration = CASE WHEN ? > 0 THEN ? ELSE duration END,
                 albumId = COALESCE(?, albumId),
                 albumName = COALESCE(?, albumName)
               WHERE id = ?""",
            t.title, t.thumbnail, t.durationSec, t.durationSec, t.albumId, t.albumName, t.id,
        )
        val hasArtists = query("SELECT COUNT(*) FROM song_artist_map WHERE songId = ?", t.id) { it.getInt(1) }.first() > 0
        if (!hasArtists) {
            t.artists.forEachIndexed { pos, a ->
                val artistId = a.id?.takeIf { it.isNotBlank() }
                    ?: query("SELECT id FROM artist WHERE name = ? LIMIT 1", a.name) { it.getString(1) }.firstOrNull()
                    ?: generateId("LA")
                exec(
                    """INSERT OR IGNORE INTO artist (id, name, thumbnailUrl, channelId, lastUpdateTime, bookmarkedAt, isLocal)
                       VALUES (?, ?, NULL, NULL, ?, NULL, 0)""",
                    artistId, a.name, nowRoom(),
                )
                exec("INSERT OR IGNORE INTO song_artist_map (songId, artistId, position) VALUES (?, ?, ?)", t.id, artistId, pos)
            }
        }
    }

    fun setLiked(t: Track, liked: Boolean) = write {
        it.upsertSongInternal(t)
        it.exec("UPDATE song SET liked = ?, likedDate = ? WHERE id = ?", liked, if (liked) nowRoom() else null, t.id)
    }

    fun setInLibrary(t: Track, inLibrary: Boolean) = write {
        it.upsertSongInternal(t)
        it.exec("UPDATE song SET inLibrary = ? WHERE id = ?", if (inLibrary) nowRoom() else null, t.id)
    }

    fun isLiked(id: String): Boolean = read {
        it.query("SELECT liked FROM song WHERE id = ?", id) { rs -> rs.getInt(1) == 1 }.firstOrNull() ?: false
    }

    /** Registra una reproducción (tabla event + totalPlayTime + playCount), como MusicService en Android. */
    fun recordPlay(t: Track, playTimeMs: Long) = write {
        it.upsertSongInternal(t)
        it.exec("INSERT INTO event (songId, timestamp, playTime) VALUES (?, ?, ?)", t.id, nowRoom(), playTimeMs)
        it.exec("UPDATE song SET totalPlayTime = totalPlayTime + ? WHERE id = ?", playTimeMs, t.id)
        val now = LocalDateTime.now().atOffset(ZoneOffset.UTC)
        it.exec("INSERT OR IGNORE INTO playCount (song, year, month, count) VALUES (?, ?, ?, 0)", t.id, now.year, now.monthValue)
        it.exec("UPDATE playCount SET count = count + 1 WHERE song = ? AND year = ? AND month = ?", t.id, now.year, now.monthValue)
    }

    private fun Connection.artistsOf(songId: String): List<ArtistRef> =
        query(
            """SELECT a.id, a.name FROM song_artist_map m JOIN artist a ON a.id = m.artistId
               WHERE m.songId = ? ORDER BY m.position""",
            songId,
        ) { rs ->
            val id = rs.getString(1)
            ArtistRef(if (id.startsWith("LA")) null else id, rs.getString(2))
        }

    private fun Connection.tracks(sql: String, vararg args: Any?): List<Track> {
        val rows = query(sql, *args) { rs -> rowSnapshot(rs) }
        return rows.map { trackFromSnapshot(it) }
    }

    // Se copia la fila antes de consultar artistas (evita anidar ResultSets abiertos).
    private data class Row(
        val id: String, val title: String, val albumId: String?, val albumName: String?, val thumb: String?,
        val duration: Int, val explicit: Boolean, val isVideo: Boolean, val liked: Boolean, val inLibrary: Boolean,
        val mapId: Long?, val extra: Long?,
    )

    private fun rowSnapshot(rs: ResultSet): Row {
        val cols = (1..rs.metaData.columnCount).map { rs.metaData.getColumnLabel(it) }.toSet()
        return Row(
            id = rs.getString("id"),
            title = rs.getString("title"),
            albumId = rs.getString("albumId"),
            albumName = rs.getString("albumName"),
            thumb = rs.getString("thumbnailUrl"),
            duration = rs.getInt("duration"),
            explicit = rs.getInt("explicit") == 1,
            isVideo = rs.getInt("isVideo") == 1,
            liked = rs.getInt("liked") == 1,
            inLibrary = rs.longOrNull("inLibrary") != null,
            mapId = if ("mapId" in cols) rs.longOrNull("mapId") else null,
            extra = if ("extra" in cols) rs.longOrNull("extra") else null,
        )
    }

    private fun Connection.trackFromSnapshot(r: Row) = Track(
        id = r.id, title = r.title, artists = artistsOf(r.id), albumId = r.albumId, albumName = r.albumName,
        thumbnail = r.thumb, durationSec = r.duration, explicit = r.explicit, isVideo = r.isVideo,
        liked = r.liked, inLibrary = r.inLibrary, mapId = r.mapId,
    )

    fun likedSongs(): List<Track> = read { it.tracks("SELECT * FROM song WHERE liked = 1 ORDER BY likedDate DESC") }

    fun librarySongs(): List<Track> = read {
        it.tracks("SELECT * FROM song WHERE inLibrary IS NOT NULL OR liked = 1 ORDER BY COALESCE(inLibrary, likedDate) DESC")
    }

    fun mostPlayed(limit: Int = 50): List<Track> = read {
        it.tracks("SELECT * FROM song WHERE totalPlayTime > 0 ORDER BY totalPlayTime DESC LIMIT ?", limit)
    }

    fun history(limit: Int = 200): List<HistoryEntry> = read { c ->
        val rows = c.query(
            """SELECT s.*, e.timestamp AS extra FROM event e JOIN song s ON s.id = e.songId
               ORDER BY e.timestamp DESC LIMIT ?""",
            limit,
        ) { rs -> rowSnapshot(rs) }
        rows.map { HistoryEntry(c.trackFromSnapshot(it), it.extra ?: 0L) }
    }

    fun song(id: String): Track? = read { it.tracks("SELECT * FROM song WHERE id = ?", id).firstOrNull() }

    // ───────────────────────── listas de reproducción ─────────────────────────

    fun playlists(): List<LocalPlaylist> = read { c ->
        val base = c.query(
            """SELECT p.id, p.name, p.browseId, p.thumbnailUrl,
                 (SELECT COUNT(*) FROM playlist_song_map m WHERE m.playlistId = p.id) AS cnt
               FROM playlist p ORDER BY p.createdAt DESC""",
        ) { rs -> listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5).toString()) }
        base.map { r ->
            val thumbs = r[3]?.let { listOf(it) } ?: c.query(
                """SELECT s.thumbnailUrl FROM playlist_song_map m JOIN song s ON s.id = m.songId
                   WHERE m.playlistId = ? AND s.thumbnailUrl IS NOT NULL ORDER BY m.position LIMIT 4""",
                r[0],
            ) { it.getString(1) }
            LocalPlaylist(id = r[0]!!, name = r[1]!!, browseId = r[2], songCount = r[4]!!.toInt(), thumbnails = thumbs)
        }
    }

    fun playlist(id: String): LocalPlaylist? = playlists().firstOrNull { it.id == id }

    fun playlistSongs(playlistId: String): List<Track> = read {
        it.tracks(
            """SELECT s.*, m.id AS mapId FROM playlist_song_map m JOIN song s ON s.id = m.songId
               WHERE m.playlistId = ? ORDER BY m.position""",
            playlistId,
        )
    }

    fun createPlaylist(name: String, browseId: String? = null): String = write { c ->
        val id = generateId("LP")
        val now = nowRoom()
        c.exec(
            """INSERT INTO playlist (id, name, browseId, createdAt, lastUpdateTime, isEditable, bookmarkedAt,
                 remoteSongCount, playEndpointParams, thumbnailUrl, shuffleEndpointParams, radioEndpointParams, isLocal, isAutoSync)
               VALUES (?, ?, ?, ?, ?, 1, NULL, NULL, NULL, NULL, NULL, NULL, 0, 0)""",
            id, name, browseId, now, now,
        )
        id
    }

    fun renamePlaylist(id: String, name: String) = write {
        it.exec("UPDATE playlist SET name = ?, lastUpdateTime = ? WHERE id = ?", name, nowRoom(), id)
    }

    fun deletePlaylist(id: String) = write {
        it.exec("DELETE FROM playlist_song_map WHERE playlistId = ?", id)
        it.exec("DELETE FROM playlist WHERE id = ?", id)
    }

    fun addToPlaylist(playlistId: String, tracks: List<Track>) = write { c ->
        tracks.forEach { t ->
            c.upsertSongInternal(t)
            val next = c.query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_song_map WHERE playlistId = ?", playlistId) {
                it.getInt(1)
            }.first()
            c.exec("INSERT INTO playlist_song_map (playlistId, songId, position, setVideoId) VALUES (?, ?, ?, NULL)", playlistId, t.id, next)
        }
        c.exec("UPDATE playlist SET lastUpdateTime = ? WHERE id = ?", nowRoom(), playlistId)
    }

    fun removeFromPlaylist(playlistId: String, mapId: Long) = write { c ->
        c.exec("DELETE FROM playlist_song_map WHERE id = ?", mapId)
        c.renumber(playlistId)
        c.exec("UPDATE playlist SET lastUpdateTime = ? WHERE id = ?", nowRoom(), playlistId)
    }

    fun movePlaylistSong(playlistId: String, from: Int, to: Int) = write { c ->
        val ids = c.query("SELECT id FROM playlist_song_map WHERE playlistId = ? ORDER BY position", playlistId) { it.getLong(1) }
            .toMutableList()
        if (from !in ids.indices || to !in ids.indices) return@write
        ids.add(to, ids.removeAt(from))
        ids.forEachIndexed { pos, id -> c.exec("UPDATE playlist_song_map SET position = ? WHERE id = ?", pos, id) }
        c.exec("UPDATE playlist SET lastUpdateTime = ? WHERE id = ?", nowRoom(), playlistId)
    }

    private fun Connection.renumber(playlistId: String) {
        val ids = query("SELECT id FROM playlist_song_map WHERE playlistId = ? ORDER BY position", playlistId) { it.getLong(1) }
        ids.forEachIndexed { pos, id -> exec("UPDATE playlist_song_map SET position = ? WHERE id = ?", pos, id) }
    }

    // ───────────────────────── álbumes y artistas guardados ─────────────────────────

    fun saveAlbum(id: String, playlistId: String?, title: String, year: Int?, thumbnail: String?, artists: List<ArtistRef>, songs: List<Track>) =
        write { c ->
            val now = nowRoom()
            c.exec(
                """INSERT OR IGNORE INTO album (id, playlistId, title, year, thumbnailUrl, themeColor, songCount, duration,
                     explicit, lastUpdateTime, bookmarkedAt, likedDate, inLibrary, description, isLocal, isUploaded)
                   VALUES (?, ?, ?, ?, ?, NULL, ?, ?, 0, ?, NULL, NULL, NULL, NULL, 0, 0)""",
                id, playlistId, title, year, thumbnail, songs.size, songs.sumOf { maxOf(it.durationSec, 0) }, now,
            )
            c.exec("UPDATE album SET bookmarkedAt = ?, inLibrary = ? WHERE id = ?", now, now, id)
            artists.forEachIndexed { i, a ->
                val aid = a.id ?: return@forEachIndexed
                c.exec("INSERT OR IGNORE INTO artist (id, name, thumbnailUrl, channelId, lastUpdateTime, bookmarkedAt, isLocal) VALUES (?, ?, NULL, NULL, ?, NULL, 0)", aid, a.name, now)
                c.exec("INSERT OR IGNORE INTO album_artist_map (albumId, artistId, `order`) VALUES (?, ?, ?)", id, aid, i)
            }
            songs.forEachIndexed { i, s ->
                c.upsertSongInternal(s.copy(albumId = id, albumName = title))
                c.exec("INSERT OR IGNORE INTO song_album_map (songId, albumId, `index`) VALUES (?, ?, ?)", s.id, id, i)
            }
        }

    fun unsaveAlbum(id: String) = write { it.exec("UPDATE album SET bookmarkedAt = NULL, inLibrary = NULL WHERE id = ?", id) }

    fun isAlbumSaved(id: String): Boolean = read {
        it.query("SELECT bookmarkedAt FROM album WHERE id = ?", id) { rs -> rs.longOrNull("bookmarkedAt") != null }.firstOrNull() ?: false
    }

    fun savedAlbums(): List<LocalAlbum> = read { c ->
        c.query(
            """SELECT al.id, al.title, al.thumbnailUrl, al.year,
                 (SELECT GROUP_CONCAT(a.name, ', ') FROM album_artist_map m JOIN artist a ON a.id = m.artistId WHERE m.albumId = al.id) AS artists
               FROM album al WHERE al.bookmarkedAt IS NOT NULL ORDER BY al.bookmarkedAt DESC""",
        ) { rs -> LocalAlbum(rs.getString(1), rs.getString(2), rs.getString(3), rs.intOrNull("year"), rs.getString(5)) }
    }

    fun setArtistBookmarked(id: String, name: String, thumbnail: String?, bookmarked: Boolean) = write { c ->
        c.exec(
            "INSERT OR IGNORE INTO artist (id, name, thumbnailUrl, channelId, lastUpdateTime, bookmarkedAt, isLocal) VALUES (?, ?, ?, NULL, ?, NULL, 0)",
            id, name, thumbnail, nowRoom(),
        )
        c.exec("UPDATE artist SET bookmarkedAt = ?, thumbnailUrl = COALESCE(?, thumbnailUrl) WHERE id = ?", if (bookmarked) nowRoom() else null, thumbnail, id)
    }

    fun isArtistBookmarked(id: String): Boolean = read {
        it.query("SELECT bookmarkedAt FROM artist WHERE id = ?", id) { rs -> rs.longOrNull("bookmarkedAt") != null }.firstOrNull() ?: false
    }

    fun bookmarkedArtists(): List<LocalArtist> = read { c ->
        c.query("SELECT id, name, thumbnailUrl FROM artist WHERE bookmarkedAt IS NOT NULL ORDER BY bookmarkedAt DESC") { rs ->
            LocalArtist(rs.getString(1), rs.getString(2), rs.getString(3))
        }
    }

    // ───────────────────────── búsqueda y letras ─────────────────────────

    fun addSearch(query: String) = write {
        it.exec("DELETE FROM search_history WHERE query = ?", query)
        it.exec("INSERT INTO search_history (query) VALUES (?)", query)
    }

    fun searchHistory(limit: Int = 20): List<String> = read {
        it.query("SELECT query FROM search_history ORDER BY id DESC LIMIT ?", limit) { rs -> rs.getString(1) }
    }

    fun clearSearchHistory() = write { it.exec("DELETE FROM search_history") }

    fun lyrics(id: String): String? = read {
        it.query("SELECT lyrics FROM lyrics WHERE id = ?", id) { rs -> rs.getString(1) }.firstOrNull()
    }

    fun saveLyrics(id: String, lyrics: String, provider: String) = write {
        it.exec(
            """INSERT OR REPLACE INTO lyrics (id, lyrics, provider, translatedLyrics, translationLanguage, translationMode)
               VALUES (?, ?, ?, '', '', '')""",
            id, lyrics, provider,
        )
    }

    /** Copia consistente de la base de datos (para exportar/sincronizar). */
    fun snapshotTo(target: File) = synchronized(lock) {
        target.delete()
        conn.createStatement().use { it.execute("VACUUM INTO '${target.absolutePath.replace("'", "''")}'") }
    }

    companion object {
        /** Igual que el TypeConverter de Room en Android. */
        fun nowRoom(): Long = LocalDateTime.now().toInstant(ZoneOffset.UTC).toEpochMilli()

        private val ALNUM = ('a'..'z') + ('A'..'Z')
        fun generateId(prefix: String): String = prefix + (1..8).map { ALNUM.random() }.joinToString("")
    }
}
