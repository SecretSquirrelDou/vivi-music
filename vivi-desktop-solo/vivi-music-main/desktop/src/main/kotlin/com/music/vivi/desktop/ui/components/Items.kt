package com.music.vivi.desktop.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.LibraryAddCheck
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.innertube.models.AlbumItem
import com.music.innertube.models.ArtistItem
import com.music.innertube.models.PlaylistItem
import com.music.innertube.models.SongItem
import com.music.innertube.models.YTItem
import com.music.vivi.desktop.LocalNavigator
import com.music.vivi.desktop.LocalServices
import com.music.vivi.desktop.Screen
import com.music.vivi.desktop.data.Track
import com.music.vivi.desktop.data.toTrack

/** Clic derecho → menú contextual (comportamiento típico de escritorio). */
fun Modifier.onRightClick(action: () -> Unit): Modifier = pointerInput(action) {
    awaitPointerEventScope {
        while (true) {
            val e = awaitPointerEvent()
            if (e.type == PointerEventType.Press && e.buttons.isSecondaryPressed) action()
        }
    }
}

fun formatDuration(sec: Int): String = if (sec <= 0) "" else "%d:%02d".format(sec / 60, sec % 60)
fun formatMs(ms: Long): String = formatDuration((ms / 1000).toInt()).ifEmpty { "0:00" }

/** Fila de canción usada en todas las listas (búsqueda, playlists, álbumes, biblioteca…). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    track: Track,
    index: Int? = null,
    isPlaying: Boolean = false,
    showAlbum: Boolean = true,
    extraMenu: (@Composable (close: () -> Unit) -> Unit)? = null,
    onClick: () -> Unit,
) {
    val services = LocalServices.current
    var menu by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val dbVersion by services.db.changes.collectAsState()
    val liked = remember(track.id, dbVersion) { services.db.isLiked(track.id) }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    isPlaying -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    hovered -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    else -> MaterialTheme.colorScheme.surface.copy(alpha = 0f)
                },
            )
            .hoverable(interaction)
            .clickable(onClick = onClick)
            .onRightClick { menu = true }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (index != null) {
            Box(Modifier.width(32.dp), contentAlignment = Alignment.Center) {
                if (hovered) Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                else Text("${index + 1}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(6.dp))
        }
        NetworkImage(track.thumbnail, Modifier.size(46.dp), RoundedCornerShape(8.dp), size = 120)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1.4f)) {
            Text(
                track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Medium,
                color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (track.explicit) {
                    Text(
                        "E",
                        Modifier.clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.onSurfaceVariant).padding(horizontal = 4.dp),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.surface,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    track.artistsText, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (showAlbum) {
            Text(
                track.albumName.orEmpty(), Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 1,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { services.toggleLike(track) }) {
            Icon(
                if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Me gusta",
                tint = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (hovered) 1f else 0.4f),
            )
        }
        Text(formatDuration(track.durationSec), Modifier.width(48.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Más") }
            SongMenu(track, menu, onDismiss = { menu = false }, extraMenu = extraMenu)
        }
    }
}

@Composable
fun SongMenu(track: Track, expanded: Boolean, onDismiss: () -> Unit, extraMenu: (@Composable (close: () -> Unit) -> Unit)? = null) {
    val services = LocalServices.current
    val nav = LocalNavigator.current
    val clipboard = LocalClipboardManager.current
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        MenuItem("Reproducir siguiente", Icons.Rounded.PlaylistPlay) { services.player.playNext(track); nav.toast("Se reproducirá a continuación"); onDismiss() }
        MenuItem("Añadir a la cola", Icons.Rounded.QueueMusic) { services.player.addToQueue(listOf(track)); nav.toast("Añadida a la cola"); onDismiss() }
        MenuItem("Iniciar radio", Icons.Rounded.Radio) { services.player.playWithRadio(track); onDismiss() }
        MenuItem("Añadir a una lista…", Icons.Rounded.PlaylistAdd) { nav.addToPlaylist = listOf(track); onDismiss() }
        val inLib = services.db.song(track.id)?.inLibrary == true
        MenuItem(if (inLib) "Quitar de la biblioteca" else "Añadir a la biblioteca", if (inLib) Icons.Rounded.LibraryAddCheck else Icons.Rounded.LibraryAdd) {
            services.toggleLibrary(track); onDismiss()
        }
        HorizontalDivider()
        track.artists.firstOrNull { it.id != null }?.let { a ->
            MenuItem("Ir a ${a.name}", Icons.Rounded.Person) { nav.go(Screen.Artist(a.id!!)); onDismiss() }
        }
        track.albumId?.let { id ->
            MenuItem("Ir al álbum", Icons.Rounded.Album) { nav.go(Screen.Album(id)); onDismiss() }
        }
        MenuItem("Copiar enlace", Icons.Rounded.ContentCopy) {
            clipboard.setText(AnnotatedString("https://music.youtube.com/watch?v=${track.id}")); nav.toast("Enlace copiado"); onDismiss()
        }
        extraMenu?.let { HorizontalDivider(); it(onDismiss) }
    }
}

@Composable
fun MenuItem(text: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, leadingIcon = { Icon(icon, null) }, onClick = onClick)
}

@Composable
fun PlaylistSongExtraMenu(playlistId: String, track: Track, position: Int, total: Int, close: () -> Unit) {
    val services = LocalServices.current
    if (position > 0) MenuItem("Subir", Icons.Rounded.KeyboardArrowUp) { services.db.movePlaylistSong(playlistId, position, position - 1); close() }
    if (position < total - 1) MenuItem("Bajar", Icons.Rounded.KeyboardArrowDown) { services.db.movePlaylistSong(playlistId, position, position + 1); close() }
    MenuItem("Quitar de la lista", Icons.Rounded.Delete) { services.removeFromPlaylist(playlistId, track); close() }
}

// ───────────────────────── tarjetas (álbum / playlist / artista / canción) ─────────────────────────

fun YTItem.subtitle(): String = when (this) {
    is SongItem -> artists.joinToString(", ") { it.name }
    is AlbumItem -> listOfNotNull(artists?.joinToString(", ") { it.name }, year?.toString()).joinToString(" • ")
    is PlaylistItem -> listOfNotNull(author?.name, songCountText).joinToString(" • ")
    is ArtistItem -> subtext ?: "Artista"
}

/** Acción al hacer clic en cualquier elemento de YouTube Music (igual que en Android). */
fun openItem(item: YTItem, nav: com.music.vivi.desktop.Navigator, services: com.music.vivi.desktop.Services) {
    when (item) {
        is SongItem -> services.player.playWithRadio(item.toTrack())
        is AlbumItem -> nav.go(Screen.Album(item.browseId))
        is PlaylistItem -> nav.go(Screen.OnlinePlaylist(item.id))
        is ArtistItem -> nav.go(Screen.Artist(item.id))
    }
}

