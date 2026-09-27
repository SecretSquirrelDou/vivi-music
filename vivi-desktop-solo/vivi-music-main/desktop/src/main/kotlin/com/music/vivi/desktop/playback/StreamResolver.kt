package com.music.vivi.desktop.playback

import com.music.innertube.NewPipeExtractor
import com.music.innertube.YouTube
import com.music.innertube.models.YouTubeClient
import com.music.innertube.models.response.PlayerResponse
import com.music.vivi.desktop.data.AudioQualitySetting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Obtiene la URL de audio de YouTube Music para un videoId.
 *
 * Es el equivalente de escritorio de `YTPlayerUtils` en Android y usa el MISMO módulo
 * `innertube` y la MISMA estrategia de clientes:
 *  1. Clientes que no necesitan PoToken (ANDROID_VR, IOS…) → `YouTube.player(...)`.
 *  2. Si la URL viene cifrada o con parámetro `n`, se descifra con NewPipeExtractor
 *     (igual que hace la app Android).
 *  3. Último recurso: extracción completa con NewPipe (`YouTube.getNewPipeStreamUrls`).
 *
 *  Cada candidato se valida con una petición HEAD antes de usarlo.
 */
object StreamResolver {
    data class ResolvedStream(
        val videoId: String,
        val url: String,
        val mimeType: String,
        val itag: Int,
        val bitrate: Int,
        val contentLength: Long?,
        val userAgent: String,
        val client: String,
        val loudnessDb: Double?,
        val expiresAtMillis: Long,
    ) {
        val baseMime: String get() = mimeType.substringBefore(';').trim().ifEmpty { "audio/mp4" }
        val isExpired: Boolean get() = System.currentTimeMillis() > expiresAtMillis - 60_000
    }

    @Volatile
    var quality: AudioQualitySetting = AudioQualitySetting.HIGH

    private val cache = ConcurrentHashMap<String, ResolvedStream>()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** Orden de clientes: igual filosofía que MAIN_CLIENT + STREAM_FALLBACK_CLIENTS en Android. */
    private val clients: List<YouTubeClient> = listOf(
        YouTubeClient.ANDROID_VR_1_43_32,
        YouTubeClient.ANDROID_VR_NO_AUTH,
        YouTubeClient.IOS,
        YouTubeClient.TVHTML5,
        YouTubeClient.WEB_REMIX,
    )

    fun invalidate(videoId: String) {
        cache.remove(videoId)
    }

    suspend fun resolve(videoId: String, forceRefresh: Boolean = false): Result<ResolvedStream> = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            cache[videoId]?.takeIf { !it.isExpired }?.let { return@withContext Result.success(it) }
        }
        val errors = ArrayList<String>()
        var loudness: Double? = null
        val sts: Int? by lazy { NewPipeExtractor.getSignatureTimestamp(videoId).getOrNull() }

        for (client in clients) {
            val result = runCatching {
                YouTube.player(
                    videoId = videoId,
                    client = client,
                    signatureTimestamp = if (client.useSignatureTimestamp) sts else null,
                ).getOrThrow()
            }
            val response = result.getOrNull()
            if (response == null) {
                errors += "${client.clientName}: ${result.exceptionOrNull()?.message}"
                continue
            }
            if (response.playabilityStatus.status != "OK") {
                errors += "${client.clientName}: ${response.playabilityStatus.status} ${response.playabilityStatus.reason.orEmpty()}"
                continue
            }
            loudness = loudness ?: response.playerConfig?.audioConfig?.loudnessDb
            val format = pickFormat(response) ?: run {
                errors += "${client.clientName}: sin formatos de audio"
                null
            } ?: continue

            val url = runCatching { NewPipeExtractor.getStreamUrl(format, videoId) }.getOrNull()
                ?: format.url
            if (url == null) {
                errors += "${client.clientName}: no se pudo descifrar la URL"
                continue
            }
            if (!validate(url, client.userAgent)) {
                errors += "${client.clientName}: URL rechazada (403)"
                continue
            }
            val stream = ResolvedStream(
                videoId = videoId,
                url = url,
                mimeType = format.mimeType,
                itag = format.itag,
                bitrate = format.bitrate,
                contentLength = format.contentLength,
                userAgent = client.userAgent,
                client = client.clientName,
                loudnessDb = loudness ?: format.loudnessDb,
                expiresAtMillis = System.currentTimeMillis() + (response.streamingData?.expiresInSeconds ?: 18_000) * 1000L,
            )
            cache[videoId] = stream
            return@withContext Result.success(stream)
        }

        // Último recurso: NewPipe completo
        val newPipe = runCatching { YouTube.getNewPipeStreamUrls(videoId) }.getOrDefault(emptyList())
        val preferred = if (quality == AudioQualitySetting.LOW) listOf(249, 250, 139, 251, 140) else listOf(251, 140, 250, 249, 139)
        for (itag in preferred) {
            val url = newPipe.firstOrNull { it.first == itag }?.second ?: continue
            if (!validate(url, YouTubeClient.USER_AGENT_WEB)) continue
            val mime = if (itag == 140 || itag == 139) "audio/mp4" else "audio/webm"
            val stream = ResolvedStream(videoId, url, mime, itag, 0, null, YouTubeClient.USER_AGENT_WEB, "NewPipe", loudness, System.currentTimeMillis() + 3 * 3600_000L)
            cache[videoId] = stream
            return@withContext Result.success(stream)
        }
        errors += "NewPipe: sin streams válidos"
        Result.failure(IllegalStateException("No se pudo obtener el audio.\n" + errors.joinToString("\n")))
    }

    private fun pickFormat(response: PlayerResponse): PlayerResponse.StreamingData.Format? {
        val audio = response.streamingData?.adaptiveFormats.orEmpty()
            .filter { it.isAudio && it.mimeType.startsWith("audio") }
        if (audio.isEmpty()) return null
        val original = audio.filter { it.isOriginal }.ifEmpty { audio }
        return when (quality) {
            AudioQualitySetting.LOW -> original.minByOrNull { it.bitrate }
            else -> original.maxByOrNull { it.bitrate + (if (it.mimeType.contains("opus")) 10_000 else 0) }
        }
    }

    private fun validate(url: String, userAgent: String): Boolean = runCatching {
        val req = Request.Builder().url(url).header("User-Agent", userAgent).header("Range", "bytes=0-1").get().build()
        http.newCall(req).execute().use { it.code == 200 || it.code == 206 }
    }.getOrDefault(false)
}
