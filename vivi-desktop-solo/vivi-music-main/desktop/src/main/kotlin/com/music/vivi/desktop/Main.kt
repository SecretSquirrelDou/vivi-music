package com.music.vivi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.music.innertube.YouTube
import com.music.vivi.desktop.data.ThemeMode
import com.music.vivi.desktop.sync.SyncManager
import com.music.vivi.desktop.ui.components.NetworkImage
import com.music.vivi.desktop.ui.components.rememberImage
import com.music.vivi.desktop.ui.components.thumbnailUrl
import com.music.vivi.desktop.ui.player.NowPlayingPanel
import com.music.vivi.desktop.ui.player.PlayerBar
import com.music.vivi.desktop.ui.screens.AlbumScreen
import com.music.vivi.desktop.ui.screens.ArtistItemsScreen
import com.music.vivi.desktop.ui.screens.ArtistScreen
import com.music.vivi.desktop.ui.screens.BrowseScreen
import com.music.vivi.desktop.ui.screens.ExploreScreen
import com.music.vivi.desktop.ui.screens.HistoryScreen
import com.music.vivi.desktop.ui.screens.HomeScreen
import com.music.vivi.desktop.ui.screens.LibraryScreen
import com.music.vivi.desktop.ui.screens.LikedScreen
import com.music.vivi.desktop.ui.screens.LocalPlaylistScreen
import com.music.vivi.desktop.ui.screens.OnlinePlaylistScreen
import com.music.vivi.desktop.ui.screens.SearchScreen
import com.music.vivi.desktop.ui.screens.SettingsScreen
import com.music.vivi.desktop.ui.theme.ViviPurple
import com.music.vivi.desktop.ui.theme.ViviTheme
import com.music.vivi.desktop.ui.theme.dominantColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val LocalServices = staticCompositionLocalOf<Services> { error("Services no inicializado") }
val LocalNavigator = staticCompositionLocalOf<Navigator> { error("Navigator no inicializado") }

fun main() {
    val services = Services()
    val icon = Thread.currentThread().contextClassLoader.getResourceAsStream("icon.png")?.use {
        BitmapPainter(org.jetbrains.skia.Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap())
    }

    application {
        val windowState = rememberWindowState(size = DpSize(1360.dp, 860.dp))
        val playerState by services.player.state.collectAsState()
        val exit = {
            services.shutdown()
            exitApplication()
        }

        if (icon != null) {
            Tray(
                icon = icon,
                tooltip = playerState.current?.let { "${it.title} — ${it.artistsText}" } ?: "VIVI Music",
                menu = {
                    Item(if (playerState.isPlaying) "Pausa" else "Reproducir", onClick = { services.player.togglePlay() })
                    Item("Siguiente", onClick = { services.player.next() })
                    Item("Anterior", onClick = { services.player.previous() })
                    Separator()
                    Item("Salir", onClick = exit)
                },
            )
        }

        Window(
            onCloseRequest = exit,
            state = windowState,
            title = playerState.current?.let { "${it.title} · VIVI Music" } ?: "VIVI Music",
            icon = icon,
            onKeyEvent = { e ->
                e.type == KeyEventType.KeyDown && when {
                    e.key == Key.MediaPlayPause -> { services.player.togglePlay(); true }
                    e.key == Key.MediaNext -> { services.player.next(); true }
                    e.key == Key.MediaPrevious -> { services.player.previous(); true }
                    e.isCtrlPressed && e.key == Key.DirectionRight -> { services.player.next(); true }
                    e.isCtrlPressed && e.key == Key.DirectionLeft -> { services.player.previous(); true }
                    e.isCtrlPressed && e.key == Key.Spacebar -> { services.player.togglePlay(); true }
                    else -> false
                }
            },
        ) {
            window.minimumSize = java.awt.Dimension(760, 560)
            App(services)
        }
    }
}

