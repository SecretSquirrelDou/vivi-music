package com.music.vivi.desktop.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.music.vivi.desktop.data.AppDirs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.TimeUnit

/** Cargador de imágenes con caché en memoria (LRU) y en disco. Reemplaza a Coil de Android. */
object ImageLoader {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val memory: MutableMap<String, ImageBitmap> = Collections.synchronizedMap(
        object : LinkedHashMap<String, ImageBitmap>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 400
        },
    )
    private val diskDir: File by lazy { File(AppDirs.cache, "images").apply { mkdirs() } }

    fun cached(url: String): ImageBitmap? = memory[url]

    suspend fun load(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
        memory[url]?.let { return@withContext it }
        runCatching {
            val file = File(diskDir, sha1(url))
            val bytes = if (file.exists()) file.readBytes() else {
                http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) return@runCatching null
                    r.body.bytes()
                }.also { runCatching { file.writeBytes(it) } }
            }
            org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap().also { memory[url] = it }
        }.getOrNull()
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** Ajusta el tamaño de las miniaturas de Google (lh3/yt3: "=w120-h120…") para no descargar de más. */
fun thumbnailUrl(url: String?, size: Int): String? {
    if (url == null) return null
    return when {
        Regex("""=w\d+-h\d+""").containsMatchIn(url) -> url.replace(Regex("""=w\d+-h\d+"""), "=w$size-h$size")
        Regex("""=s\d+""").containsMatchIn(url) -> url.replace(Regex("""=s\d+"""), "=s$size")
        else -> url
    }
}

@Composable
fun rememberImage(url: String?): ImageBitmap? {
    var bmp by remember(url) { mutableStateOf(url?.let { ImageLoader.cached(it) }) }
    LaunchedEffect(url) {
        if (url != null && bmp == null) bmp = ImageLoader.load(url)
    }
    return bmp
}

@Composable
fun NetworkImage(
    url: String?,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.small,
    size: Int = 226,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val bmp = rememberImage(thumbnailUrl(url, size))
    Box(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (bmp != null) {
            Image(bmp, contentDescription = null, contentScale = contentScale, modifier = Modifier.matchParentSize())
        } else {
            Icon(Icons.Rounded.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
        }
    }
}
