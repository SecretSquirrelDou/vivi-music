package com.music.vivi.desktop.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.LibraryAddCheck
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.music.innertube.YouTube
import com.music.innertube.models.BrowseEndpoint
import com.music.innertube.models.SongItem
import com.music.innertube.models.YTItem
import com.music.vivi.desktop.LocalNavigator
import com.music.vivi.desktop.LocalServices
import com.music.vivi.desktop.Screen
import com.music.vivi.desktop.data.ArtistRef
import com.music.vivi.desktop.data.Track
import com.music.vivi.desktop.data.toTrack
import com.music.vivi.desktop.ui.components.CollectionHeader
import com.music.vivi.desktop.ui.components.ItemCard
import com.music.vivi.desktop.ui.components.ItemRow
import com.music.vivi.desktop.ui.components.LoadContent
import com.music.vivi.desktop.ui.components.PillButton
import com.music.vivi.desktop.ui.components.SectionHeader
import com.music.vivi.desktop.ui.components.SongRow
import com.music.vivi.desktop.ui.components.openItem
import com.music.vivi.desktop.ui.components.rememberLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ───────────────────────── Álbum ─────────────────────────

@Composable
fun AlbumScreen(browseId: String) {
    val services = LocalServices.current
    val player by services.player.state.collectAsState()
    val dbVersion by services.db.changes.collectAsState()
    val loader = rememberLoader(browseId) { YouTube.album(browseId) }
    LoadContent(loader) { page ->
        val tracks = remember(page) { page.songs.map { it.toTrack().copy(albumId = page.album.browseId, albumName = page.album.title) } }
        val saved = remember(dbVersion) { services.db.isAlbumSaved(page.album.browseId) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 28.dp)) {
            item {
                CollectionHeader(
                    thumbnail = page.album.thumbnail,
                    kind = "Álbum",
                    title = page.album.title,
                    subtitle = listOfNotNull(page.album.artists?.joinToString(", ") { it.name }, page.album.year?.toString(), "${tracks.size} canciones").joinToString(" • "),
                    description = page.description,
                ) {
                    PillButton("Reproducir", Icons.Rounded.PlayArrow) { services.player.playQueue(tracks, 0, page.album.title) }
                    PillButton("Aleatorio", Icons.Rounded.Shuffle, filled = false) { services.player.playQueue(tracks.shuffled(), 0, page.album.title) }
                    PillButton(if (saved) "Guardado" else "Guardar", if (saved) Icons.Rounded.LibraryAddCheck else Icons.Rounded.LibraryAdd, filled = false) {
                        if (saved) services.unsaveAlbum(page.album.browseId)
                        else services.db.saveAlbum(
                            page.album.browseId, page.album.playlistId, page.album.title, page.album.year, page.album.thumbnail,
                            page.album.artists.orEmpty().map { ArtistRef(it.id, it.name) }, tracks,
                        )
                    }
                }
            }
            itemsIndexed(tracks) { i, t ->
                SongRow(t, index = i, isPlaying = player.current?.id == t.id, showAlbum = false) { services.player.playQueue(tracks, i, page.album.title) }
            }
            if (page.otherVersions.isNotEmpty()) {
                item { SectionHeader("Otras versiones") }
                item { ItemRow(page.otherVersions) }
            }
        }
    }
}

// ───────────────────────── Playlist online ─────────────────────────

@Composable
fun OnlinePlaylistScreen(playlistId: String) {
    val services = LocalServices.current
    val nav = LocalNavigator.current
    val player by services.player.state.collectAsState()
    val scope = rememberCoroutineScope()
    val loader = rememberLoader(playlistId) { YouTube.playlist(playlistId) }
    LoadContent(loader) { page ->
        val songs = remember(page) { mutableStateListOf<Track>().apply { addAll(page.songs.map { it.toTrack() }) } }
        var continuation by remember(page) { mutableStateOf(page.songsContinuation ?: page.continuation) }
        var loadingMore by remember(page) { mutableStateOf(false) }

        LaunchedEffect(page) {
            // Carga automática de toda la lista (en segundo plano, por bloques)
            var guard = 0
            while (continuation != null && guard++ < 40) {
                loadingMore = true
                val c = continuation!!
                withContext(Dispatchers.IO) { YouTube.playlistContinuation(c) }
                    .onSuccess { cont -> songs.addAll(cont.songs.map { it.toTrack() }); continuation = cont.continuation }
                    .onFailure { continuation = null }
            }
            loadingMore = false
        }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 28.dp)) {
            item {
                CollectionHeader(
                    thumbnail = page.playlist.thumbnail,
                    kind = "Lista de reproducción",
                    title = page.playlist.title,
                    subtitle = listOfNotNull(page.playlist.author?.name, page.playlist.songCountText ?: "${songs.size} canciones").joinToString(" • "),
                    description = page.playlist.description,
                ) {
                    PillButton("Reproducir", Icons.Rounded.PlayArrow) { services.player.playQueue(songs.toList(), 0, page.playlist.title) }
                    PillButton("Aleatorio", Icons.Rounded.Shuffle, filled = false) { services.player.playQueue(songs.shuffled(), 0, page.playlist.title) }
                    page.playlist.radioEndpoint?.let { ep ->
                        PillButton("Radio", Icons.Rounded.Radio, filled = false) { services.player.playEndpoint(ep, "Radio · ${page.playlist.title}") }
                    }
                    PillButton("Importar a mis listas", Icons.Rounded.PlaylistAdd, filled = false) {
                        scope.launch {
                            val id = services.db.createPlaylist(page.playlist.title, browseId = page.playlist.id)
                            services.addToPlaylist(id, songs.toList())
                            nav.toast("Lista importada: se sincronizará con tu móvil")
                            nav.go(Screen.LocalPlaylist(id))
                        }
                    }
                }
            }
            itemsIndexed(songs) { i, t ->
                SongRow(t, index = i, isPlaying = player.current?.id == t.id) { services.player.playQueue(songs.toList(), i, page.playlist.title) }
            }
            if (loadingMore) item { Loading() }
            page.related?.takeIf { it.isNotEmpty() }?.let { related ->
                item { SectionHeader("Relacionadas") }
                item { ItemRow(related) }
            }
        }
    }
}