@Composable
fun ItemCard(item: YTItem, width: Int = 170, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var menu by remember { mutableStateOf(false) }
    val circle = item is ArtistItem
    Column(
        Modifier
            .width(width.dp)
            .clip(RoundedCornerShape(14.dp))
            .hoverable(interaction)
            .background(if (hovered) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface.copy(alpha = 0f))
            .clickable(onClick = onClick)
            .onRightClick { if (item is SongItem) menu = true }
            .padding(8.dp),
    ) {
        Box {
            NetworkImage(
                item.thumbnail,
                Modifier.fillMaxWidth().aspectRatio(if (item is SongItem && item.isVideoSong) 16f / 9f else 1f),
                if (circle) CircleShape else RoundedCornerShape(12.dp),
                size = 400,
            )
            if (hovered && !circle) {
                Surface(
                    shape = CircleShape, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).size(40.dp),
                    shadowElevation = 4.dp,
                ) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.PlayArrow, null, tint = MaterialTheme.colorScheme.onPrimary) }
                }
            }
            if (item is SongItem) SongMenu(item.toTrack(), menu, onDismiss = { menu = false })
        }
        Spacer(Modifier.height(8.dp))
        Text(
            item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth(),
        )
        Text(
            item.subtitle(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun SectionHeader(title: String, subtitle: String? = null, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            subtitle?.let { Text(it.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(title, style = MaterialTheme.typography.headlineSmall)
        }
        action?.invoke()
    }
}

@Composable
fun ItemRow(items: List<YTItem>, cardWidth: Int = 170) {
    val nav = LocalNavigator.current
    val services = LocalServices.current
    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(end = 16.dp)) {
        items(items) { item ->
            ItemCard(item, cardWidth) { openItem(item, nav, services) }
        }
    }
}
