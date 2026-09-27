package com.music.vivi.desktop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.music.innertube.YouTube
import com.music.innertube.pages.HomePage
import com.music.vivi.desktop.LocalNavigator
import com.music.vivi.desktop.LocalServices
import com.music.vivi.desktop.Screen
import com.music.vivi.desktop.data.Track
import com.music.vivi.desktop.ui.components.ErrorBox
import com.music.vivi.desktop.ui.components.ItemRow
import com.music.vivi.desktop.ui.components.SectionHeader
import com.music.vivi.desktop.ui.components.SongRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen() {
    val services = LocalServices.current
    val nav = LocalNavigator.current
    val player by services.player.state.collectAsState()
    val dbVersion by services.db.changes.collectAsState()

    var chips by remember { mutableStateOf<List<HomePage.Chip>>(emptyList()) }
    var selectedChip by remember { mutableStateOf<HomePage.Chip?>(null) }
    val sections = remember { mutableStateListOf<HomePage.Section>() }
    var continuation by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }

    val recent: List<Track> = remember(dbVersion) {
        services.db.history(60).map { it.track }.distinctBy { it.id }.take(8)
    }

    LaunchedEffect(selectedChip, attempt) {
        loading = true
        error = null
        sections.clear()
        val result = withContext(Dispatchers.IO) { YouTube.home(params = selectedChip?.endpoint?.params) }
        result.onSuccess { page ->
            if (selectedChip == null) chips = page.chips.orEmpty()
            sections.addAll(page.sections)
            continuation = page.continuation
        }.onFailure { error = it.message ?: "Error de red" }
        loading = false
    }

    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 2
        }
    }
    LaunchedEffect(nearEnd, continuation) {
        val c = continuation ?: return@LaunchedEffect
        if (!nearEnd || loading) return@LaunchedEffect
        loading = true
        withContext(Dispatchers.IO) { YouTube.home(continuation = c) }.onSuccess { page ->
            sections.addAll(page.sections)
            continuation = page.continuation
        }.onFailure { continuation = null }
        loading = false
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp)) {
        if (chips.isNotEmpty()) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    items(chips) { chip ->
                        FilterChip(
                            selected = selectedChip == chip,
                            onClick = { selectedChip = if (selectedChip == chip) null else chip },
                            label = { Text(chip.title) },
                        )
                    }
                }
            }
        }
        if (recent.isNotEmpty() && selectedChip == null) {
            item { SectionHeader("Escuchado recientemente", "Tu historial") }
            item {
                androidx.compose.foundation.layout.Column {
                    recent.chunked(2).forEach { pair ->
                        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth()) {
                            pair.forEach { t ->
                                Box(Modifier.weight(1f)) {
                                    SongRow(t, isPlaying = player.current?.id == t.id, showAlbum = false) { services.player.playWithRadio(t) }
                                }
                            }
                            if (pair.size == 1) Box(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        items(sections) { section ->
            SectionHeader(section.title, section.label) {
                section.endpoint?.let { ep ->
                    androidx.compose.material3.TextButton(onClick = {
                        when {
                            ep.browseId.startsWith("UC") || ep.browseId.startsWith("MPLA") -> nav.go(Screen.Artist(ep.browseId))
                            ep.browseId.startsWith("VL") -> nav.go(Screen.OnlinePlaylist(ep.browseId.removePrefix("VL")))
                            else -> nav.go(Screen.Browse(section.title, ep))
                        }
                    }) { Text("Ver todo") }
                }
            }
            ItemRow(section.items)
        }
        if (error != null) item { ErrorBox(error!!) { attempt++ } }
        if (loading) item {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        item { Box(Modifier.width(1.dp).padding(bottom = 24.dp)) }
    }
}

@Composable
fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
