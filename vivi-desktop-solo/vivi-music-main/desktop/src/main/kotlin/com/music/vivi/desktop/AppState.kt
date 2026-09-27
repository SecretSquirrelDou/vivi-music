package com.music.vivi.desktop

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.music.innertube.YouTube
import com.music.innertube.models.BrowseEndpoint
import com.music.innertube.models.YouTubeLocale
import com.music.vivi.desktop.data.AppDirs
import com.music.vivi.desktop.data.MusicDatabase
import com.music.vivi.desktop.data.SettingsStore
import com.music.vivi.desktop.data.Track
import com.music.vivi.desktop.lyrics.LyricsRepository
import com.music.vivi.desktop.playback.PlayerController
import com.music.vivi.desktop.playback.StreamResolver
import com.music.vivi.desktop.sync.SyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale

/** Pantallas de la app (navegación simple con pila). */
sealed interface Screen {
    data object Home : Screen
    data object Explore : Screen
    data class Search(val query: String) : Screen
    data object Library : Screen
    data object Liked : Screen
    data object History : Screen
    data object Settings : Screen
    data class LocalPlaylist(val id: String) : Screen
    data class OnlinePlaylist(val id: String) : Screen
    data class Album(val browseId: String) : Screen
    data class Artist(val browseId: String) : Screen
    data class ArtistItems(val title: String, val endpoint: BrowseEndpoint) : Screen
    data class Browse(val title: String, val endpoint: BrowseEndpoint) : Screen
}

/** Contenedor de servicios (equivalente al grafo de Hilt en Android). */
class Services {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val settings = SettingsStore()
    val db = MusicDatabase(AppDirs.database)
    val player = PlayerController(db, settings, scope)
    val sync = SyncManager(db, settings, scope)
    val lyrics = LyricsRepository(db)

    init {
        val s = settings.value
        StreamResolver.quality = s.audioQuality
        val locale = Locale.getDefault()
        YouTube.locale = YouTubeLocale(
            gl = s.contentCountry ?: locale.country.ifBlank { "US" },
            hl = s.contentLanguage ?: locale.toLanguageTag(),
        )
        applyCookie(s.cookie)
        if (s.visitorData != null) YouTube.visitorData = s.visitorData
        scope.launch(Dispatchers.IO) {
            if (YouTube.visitorData == null) {
                YouTube.refreshVisitorData().onSuccess { v -> settings.update { it.copy(visitorData = v) } }
            }
        }
        sync.start()
    }

    fun applyCookie(cookie: String?) {
        YouTube.cookie = cookie?.takeIf { it.contains("SAPISID") }
        YouTube.useLoginForBrowse = YouTube.cookie != null
    }

    // ───── acciones compartidas por varias pantallas (con "lápidas" para la sincronización) ─────

    fun toggleLike(t: Track) {
        val liked = !db.isLiked(t.id)
        db.setLiked(t, liked)
        if (liked) settings.removeTombstone("unlike", t.id) else settings.addTombstone("unlike", t.id)
        if (YouTube.cookie != null) scope.launch(Dispatchers.IO) { YouTube.likeVideo(t.id, liked) }
    }

    fun toggleLibrary(t: Track) {
        val inLib = db.song(t.id)?.inLibrary == true
        db.setInLibrary(t, !inLib)
        if (!inLib) settings.removeTombstone("library", t.id) else settings.addTombstone("library", t.id)
    }

    fun deletePlaylist(id: String) {
        db.deletePlaylist(id)
        settings.addTombstone("playlist", id)
    }

    fun removeFromPlaylist(playlistId: String, t: Track) {
        val mapId = t.mapId ?: return
        db.removeFromPlaylist(playlistId, mapId)
        settings.addTombstone("playlist_song", "$playlistId|${t.id}")
    }

    fun addToPlaylist(playlistId: String, tracks: List<Track>) {
        db.addToPlaylist(playlistId, tracks)
        tracks.forEach { settings.removeTombstone("playlist_song", "$playlistId|${it.id}") }
    }

    fun setArtistBookmarked(id: String, name: String, thumb: String?, bookmarked: Boolean) {
        db.setArtistBookmarked(id, name, thumb, bookmarked)
        if (bookmarked) settings.removeTombstone("artist", id) else settings.addTombstone("artist", id)
    }

    fun unsaveAlbum(id: String) {
        db.unsaveAlbum(id)
        settings.addTombstone("album", id)
    }

    fun shutdown() {
        player.shutdown()
        // Último respaldo al cerrar (si hubo cambios)
        if (sync.folder != null && db.lastWriteAt > settings.value.lastExportAt) {
            runCatching { sync.exportTo(sync.folder!!) }
        }
        db.close()
    }
}

@Stable
class Navigator {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val current: Screen get() = stack.last()
    var showNowPlaying by mutableStateOf(true)
    var rightTab by mutableStateOf(0) // 0 = cola, 1 = letras
    var addToPlaylist by mutableStateOf<List<Track>?>(null)
    var snackbar by mutableStateOf<String?>(null)

    fun go(screen: Screen) {
        if (current == screen) return
        stack.add(screen)
        if (stack.size > 50) stack.removeAt(0)
    }

    /** Para las secciones principales: reinicia la pila. */
    fun root(screen: Screen) {
        stack.clear()
        stack.add(screen)
    }

    fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    fun toast(msg: String) {
        snackbar = msg
    }
}
