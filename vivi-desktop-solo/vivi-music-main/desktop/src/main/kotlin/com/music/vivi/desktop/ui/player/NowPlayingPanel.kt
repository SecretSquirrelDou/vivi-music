package com.music.vivi.desktop.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.vivi.desktop.LocalNavigator
import com.music.vivi.desktop.LocalServices
import com.music.vivi.desktop.lyrics.Lyrics
import com.music.vivi.desktop.ui.components.NetworkImage
import com.music.vivi.desktop.ui.components.formatDuration

/** Panel derecho: carátula grande + pestañas Cola / Letras (sustituye la pantalla completa del reproductor de Android). */
@Composable
fun NowPlayingPanel(modifier: Modifier = Modifier) {
    val services = LocalServices.current
    val nav = LocalNavigator.current
    val state by services.player.state.collectAsState()
    val track = state.current

    Surface(modifier.fillMaxHeight(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Reproduciendo", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { nav.showNowPlaying = false }) { Icon(Icons.Rounded.Close, "Cerrar") }
            }
            if (track != null && nav.rightTab == 0) {
                NetworkImage(track.thumbnail, Modifier.fillMaxWidth().aspectRatio(1f), RoundedCornerShape(16.dp), size = 720)
                Spacer(Modifier.height(12.dp))
                Text(track.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(track.artistsText, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Spacer(Modifier.height(8.dp))
            }
            PrimaryTabRow(selectedTabIndex = nav.rightTab, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                Tab(nav.rightTab == 0, onClick = { nav.rightTab = 0 }, text = { Text("A continuación") })
                Tab(nav.rightTab == 1, onClick = { nav.rightTab = 1 }, text = { Text("Letras") })
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (nav.rightTab == 0) QueueList() else LyricsView()
            }
        }
    }
}

@Composable
private fun QueueList() {
    val services = LocalServices.current
    val state by services.player.state.collectAsState()
    val listState = rememberLazyListState()
    LaunchedEffect(state.index) { if (state.index > 0) listState.animateScrollToItem((state.index - 1).coerceAtLeast(0)) }
    if (state.queue.isEmpty()) {
        Text("La cola está vacía", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(state.queueTitle ?: "Cola", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(top = 8.dp), maxLines = 1)
            TextButton(onClick = { services.player.clearQueue() }) { Text("Vaciar") }
        }
        LazyColumn(state = listState, contentPadding = PaddingValues(vertical = 4.dp)) {
            itemsIndexed(state.queue) { i, t ->
                val current = i == state.index
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(if (current) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceContainerLow)
                        .clickable { services.player.jumpTo(i) }
                        .padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NetworkImage(t.thumbnail, Modifier.size(40.dp), RoundedCornerShape(6.dp), size = 120)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                            color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        Text(t.artistsText, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(formatDuration(t.durationSec), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!current) {
                        IconButton(onClick = { services.player.moveInQueue(i, i - 1) }, Modifier.size(28.dp), enabled = i > 0) { Icon(Icons.Rounded.KeyboardArrowUp, "Subir", Modifier.size(18.dp)) }
                        IconButton(onClick = { services.player.moveInQueue(i, i + 1) }, Modifier.size(28.dp), enabled = i < state.queue.lastIndex) { Icon(Icons.Rounded.KeyboardArrowDown, "Bajar", Modifier.size(18.dp)) }
                        IconButton(onClick = { services.player.removeFromQueue(i) }, Modifier.size(28.dp)) { Icon(Icons.Rounded.Close, "Quitar", Modifier.size(18.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricsView() {
    val services = LocalServices.current
    val state by services.player.state.collectAsState()
    val position by services.player.position.collectAsState()
    val track = state.current
    var lyrics by remember(track?.id) { mutableStateOf<Lyrics?>(null) }
    var loading by remember(track?.id) { mutableStateOf(true) }

    LaunchedEffect(track?.id) {
        loading = true
        lyrics = track?.let { services.lyrics.get(it) }
        loading = false
    }

    when {
        track == null -> Text("Nada en reproducción", Modifier.padding(16.dp))
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        lyrics == null -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(40.dp))
            Icon(Icons.Rounded.Mic, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("No hay letras disponibles", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> {
            val l = lyrics!!
            val active = l.activeIndex(position)
            val listState = rememberLazyListState()
            LaunchedEffect(active) { if (active >= 0) listState.animateScrollToItem((active - 2).coerceAtLeast(0)) }
            LazyColumn(state = listState, contentPadding = PaddingValues(vertical = 16.dp)) {
                itemsIndexed(l.lines) { i, line ->
                    val color by animateColorAsState(
                        when {
                            !l.synced -> MaterialTheme.colorScheme.onSurface
                            i == active -> MaterialTheme.colorScheme.primary
                            i < active -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Text(
                        line.text.ifBlank { "♪" },
                        style = if (l.synced) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
                        fontWeight = if (i == active) FontWeight.Bold else FontWeight.Medium,
                        color = color,
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = l.synced) { services.player.seek(line.timeMs) }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                    )
                }
                item { Text("Fuente: ${l.provider}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp)) }
            }
        }
    }
}