// ───────────────────────── Artista ─────────────────────────

@Composable
fun ArtistScreen(browseId: String) {
    val services = LocalServices.current
    val nav = LocalNavigator.current
    val player by services.player.state.collectAsState()
    val dbVersion by services.db.changes.collectAsState()
    val loader = rememberLoader(browseId) { YouTube.artist(browseId) }
    LoadContent(loader) { page ->
        val artist = page.artist
        val bookmarked = remember(dbVersion) { services.db.isArtistBookmarked(artist.id) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 28.dp)) {
            item {
                CollectionHeader(
                    thumbnail = artist.thumbnail,
                    kind = "Artista",
                    title = artist.title,
                    subtitle = listOfNotNull(page.subscriberCountText, page.monthlyListenerCount).joinToString(" • ").ifBlank { null },
                    description = page.description,
                    shape = CircleShape,
                ) {
                    artist.shuffleEndpoint?.let { ep -> PillButton("Aleatorio", Icons.Rounded.Shuffle) { services.player.playEndpoint(ep, artist.title) } }
                    artist.radioEndpoint?.let { ep -> PillButton("Radio", Icons.Rounded.Radio, filled = false) { services.player.playEndpoint(ep, "Radio · ${artist.title}") } }
                    PillButton(if (bookmarked) "Siguiendo" else "Seguir", if (bookmarked) Icons.Rounded.Check else Icons.Rounded.PersonAdd, filled = false) {
                        services.setArtistBookmarked(artist.id, artist.title, artist.thumbnail, !bookmarked)
                    }
                }
            }
            page.sections.forEach { section ->
                item {
                    SectionHeader(section.title) {
                        section.moreEndpoint?.let { ep -> TextButton(onClick = { nav.go(Screen.ArtistItems(section.title, ep)) }) { Text("Ver todo") } }
                    }
                }
                val songs = section.items.filterIsInstance<SongItem>()
                if (songs.isNotEmpty() && songs.size == section.items.size && songs.none { it.isVideoSong && section.items.size < 3 }) {
                    val tracks = songs.map { it.toTrack() }
                    itemsIndexed(tracks) { i, t ->
                        SongRow(t, isPlaying = player.current?.id == t.id) { services.player.playQueue(tracks, i, artist.title) }
                    }
                } else {
                    item { ItemRow(section.items) }
                }
            }
        }
    }
}

// ───────────────────────── "Ver todo" (artista) y Browse genérico ─────────────────────────

@Composable
fun ArtistItemsScreen(title: String, endpoint: BrowseEndpoint) {
    val loader = rememberLoader(endpoint) { YouTube.artistItems(endpoint) }
    LoadContent(loader) { page ->
        val items = remember(page) { mutableStateListOf<YTItem>().apply { addAll(page.items) } }
        LaunchedEffect(page) {
            var c = page.continuation
            var guard = 0
            while (c != null && guard++ < 20) {
                val cont = withContext(Dispatchers.IO) { YouTube.artistItemsContinuation(c!!) }.getOrNull() ?: break
                items.addAll(cont.items)
                c = cont.continuation
            }
        }
        ItemsGrid(page.title.ifBlank { title }, items)
    }
}

@Composable
fun BrowseScreen(title: String, endpoint: BrowseEndpoint) {
    val loader = rememberLoader(endpoint) { YouTube.browse(endpoint.browseId, endpoint.params) }
    LoadContent(loader) { result ->
        val services = LocalServices.current
        val player by services.player.state.collectAsState()
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 28.dp)) {
            item { SectionTitle(result.title ?: title) }
            result.items.forEach { group ->
                group.title?.let { t -> item { SectionHeader(t) } }
                val songs = group.items.filterIsInstance<SongItem>()
                if (songs.size == group.items.size && songs.isNotEmpty()) {
                    val tracks = songs.map { it.toTrack() }
                    itemsIndexed(tracks) { i, t -> SongRow(t, isPlaying = player.current?.id == t.id) { services.player.playQueue(tracks, i) } }
                } else item { ItemRow(group.items) }
            }
        }
    }
}

@Composable
fun ItemsGrid(title: String, items: List<YTItem>) {
    val nav = LocalNavigator.current
    val services = LocalServices.current
    LazyVerticalGrid(GridCells.Adaptive(180.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle(title) }
        items(items) { item ->
            if (item is SongItem) {
                val t = item.toTrack()
                ItemCard(item, 180) { services.player.playWithRadio(t) }
            } else ItemCard(item, 180) { openItem(item, nav, services) }
        }
    }
}

