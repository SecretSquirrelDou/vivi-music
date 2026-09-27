package com.music.vivi.desktop.data

import com.music.innertube.models.SongItem
import com.music.innertube.models.WatchEndpoint

/** Referencia a un artista (igual que en Android: el id puede faltar). */
data class ArtistRef(val id: String?, val name: String)

/**
 * Canción tal como la maneja la app de escritorio. Se construye desde un [SongItem]
 * de innertube (resultados online) o desde la tabla `song` de la base de datos.
 */
data class Track(
    val id: String,
    val title: String,
    val artists: List<ArtistRef>,
    val albumId: String? = null,
    val albumName: String? = null,
    val thumbnail: String? = null,
    val durationSec: Int = -1,
    val explicit: Boolean = false,
    val isVideo: Boolean = false,
    val liked: Boolean = false,
    val inLibrary: Boolean = false,
    /** Para listas de reproducción locales: id de la fila en playlist_song_map. */
    val mapId: Long? = null,
    val endpoint: WatchEndpoint? = null,
) {
    val artistsText: String get() = artists.joinToString(", ") { it.name }
}

fun SongItem.toTrack(): Track = Track(
    id = id,
    title = title,
    artists = artists.map { ArtistRef(it.id, it.name) },
    albumId = album?.id,
    albumName = album?.name,
    thumbnail = thumbnail,
    durationSec = duration ?: -1,
    explicit = explicit,
    isVideo = isVideoSong,
    endpoint = endpoint,
)

data class LocalPlaylist(
    val id: String,
    val name: String,
    val browseId: String?,
    val songCount: Int,
    val thumbnails: List<String>,
)

data class LocalAlbum(
    val id: String,
    val title: String,
    val thumbnail: String?,
    val year: Int?,
    val artist: String?,
)

data class LocalArtist(
    val id: String,
    val name: String,
    val thumbnail: String?,
)

data class HistoryEntry(
    val track: Track,
    val timestampMillis: Long,
)
