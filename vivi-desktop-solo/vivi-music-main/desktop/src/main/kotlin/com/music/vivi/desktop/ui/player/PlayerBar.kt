package com.music.vivi.desktop.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.vivi.desktop.LocalNavigator
import com.music.vivi.desktop.LocalServices
import com.music.vivi.desktop.Screen
import com.music.vivi.desktop.playback.RepeatMode
import com.music.vivi.desktop.ui.components.NetworkImage
import com.music.vivi.desktop.ui.components.formatMs

/** Barra inferior de reproducción (equivalente al MiniPlayer de Android, adaptado al escritorio). */
@Composable
fun PlayerBar(compact: Boolean) {
    val services = LocalServices.current
    val nav = LocalNavigator.current
    val state by services.player.state.collectAsState()
    val position by services.player.position.collectAsState()
    val v by services.db.changes.collectAsState()
    val track = state.current
    val liked = remember(track?.id, v) { track?.let { services.db.isLiked(it.id) } ?: false }

    var seeking by remember { mutableStateOf<Float?>(null) }
    var lastVolume by remember { mutableStateOf(80) }

    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().height(92.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Izquierda: carátula + título
            Row(Modifier.weight(if (compact) 1.2f else 1f), verticalAlignment = Alignment.CenterVertically) {
                if (track != null) {
                    NetworkImage(track.thumbnail, Modifier.size(60.dp).clickable { nav.showNowPlaying = !nav.showNowPlaying }, RoundedCornerShape(10.dp), size = 226)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f, fill = false).widthIn(max = 260.dp)) {
                        Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Text(
                            track.artistsText, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable(enabled = track.artists.firstOrNull()?.id != null) {
                                track.artists.firstOrNull()?.id?.let { nav.go(Screen.Artist(it)) }
                            },
                        )
                    }
                    IconButton(onClick = { services.toggleLike(track) }) {
                        Icon(
                            if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Me gusta",
                            tint = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Centro: controles + progreso
            Column(Modifier.weight(if (compact) 1.4f else 1.6f), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconButton(onClick = { services.player.toggleShuffle() }) {
                        Icon(Icons.Rounded.Shuffle, "Aleatorio", tint = if (state.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { services.player.previous() }) { Icon(Icons.Rounded.SkipPrevious, "Anterior", Modifier.size(30.dp)) }
                    FilledIconButton(
                        onClick = { services.player.togglePlay() }, modifier = Modifier.size(48.dp), shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
                    ) {
                        if (state.isLoading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        else Icon(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Reproducir/Pausa", Modifier.size(30.dp))
                    }
                    IconButton(onClick = { services.player.next() }) { Icon(Icons.Rounded.SkipNext, "Siguiente", Modifier.size(30.dp)) }
                    IconButton(onClick = { services.player.cycleRepeat() }) {
                        Icon(
                            if (state.repeat == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repetir",
                            tint = if (state.repeat != RepeatMode.OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    val dur = state.durationMs.coerceAtLeast(1)
                    val shown = seeking?.let { (it * dur).toLong() } ?: position
                    Text(formatMs(shown), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(44.dp))
                    Slider(
                        value = seeking ?: (position.toFloat() / dur).coerceIn(0f, 1f),
                        onValueChange = { seeking = it },
                        onValueChangeFinished = {
                            seeking?.let { services.player.seek((it * dur).toLong()) }
                            seeking = null
                        },
                        modifier = Modifier.weight(1f).height(20.dp),
                        colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary),
                    )
                    Text(formatMs(state.durationMs), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(44.dp).padding(start = 6.dp))
                }
            }

            // Derecha: letras, cola, volumen
            Row(Modifier.weight(if (compact) 0.8f else 1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    if (nav.showNowPlaying && nav.rightTab == 1) nav.showNowPlaying = false else { nav.showNowPlaying = true; nav.rightTab = 1 }
                }) { Icon(Icons.Rounded.Mic, "Letras", tint = if (nav.showNowPlaying && nav.rightTab == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                IconButton(onClick = {
                    if (nav.showNowPlaying && nav.rightTab == 0) nav.showNowPlaying = false else { nav.showNowPlaying = true; nav.rightTab = 0 }
                }) { Icon(Icons.Rounded.QueueMusic, "Cola", tint = if (nav.showNowPlaying && nav.rightTab == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                if (!compact) {
                    IconButton(onClick = {
                        if (state.volume > 0) { lastVolume = state.volume; services.player.setVolume(0) } else services.player.setVolume(lastVolume)
                    }) { Icon(if (state.volume == 0) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp, "Volumen") }
                    Box(Modifier.width(120.dp)) {
                        Slider(value = state.volume / 100f, onValueChange = { services.player.setVolume((it * 100).toInt()) }, modifier = Modifier.height(20.dp))
                    }
                }
            }
        }
    }
}
