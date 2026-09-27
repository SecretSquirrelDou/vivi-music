package com.music.vivi.desktop.playback

import com.sun.jna.NativeLibrary
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import java.io.File

/**
 * Motor de audio nativo basado en LibVLC (vlcj). Sustituye a ExoPlayer/Media3 de Android.
 *
 * Busca VLC en este orden:
 *  1. VLC empaquetado con el instalador (carpeta `vlc` junto a los recursos de la app).
 *  2. VLC instalado en el sistema (descubrimiento automático de vlcj).
 */
class AudioEngine(private val listener: Listener) {
    interface Listener {
        fun onPlaying()
        fun onPaused()
        fun onFinished()
        fun onError()
        fun onTime(ms: Long)
        fun onLength(ms: Long)
        fun onBuffering(percent: Float)
    }

    private val factory: MediaPlayerFactory
    private val player: MediaPlayer

    init {
        loadNatives()
        factory = MediaPlayerFactory(
            "--no-video",
            "--quiet",
            "--intf=dummy",
            "--network-caching=2000",
            "--http-reconnect",
        )
        player = factory.mediaPlayers().newMediaPlayer()
        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun playing(mediaPlayer: MediaPlayer) = listener.onPlaying()
            override fun paused(mediaPlayer: MediaPlayer) = listener.onPaused()
            override fun finished(mediaPlayer: MediaPlayer) = listener.onFinished()
            override fun error(mediaPlayer: MediaPlayer) = listener.onError()
            override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) = listener.onTime(newTime)
            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) = listener.onLength(newLength)
            override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) = listener.onBuffering(newCache)
        })
    }

    /** Todas las llamadas a VLC se hacen fuera del hilo de eventos nativo (recomendación de vlcj). */
    private fun vlc(block: MediaPlayer.() -> Unit) = player.submit { runCatching { player.block() } }

    fun play(mrl: String, startMs: Long = 0) = vlc {
        val opts = if (startMs > 0) arrayOf(":start-time=${startMs / 1000.0}") else emptyArray()
        media().play(mrl, *opts)
    }

    fun pause() = vlc { controls().setPause(true) }
    fun resume() = vlc { controls().setPause(false) }
    fun stop() = vlc { controls().stop() }
    fun seek(ms: Long) = vlc { controls().setTime(ms) }
    fun setVolume(percent: Int) = vlc { audio().setVolume(percent.coerceIn(0, 200)) }

    fun release() {
        runCatching { player.controls().stop() }
        runCatching { player.release() }
        runCatching { factory.release() }
    }

    companion object {
        @Volatile
        var vlcSource: String = "desconocido"
            private set

        private fun loadNatives() {
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            val candidates = listOfNotNull(
                System.getProperty("compose.application.resources.dir")?.let { File(it, "vlc") },
                File(System.getProperty("user.dir"), "resources/windows/vlc"),
                File(System.getProperty("user.dir"), "vlc"),
            )
            val bundled = candidates.firstOrNull { dir ->
                File(dir, if (isWindows) "libvlc.dll" else "libvlc.so").exists() || File(dir, "lib").isDirectory
            }
            if (bundled != null) {
                val libDir = if (File(bundled, "lib").isDirectory && !isWindows) File(bundled, "lib") else bundled
                val core = if (isWindows) "libvlccore" else "vlccore"
                val main = if (isWindows) "libvlc" else "vlc"
                NativeLibrary.addSearchPath(core, libDir.absolutePath)
                NativeLibrary.addSearchPath(main, libDir.absolutePath)
                // Precarga libvlccore para que libvlc encuentre su dependencia.
                runCatching { NativeLibrary.getInstance(core) }
                if (!isWindows) {
                    File(bundled, "plugins").takeIf { it.isDirectory }?.let { System.setProperty("VLC_PLUGIN_PATH", it.absolutePath) }
                }
                vlcSource = "VLC incluido (${bundled.absolutePath})"
                return
            }
            val found = NativeDiscovery().discover()
            vlcSource = if (found) "VLC del sistema" else "VLC no encontrado"
            if (!found) {
                throw IllegalStateException(
                    "No se encontró VLC. Instala VLC 64 bits (https://www.videolan.org) o usa el instalador de VIVI Music, que ya lo incluye.",
                )
            }
        }
    }
}
