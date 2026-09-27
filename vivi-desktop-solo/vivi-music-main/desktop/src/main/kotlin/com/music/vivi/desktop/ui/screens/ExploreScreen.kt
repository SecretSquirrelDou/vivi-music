package com.music.vivi.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.music.innertube.YouTube
import com.music.vivi.desktop.LocalNavigator
import com.music.vivi.desktop.Screen
import com.music.vivi.desktop.ui.components.ItemRow
import com.music.vivi.desktop.ui.components.LoadContent
import com.music.vivi.desktop.ui.components.SectionHeader
import com.music.vivi.desktop.ui.components.rememberLoader

@Composable
fun ExploreScreen() {
    val nav = LocalNavigator.current
    val loader = rememberLoader("explore") { YouTube.explore() }
    LoadContent(loader) { page ->
        LazyVerticalGrid(
            GridCells.Adaptive(200.dp), Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Explorar") }
            if (page.newReleaseAlbums.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader("Nuevos lanzamientos") }
                item(span = { GridItemSpan(maxLineSpan) }) { ItemRow(page.newReleaseAlbums) }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader("Estados de ánimo y géneros") }
            items(page.moodAndGenres) { mood ->
                val stripe = Color(mood.stripeColor.toInt()).copy(alpha = 1f)
                Box(
                    Modifier.padding(6.dp).fillMaxWidth().height(64.dp).clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable { nav.go(Screen.Browse(mood.title, mood.endpoint)) },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Box(Modifier.width(8.dp).fillMaxSize().background(stripe))
                    Text(mood.title, Modifier.padding(start = 20.dp), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