@Composable
fun App(services: Services) {
    val nav = remember { Navigator() }
    val settings by services.settings.state.collectAsState()
    val player by services.player.state.collectAsState()

    // Material You: color de la carátula actual
    val art = rememberImage(thumbnailUrl(player.current?.thumbnail, 120))
    var seed by remember { mutableStateOf(ViviPurple) }
    LaunchedEffect(art, settings.dynamicColor) {
        seed = if (settings.dynamicColor && art != null) withContext(Dispatchers.Default) { art.dominantColor() } else ViviPurple
    }
    val dark = when (settings.themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    CompositionLocalProvider(LocalServices provides services, LocalNavigator provides nav) {
        ViviTheme(dark, seed) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val wide = maxWidth > 1180.dp
                    val compactSidebar = maxWidth < 900.dp
                    Column(Modifier.fillMaxSize()) {
                        Row(Modifier.weight(1f).fillMaxWidth()) {
                            Sidebar(compactSidebar)
                            Column(Modifier.weight(1f).fillMaxHeight()) {
                                TopBar()
                                Box(Modifier.weight(1f).fillMaxWidth()) { ScreenContent(nav.current) }
                            }
                            if (nav.showNowPlaying && player.current != null && wide) {
                                NowPlayingPanel(Modifier.width(360.dp))
                            }
                        }
                        PlayerBar(compact = maxWidth < 1000.dp)
                    }
                    if (nav.showNowPlaying && player.current != null && !wide) {
                        // En ventanas estrechas el panel se superpone a la derecha
                        Box(Modifier.align(Alignment.TopEnd).padding(bottom = 92.dp).width(340.dp).fillMaxHeight()) {
                            NowPlayingPanel(Modifier.fillMaxSize())
                        }
                    }
                    player.error?.let { err ->
                        LaunchedEffect(err) { delay(7000); services.player.clearError() }
                        ErrorSnack(err, Modifier.align(Alignment.BottomCenter).padding(bottom = 104.dp))
                    }
                    nav.snackbar?.let { msg ->
                        LaunchedEffect(msg) { delay(2500); nav.snackbar = null }
                        Snackbar(Modifier.align(Alignment.BottomCenter).padding(bottom = 104.dp).widthIn(max = 520.dp)) { Text(msg) }
                    }
                }
                nav.addToPlaylist?.let { tracks -> AddToPlaylistDialog(tracks) { nav.addToPlaylist = null } }
            }
        }
    }
}

