package com.music.vivi.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.vivi.desktop.LocalNavigator
import com.music.vivi.desktop.LocalServices
import com.music.vivi.desktop.Screen
import com.music.vivi.desktop.data.LocalPlaylist
import com.music.vivi.desktop.data.Track
import com.music.vivi.desktop.ui.components.CollectionHeader
import com.music.vivi.desktop.ui.components.EmptyState
import com.music.vivi.desktop.ui.components.NetworkImage
import com.music.vivi.desktop.ui.components.PillButton
import com.music.vivi.desktop.ui.components.PlaylistSongExtraMenu
import com.music.vivi.desktop.ui.components.SongRow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Lista de canciones local con cabecera y botones reproducir/aleatorio. */
@Composable
fun TrackListScreen(
    title: String,
    kind: String,
    tracks: List<Track>,
    emptyTitle: String,
    emptyMessage: String,
    thumbnail: String? = tracks.firstOrNull()?.thumbnail,
) {
    val services = LocalServices.current
    val player by services.player.state.collectAsState()
    if (tracks.isEmpty()) {
        EmptyState(Icons.Rounded.LibraryMusic, emptyTitle, emptyMessage)
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 28.dp)) {
        item {
            CollectionHeader(thumbnail, kind, title, "${tracks.size} canciones") {
                PillButton("Reproducir", Icons.Rounded.PlayArrow) { services.player.playQueue(tracks, 0, title) }
                PillButton("Aleatorio", Icons.Rounded.Shuffle, filled = false) { services.player.playQueue(tracks.shuffled(), 0, title) }
            }
        }
        itemsIndexed(tracks) { i, t ->
            SongRow(t, index = i, isPlaying = player.current?.id == t.id) { services.player.playQueue(tracks, i, title) }
        }
    }
}

@Composable
fun LikedScreen() {
    val services = LocalServices.current
    val v by services.db.changes.collectAsState()
    val tracks = remember(v) { services.db.likedSongs() }
    TrackListScreen("Me gusta", "Lista automática", tracks, "Aún no tienes favoritos", "Pulsa el corazón en cualquier canción. Se sincroniza con tu móvil.")
}

@Composable
fun HistoryScreen() {
    val services = LocalServices.current
    val player by services.player.state.collectAsState()
    val v by services.db.changes.collectAsState()
    val entries = remember(v) { services.db.history(500) }
    if (entries.isEmpty()) {
        EmptyState(Icons.Rounded.History, "Sin historial", "Lo que escuches aquí y en tu móvil aparecerá en esta lista.")
        return
    }
    val fmt = remember { DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", Locale.forLanguageTag("es")) }
    val grouped = remember(entries) {
        entries.groupBy { Instant.ofEpochMilli(it.timestampMillis).atOffset(ZoneOffset.UTC).toLocalDate() }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp)) {
        item { SectionTitle("Historial") }
        grouped.forEach { (date, list) ->
            item {
                val label = when (date) {
                    LocalDate.now() -> "Hoy"
                    LocalDate.now().minusDays(1) -> "Ayer"
                    else -> date.format(fmt).replaceFirstChar { it.uppercase() }
                }
                Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
            }
            itemsIndexed(list) { _, e ->
                SongRow(e.track, isPlaying = player.current?.id == e.track.id) { services.player.playWithRadio(e.track) }
            }
        }
    }
}

@Composable
fun LibraryScreen() {
    val services = LocalServices.current
    val nav = LocalNavigator.current
    val v by services.db.changes.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Listas", "Canciones", "Álbumes", "Artistas")
    Column(Modifier.fillMaxSize()) {
        Text("Biblioteca", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 28.dp, top = 20.dp, bottom = 8.dp))
        PrimaryTabRow(selectedTabIndex = tab, modifier = Modifier.padding(horizontal = 28.dp), containerColor = MaterialTheme.colorScheme.background) {
            tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
        }
        when (tab) {
            0 -> {
                val playlists = remember(v) { services.db.playlists() }
                PlaylistGrid(playlists, onCreate = { nav.addToPlaylist = emptyList() })
            }
            1 -> {
                val songs = remember(v) { services.db.librarySongs() }
                TrackListScreen("Canciones", "Biblioteca", songs, "Biblioteca vacía", "Añade canciones con el menú ⋮ → \"Añadir a la biblioteca\" o dales \"Me gusta\".")
            }
            2 -> {
                val albums = remember(v) { services.db.savedAlbums() }
                if (albums.isEmpty()) EmptyState(Icons.Rounded.LibraryMusic, "Sin álbumes guardados", "Guarda álbumes desde su página.")
                else LazyVerticalGrid(GridCells.Adaptive(180.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp)) {
                    items(albums) { a -> GridTile(a.thumbnail, a.title, listOfNotNull(a.artist, a.year?.toString()).joinToString(" • ")) { nav.go(Screen.Album(a.id)) } }
                }
            }
            else -> {
                val artists = remember(v) { services.db.bookmarkedArtists() }
                if (artists.isEmpty()) EmptyState(Icons.Rounded.LibraryMusic, "No sigues a ningún artista", "Pulsa \"Seguir\" en la página de un artista.")
                else LazyVerticalGrid(GridCells.Adaptive(180.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp)) {
                    items(artists) { a -> GridTile(a.thumbnail, a.name, "Artista", circle = true) { nav.go(Screen.Artist(a.id)) } }
                }
            }
        }
    }
}

