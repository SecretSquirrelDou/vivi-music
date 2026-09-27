package com.music.vivi.desktop.playback

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Servidor HTTP local (127.0.0.1) que hace de puente entre VLC y googlevideo.
 *
 * Por qué existe (equivalente al DataSource de ExoPlayer en Android):
 *  • Descarga en bloques con rango acotado → evita el estrangulamiento/403 de YouTube.
 *  • Envía el User-Agent correcto del cliente que generó la URL.
 *  • Si la URL caduca a mitad de canción, la vuelve a resolver y continúa sin cortes.
 *  • VLC puede hacer seek con normalidad (soporta Range).
 */
class StreamProxy {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16).apply {
        createContext("/stream/") { ex -> runCatching { handle(ex) }.onFailure { runCatching { ex.close() } } }
        executor = Executors.newCachedThreadPool { r -> Thread(r, "vivi-stream-proxy").apply { isDaemon = true } }
        start()
    }

    val port: Int get() = server.address.port

    fun urlFor(videoId: String): String = "http://127.0.0.1:$port/stream/$videoId"

    fun stop() = server.stop(0)

    private fun handle(ex: HttpExchange) {
        val videoId = ex.requestURI.path.removePrefix("/stream/").substringBefore('/').substringBefore('?')
        var stream = runBlocking { StreamResolver.resolve(videoId) }.getOrElse {
            ex.sendResponseHeaders(502, -1)
            ex.close()
            return
        }
        val total = stream.contentLength ?: probeLength(stream.url, stream.userAgent)
        if (total == null || total <= 0) {
            ex.sendResponseHeaders(502, -1)
            ex.close()
            return
        }

        val rangeHeader = ex.requestHeaders.getFirst("Range")
        var start = 0L
        var end = total - 1
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val spec = rangeHeader.removePrefix("bytes=").substringBefore(',')
            val a = spec.substringBefore('-').trim()
            val b = spec.substringAfter('-', "").trim()
            if (a.isEmpty() && b.isNotEmpty()) {
                start = (total - b.toLong()).coerceAtLeast(0)
            } else {
                start = a.toLongOrNull() ?: 0L
                if (b.isNotEmpty()) end = minOf(b.toLong(), total - 1)
            }
        }
        if (start >= total) {
            ex.responseHeaders.add("Content-Range", "bytes */$total")
            ex.sendResponseHeaders(416, -1)
            ex.close()
            return
        }

        val length = end - start + 1
        ex.responseHeaders.add("Content-Type", stream.baseMime)
        ex.responseHeaders.add("Accept-Ranges", "bytes")
        if (rangeHeader != null) ex.responseHeaders.add("Content-Range", "bytes $start-$end/$total")
        if (ex.requestMethod.equals("HEAD", ignoreCase = true)) {
            ex.responseHeaders.add("Content-Length", length.toString())
            ex.sendResponseHeaders(if (rangeHeader != null) 206 else 200, -1)
            ex.close()
            return
        }
        ex.sendResponseHeaders(if (rangeHeader != null) 206 else 200, length)

        val out = ex.responseBody
        val buffer = ByteArray(64 * 1024)
        var pos = start
        var refreshes = 0
        try {
            while (pos <= end) {
                val chunkEnd = minOf(pos + CHUNK_SIZE - 1, end)
                val req = Request.Builder()
                    .url(stream.url)
                    .header("User-Agent", stream.userAgent)
                    .header("Range", "bytes=$pos-$chunkEnd")
                    .build()
                val resp = http.newCall(req).execute()
                if (resp.code == 403 || resp.code == 410 || resp.code == 404) {
                    resp.close()
                    if (refreshes++ >= 2) break
                    stream = runBlocking { StreamResolver.resolve(videoId, forceRefresh = true) }.getOrNull() ?: break
                    continue
                }
                if (!resp.isSuccessful) {
                    resp.close()
                    break
                }
                resp.use { r ->
                    val input = r.body.byteStream()
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        pos += n
                        if (pos > chunkEnd) break
                    }
                }
            }
        } catch (e: IOException) {
            // VLC cerró la conexión (seek, cambio de canción…). Es normal.
        } finally {
            runCatching { out.close() }
            ex.close()
        }
    }

    private fun probeLength(url: String, userAgent: String): Long? = runCatching {
        val req = Request.Builder().url(url).header("User-Agent", userAgent).header("Range", "bytes=0-0").build()
        http.newCall(req).execute().use { r ->
            r.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                ?: r.header("Content-Length")?.toLongOrNull()?.takeIf { r.code == 200 }
        }
    }.getOrNull()

    companion object {
        private const val CHUNK_SIZE = 1L * 1024 * 1024
    }
}
