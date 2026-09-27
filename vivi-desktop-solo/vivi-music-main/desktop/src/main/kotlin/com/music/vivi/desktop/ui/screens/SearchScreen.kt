package com.music.vivi.desktop.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import com.music.innertube.models.SongItem
import com.music.innertube.models.YTItem
import com.music.innertube.pages.SearchSummaryPage
import com.music.vivi.desktop.LocalNavigator
import com.music.vivi.desktop.LocalServices
import com.music.vivi.desktop.data.toTrack
import com.music.vivi.desktop.ui.components.ErrorBox
import com.music.vivi.desktop.ui.components.ItemCard
import com.music.vivi.desktop.ui.components.ItemRow
import com.music.vivi.desktop.ui.components.SectionHeader
import com.music.vivi.desktop.ui.components.SongRow
import com.music.vivi.desktop.ui.components.openItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class SearchTab(val label: String, val filter: YouTube.SearchFilter?) {
    ALL("Todo", null),
    SONGS("Canciones", YouTube.SearchFilter.FILTER_SONG),
    VIDEOS("Videos", YouTube.SearchFilter.FILTER_VIDEO),
    ALBUMS("Álbumes", YouTube.SearchFilter.FILTER_ALBUM),
    ARTISTS("Artistas", YouTube.SearchFilter.FILTER_ARTIST),
    PLAYLISTS("Listas", YouTube.SearchFilter.FILTER_FEATURED_PLAYLIST),
    COMMUNITY("Listas de la comunidad", YouTube.SearchFilter.FILTER_COMMUNITY_PLAYLIST),
}

@Composable
fun SearchScreen(query: String) {
    var tab by remember(query) { mutableStateOf(SearchTab.ALL) }
    Column0 {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchTab.entries.forEach { t ->
                FilterChip(selected = tab == t, onClick = { tab = t }, label = { Text(t.label) })
            }
        }
        if (tab == SearchTab.ALL) SearchSummary(query) else FilteredResults(query, tab)
    }
}

@Composable
private fun Column0(content: @Composable () -> Unit) = androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) { content() }

@Composable
private fun SearchSummary(query: String) {
    val services = LocalServices.current
    val player by services.player.state.collectAsState()
    var result by remember(query) { mutableStateOf<Result<SearchSummaryPage>?>(null) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(query, attempt) {
        result = null
        result = withContext(Dispatchers.IO) { YouTube.searchSummary(query) }
    }
    val r = result
    when {
        r == null -> Loading()
        r.isFailure -> ErrorBox(r.exceptionOrNull()?.message ?: "Error") { attempt++ }
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 28.dp, vertical = 4.dp)) {
            val page = r.getOrThrow()
            page.summaries.forEach { summary ->
                item { SectionHeader(summary.title) }
                val songs = summary.items.filterIsInstance<SongItem>()
                val others = summary.items.filterNot { it is SongItem }
                items(songs) { song ->
                    val t = song.toTrack()
                    SongRow(t, isPlaying = player.current?.id == t.id) { services.player.playWithRadio(t) }
                }
                if (others.isNotEmpty()) item { ItemRow(others) }
            }
        }
    }
}

@Composable
private fun FilteredResults(query: String, tab: SearchTab) {
    val services = LocalServices.current
    val nav = LocalNavigator.current
    val player by services.player.state.collectAsState()
    val items = remember(query, tab) { mutableStateListOf<YTItem>() }
    var continuation by remember(query, tab) { mutableStateOf<String?>(null) }
    var loading by remember(query, tab) { mutableStateOf(true) }
    var error by remember(query, tab) { mutableStateOf<String?>(null) }

    LaunchedEffect(query, tab) {
        loading = true
        withContext(Dispatchers.IO) { YouTube.search(query, tab.filter!!) }
            .onSuccess { items.addAll(it.items); continuation = it.continuation }
            .onFailure { error = it.message }
        loading = false
    }

    suspend fun more() {
        val c = continuation ?: return
        if (loading) return
        loading = true
        withContext(Dispatchers.IO) { YouTube.searchContinuation(c) }
            .onSuccess { items.addAll(it.items); continuation = it.continuation }
            .onFailure { continuation = null }
        loading = false
    }

    if (error != null && items.isEmpty()) {
        ErrorBox(error!!)
        return
    }
    val isList = tab == SearchTab.SONGS || tab == SearchTab.VIDEOS
    if (isList) {
        val state = rememberLazyListState()
        val nearEnd by remember { derivedStateOf { (state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= state.layoutInfo.totalItemsCount - 5 } }
        LaunchedEffect(nearEnd, continuation) { if (nearEnd) more() }
        LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 28.dp, vertical = 4.dp)) {
            items(items.filterIsInstance<SongItem>()) { song ->
                val t = song.toTrack()
                SongRow(t, isPlaying = player.current?.id == t.id) { services.player.playWithRadio(t) }
            }
            if (loading) item { Loading() }
        }
    } else {
        val state = rememberLazyGridState()
        val nearEnd by remember { derivedStateOf { (state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= state.layoutInfo.totalItemsCount - 6 } }
        LaunchedEffect(nearEnd, continuation) { if (nearEnd) more() }
        LazyVerticalGrid(
            GridCells.Adaptive(180.dp), state = state, modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 4.dp),
        ) {
            items(items.toList()) { item -> ItemCard(item, 180) { openItem(item, nav, services) } }
        }
    }
}

@Composable
fun Loading() {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}