@Composable
private fun PlaylistGrid(playlists: List<LocalPlaylist>, onCreate: () -> Unit) {
    val nav = LocalNavigator.current
    LazyVerticalGrid(GridCells.Adaptive(180.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp)) {
        item {
            Column(
                Modifier.padding(8.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onCreate).padding(8.dp),
            ) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) { Text("+", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onPrimaryContainer) }
                Spacer(Modifier.height(8.dp))
                Text("Nueva lista", style = MaterialTheme.typography.bodyLarge)
            }
        }
        items(playlists) { p ->
            GridTile(p.thumbnails.firstOrNull(), p.name, "${p.songCount} canciones") { nav.go(Screen.LocalPlaylist(p.id)) }
        }
    }
}

@Composable
fun GridTile(thumbnail: String?, title: String, subtitle: String, circle: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.padding(8.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(8.dp)) {
        NetworkImage(thumbnail, Modifier.fillMaxWidth().aspectRatio(1f), if (circle) CircleShape else RoundedCornerShape(12.dp), size = 400)
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
fun LocalPlaylistScreen(id: String) {
    val services = LocalServices.current
    val nav = LocalNavigator.current
    val player by services.player.state.collectAsState()
    val v by services.db.changes.collectAsState()
    val playlist = remember(v, id) { services.db.playlist(id) }
    val tracks = remember(v, id) { services.db.playlistSongs(id) }
    var rename by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (playlist == null) {
        EmptyState(Icons.Rounded.QueueMusic, "Lista no encontrada", "Puede que se haya eliminado.")
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 28.dp)) {
        item {
            CollectionHeader(playlist.thumbnails.firstOrNull(), "Tu lista", playlist.name, "${tracks.size} canciones") {
                PillButton("Reproducir", Icons.Rounded.PlayArrow) { services.player.playQueue(tracks, 0, playlist.name) }
                PillButton("Aleatorio", Icons.Rounded.Shuffle, filled = false) { services.player.playQueue(tracks.shuffled(), 0, playlist.name) }
                PillButton("Renombrar", Icons.Rounded.Edit, filled = false) { rename = true }
                PillButton("Eliminar", Icons.Rounded.Delete, filled = false) { confirmDelete = true }
            }
        }
        if (tracks.isEmpty()) item {
            Text("Esta lista está vacía. Usa ⋮ → \"Añadir a una lista…\" en cualquier canción.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        itemsIndexed(tracks) { i, t ->
            SongRow(
                t, index = i, isPlaying = player.current?.id == t.id,
                onClick = { services.player.playQueue(tracks, i, playlist.name) },
                extraMenu = { close -> PlaylistSongExtraMenu(id, t, i, tracks.size, close) },
            )
        }
    }

    if (rename) {
        var name by remember { mutableStateOf(playlist.name) }
        AlertDialog(
            onDismissRequest = { rename = false },
            title = { Text("Renombrar lista") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { services.db.renamePlaylist(id, name.trim().ifEmpty { playlist.name }); rename = false }) { Text("Guardar") } },
            dismissButton = { TextButton(onClick = { rename = false }) { Text("Cancelar") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("¿Eliminar \"${playlist.name}\"?") },
            text = { Text("La lista también se eliminará del móvil en la próxima sincronización (al restaurar el respaldo).") },
            confirmButton = { TextButton(onClick = { services.deletePlaylist(id); confirmDelete = false; nav.back() }) { Text("Eliminar") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }
}
