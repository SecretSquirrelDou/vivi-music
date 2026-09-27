package com.music.vivi.desktop.lyrics

import com.music.innertube.YouTube
import com.music.innertube.models.WatchEndpoint
import com.music.lrclib.LrcLib
import com.music.vivi.desktop.data.MusicDatabase
import com.music.vivi.desktop.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LyricLine(val timeMs: Long, val text: String)

data class Lyrics(val synced: Boolean, val lines: List<LyricLine>, val provider: String) {
    /** Índice de la línea activa para la posición dada. */
    fun activeIndex(positionMs: Long): Int {
        if (!synced) return -1
        var lo = 0
        var hi = lines.lastIndex
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (lines[mid].timeMs <= positionMs) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }
}

/**
 * Letras: usa la caché de la tabla `lyrics` (compartida con Android por la sincronización),
 * luego LrcLib (letras sincronizadas, mismo proveedor que Android) y por último YouTube Music.
 */
class LyricsRepository(private val db: MusicDatabase) {
    private val timeTag = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")

    suspend fun get(track: Track): Lyrics? = withContext(Dispatchers.IO) {
        db.lyrics(track.id)?.takeIf { it.isNotBlank() && it != LYRICS_NOT_FOUND }?.let { return@withContext parse(it, "Caché") }

        val artist = track.artists.firstOrNull()?.name.orEmpty()
        LrcLib.getLyrics(track.title, artist, track.durationSec, track.albumName).getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { text ->
                runCatching { db.saveLyrics(track.id, text, "LrcLib") }
                return@withContext parse(text, "LrcLib")
            }

        val yt = runCatching {
            val endpoint = YouTube.next(WatchEndpoint(videoId = track.id)).getOrThrow().lyricsEndpoint ?: return@runCatching null
            YouTube.lyrics(endpoint).getOrNull()
        }.getOrNull()
        if (!yt.isNullOrBlank()) {
            runCatching { db.saveLyrics(track.id, yt, "YouTubeMusic") }
            return@withContext parse(yt, "YouTube Music")
        }
        null
    }

    fun parse(raw: String, provider: String): Lyrics {
        val synced = ArrayList<LyricLine>()
        raw.lineSequence().forEach { line ->
            val tags = timeTag.findAll(line).toList()
            if (tags.isEmpty()) return@forEach
            val text = line.substring(tags.last().range.last + 1).trim()
                .replace(Regex("""<\d{1,2}:\d{2}(?:[.:]\d{1,3})?>"""), "") // etiquetas palabra a palabra
            tags.forEach { m ->
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val frac = m.groupValues[3]
                val ms = when (frac.length) {
                    0 -> 0L
                    1 -> frac.toLong() * 100
                    2 -> frac.toLong() * 10
                    else -> frac.take(3).toLong()
                }
                synced += LyricLine(min * 60_000 + sec * 1000 + ms, text)
            }
        }
        return if (synced.size >= 3) {
            Lyrics(true, synced.sortedBy { it.timeMs }, provider)
        } else {
            Lyrics(false, raw.lines().map { LyricLine(0, it) }, provider)
        }
    }

    companion object {
        const val LYRICS_NOT_FOUND = "LYRICS_NOT_FOUND"
    }
}