@Composable
private fun ErrorSnack(message: String, modifier: Modifier) {
    Snackbar(modifier.widthIn(max = 640.dp), containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
        Text(message, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ScreenContent(screen: Screen) {
    when (screen) {
        Screen.Home -> HomeScreen()
        Screen.Explore -> ExploreScreen()
        is Screen.Search -> SearchScreen(screen.query)
        Screen.Library -> LibraryScreen()
        Screen.Liked -> LikedScreen()
        Screen.History -> HistoryScreen()
        Screen.Settings -> SettingsScreen()
        is Screen.LocalPlaylist -> LocalPlaylistScreen(screen.id)
        is Screen.OnlinePlaylist -> OnlinePlaylistScreen(screen.id)
        is Screen.Album -> AlbumScreen(screen.browseId)
        is Screen.Artist -> ArtistScreen(screen.browseId)
        is Screen.ArtistItems -> ArtistItemsScreen(screen.title, screen.endpoint)
        is Screen.Browse -> BrowseScreen(screen.title, screen.endpoint)
    }
}

// ───────────────────────── barra lateral ─────────────────────────

@Composable
private fun Sidebar(compact: Boolean) {
    val services = LocalServices.current
    val nav = LocalNavigator.current
    val v by services.db.changes.collectAsState()
    val playlists = remember(v) { services.db.playlists() }
    val syncStatus by services.sync.status.collectAsState()
    val width = if (compact) 76.dp else 248.dp

    Surface(Modifier.width(width).fillMaxHeight(), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(Modifier.fillMaxSize().padding(horizontal = if (compact) 8.dp else 12.dp, vertical = 16.dp)) {
            Row(Modifier.padding(start = 8.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) { Text("V", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold) }
                if (!compact) {
                    Spacer(Modifier.width(10.dp))
                    Text("VIVI Music", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            }
            NavItem("Inicio", Icons.Rounded.Home, nav.current == Screen.Home, compact) { nav.root(Screen.Home) }
            NavItem("Explorar", Icons.Rounded.Explore, nav.current == Screen.Explore, compact) { nav.root(Screen.Explore) }
            NavItem("Biblioteca", Icons.Rounded.LibraryMusic, nav.current == Screen.Library, compact) { nav.root(Screen.Library) }
            NavItem("Me gusta", Icons.Rounded.Favorite, nav.current == Screen.Liked, compact) { nav.root(Screen.Liked) }
            NavItem("Historial", Icons.Rounded.History, nav.current == Screen.History, compact) { nav.root(Screen.History) }
            NavItem("Ajustes", Icons.Rounded.Settings, nav.current == Screen.Settings, compact) { nav.root(Screen.Settings) }

            if (!compact) {
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
                    Text("Tus listas", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    TextButton(onClick = { nav.addToPlaylist = emptyList() }) { Text("+ Nueva") }
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(playlists) { p ->
                        val selected = (nav.current as? Screen.LocalPlaylist)?.id == p.id
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest)
                                .clickable { nav.go(Screen.LocalPlaylist(p.id)) }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            NetworkImage(p.thumbnails.firstOrNull(), Modifier.size(40.dp), RoundedCornerShape(8.dp), size = 120)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                Text("${p.songCount} canciones", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                // Estado de sincronización
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { nav.root(Screen.Settings) }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Sync, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when (val s = syncStatus) {
                            is SyncManager.Status.Running -> "Sincronizando…"
                            is SyncManager.Status.Done -> "Sincronizado"
                            is SyncManager.Status.Error -> "Error de sincronización"
                            SyncManager.Status.Idle -> if (services.sync.folder == null) "Sincronización desactivada" else "Sincronización lista"
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun NavItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, compact: Boolean, onClick: () -> Unit) {
    if (compact) {
        Box(
            Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(14.dp))
                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label) }
    } else {
        NavigationDrawerItem(
            label = { Text(label) },
            icon = { Icon(icon, null) },
            selected = selected,
            onClick = onClick,
            colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
            modifier = Modifier.height(48.dp),
        )
    }
}

// ───────────────────────── barra superior + búsqueda ─────────────────────────

@Composable
private fun TopBar() {
    val services = LocalServices.current
    val nav = LocalNavigator.current
    var query by remember { mutableStateOf((nav.current as? Screen.Search)?.query.orEmpty()) }
    var focused by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    val v by services.db.changes.collectAsState()
    val history = remember(v) { services.db.searchHistory(8) }

    LaunchedEffect(query) {
        if (query.isBlank()) { suggestions = emptyList(); return@LaunchedEffect }
        delay(200)
        suggestions = withContext(Dispatchers.IO) { YouTube.searchSuggestions(query) }.getOrNull()?.queries.orEmpty().take(8)
    }

    fun submit(q: String) {
        val text = q.trim()
        if (text.isEmpty()) return
        query = text
        services.db.addSearch(text)
        nav.go(Screen.Search(text))
        focused = false
    }

    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.back() }, enabled = nav.stack.size > 1) { Icon(Icons.Rounded.ArrowBack, "Atrás") }
            Spacer(Modifier.width(8.dp))
            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Buscar canciones, álbumes, artistas y podcasts") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Borrar") } },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0f),
                    unfocusedIndicatorColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0f),
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { submit(query) }),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()
                    .onFocusChanged { f ->
                        if (f.isFocused) {
                            focused = true
                        } else {
                            scope.launch { delay(250); focused = false }
                        }
                    }
                    .onKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) { submit(query); true } else false
                    },
            )
        }
        val shown = if (query.isBlank()) history else suggestions
        if (focused && shown.isNotEmpty()) {
            Surface(
                Modifier.padding(top = 60.dp, start = 56.dp).widthIn(max = 640.dp).fillMaxWidth().heightIn(max = 360.dp),
                shape = RoundedCornerShape(16.dp), tonalElevation = 6.dp, shadowElevation = 8.dp,
            ) {
                LazyColumn {
                    items(shown) { s ->
                        Row(
                            Modifier.fillMaxWidth().clickable { submit(s) }.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(if (query.isBlank()) Icons.Rounded.History else Icons.Rounded.Search, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(12.dp))
                            Text(s)
                        }
                    }
                }
            }
        }
    }
}

// ───────────────────────── diálogo "Añadir a lista" / "Nueva lista" ─────────────────────────

@Composable
private fun AddToPlaylistDialog(tracks: List<com.music.vivi.desktop.data.Track>, onDismiss: () -> Unit) {
    val services = LocalServices.current
    val nav = LocalNavigator.current
    val playlists = remember { services.db.playlists() }
    var newName by remember { mutableStateOf("") }
    val createOnly = tracks.isEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (createOnly) "Nueva lista" else "Añadir a una lista") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(newName, { newName = it }, singleLine = true, label = { Text("Nombre de la nueva lista") }, modifier = Modifier.fillMaxWidth())
                if (!createOnly && playlists.isNotEmpty()) {
                    Text("O elige una existente:", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                    LazyColumn(Modifier.heightIn(max = 300.dp)) {
                        items(playlists) { p ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
                                    services.addToPlaylist(p.id, tracks)
                                    nav.toast("Añadida a \"${p.name}\"")
                                    onDismiss()
                                }.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.QueueMusic, null)
                                Spacer(Modifier.width(10.dp))
                                Text(p.name, Modifier.weight(1f))
                                Text("${p.songCount}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = newName.isNotBlank(), onClick = {
                val id = services.db.createPlaylist(newName.trim())
                if (tracks.isNotEmpty()) services.addToPlaylist(id, tracks)
                nav.toast("Lista \"${newName.trim()}\" creada")
                onDismiss()
                nav.go(Screen.LocalPlaylist(id))
            }) { Text("Crear") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
