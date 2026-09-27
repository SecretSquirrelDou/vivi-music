package com.music.vivi.desktop.playback

import com.music.innertube.YouTube
import com.music.innertube.models.WatchEndpoint
import com.music.vivi.desktop.data.AppDirs
import com.music.vivi.desktop.data.ArtistRef
import com.music.vivi.desktop.data.MusicDatabase
import com.music.vivi.desktop.data.SettingsStore
import com.music.vivi.desktop.data.Track
import com.music.vivi.desktop.data.toTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.pow

enum class RepeatMode { OFF, ALL, ONE }

data class PlayerState(
    val queue: List<Track> = emptyList(),
    val index: Int = -1,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val durationMs: Long = 0,
    val repeat: RepeatMode = RepeatMode.OFF,
    val shuffle: Boolean = false,
    val volume: Int = 80,
    val error: String? = null,
    val queueTitle: String? = null,
) {
    val current: Track? get() = queue.getOrNull(index)
    val hasNext: Boolean get() = index < queue.lastIndex || repeat != RepeatMode.OFF
}

/**
 * Cola de reproducción + control del motor. Equivalente a MusicService/PlayerConnection de Android:
 *  • Radio automática (YouTube.next con "RDAMVM<id>") como "Iniciar radio" en Android.
 *  • Autocompletado de la cola al llegar al final (continuaciones de YouTube Music).
 *  • Registro de historial (tabla event), tiempo total y contador mensual — igual que Android,
 *    por eso las estadísticas se sincronizan entre ambos.
 *  • Normalización de volumen usando loudnessDb de YouTube.
 */
class PlayerController(
    private val db: MusicDatabase,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) : AudioEngine.Listener {
    private val _state = MutableStateFlow(PlayerState(volume = settings.value.volume))
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()

    private val proxy = StreamProxy()
    private var engine: AudioEngine? = null
    var engineError: String? = null
        private set

    private var radioEndpoint: WatchEndpoint? = null
    private var radioContinuation: String? = null
    private var loadingMore = false
    private var playJob: Job? = null
    private var currentLoudness: Double? = null

    // Contabilidad de tiempo escuchado (para el historial)
    private var listenedMs = 0L
    private var lastTick = 0L

    private val queueFile = File(AppDirs.root, "queue.json")
    private val json = Json { ignoreUnknownKeys = true }

    init {
        engine = runCatching { AudioEngine(this) }.onFailure { engineError = it.message }.getOrNull()
        if (settings.value.persistQueue) restoreQueue()
    }

    // ───────────────────────── API pública ─────────────────────────

    /** Reproduce una lista completa empezando en [startIndex]. */
    fun playQueue(tracks: List<Track>, startIndex: Int = 0, title: String? = null) {
        if (tracks.isEmpty()) return
        radioEndpoint = null
        radioContinuation = null
        val list = if (_state.value.shuffle) {
            val first = tracks[startIndex]
            listOf(first) + (tracks - first).shuffled()
        } else tracks
        val idx = if (_state.value.shuffle) 0 else startIndex
        _state.update { it.copy(queue = list, index = idx, queueTitle = title, error = null) }
        startCurrent()
    }

    /** Reproduce una canción y genera una radio a partir de ella (como en Android). */
    fun playWithRadio(track: Track) {
        _state.update { it.copy(queue = listOf(track), index = 0, queueTitle = "Radio · ${track.title}", error = null) }
        radioEndpoint = track.endpoint?.takeIf { it.playlistId != null } ?: WatchEndpoint(videoId = track.id, playlistId = "RDAMVM${track.id}")
        radioContinuation = null
        startCurrent()
        scope.launch(Dispatchers.IO) { loadRadio(initial = true) }
    }

    /** Reproduce una playlist/álbum de YouTube por su endpoint (botones "Reproducir" / "Aleatorio" / "Radio"). */
    fun playEndpoint(endpoint: WatchEndpoint, title: String? = null) {
        scope.launch(Dispatchers.IO) {
            _state.update { it.copy(isLoading = true, error = null) }
            YouTube.next(endpoint).onSuccess { result ->
                val tracks = result.items.map { it.toTrack() }
                if (tracks.isEmpty()) return@onSuccess
                radioEndpoint = result.endpoint
                radioContinuation = result.continuation
                _state.update { it.copy(queue = tracks, index = (result.currentIndex ?: 0).coerceIn(0, tracks.lastIndex), queueTitle = title ?: result.title) }
                startCurrent()
            }.onFailure { e -> _state.update { it.copy(isLoading = false, error = e.message) } }
        }
    }

    fun playNext(track: Track) {
        val s = _state.value
        if (s.queue.isEmpty()) {
            _state.value = s.copy(queue = listOf(track), index = 0)
            startCurrent()
            return
        }
        _state.value = s.copy(queue = s.queue.toMutableList().apply { add(s.index + 1, track) })
    }

    fun addToQueue(tracks: List<Track>) {
        val s = _state.value
        if (s.queue.isEmpty()) {
            _state.value = s.copy(queue = tracks, index = 0)
            startCurrent()
            return
        }
        _state.value = s.copy(queue = s.queue + tracks)
    }

    fun removeFromQueue(i: Int) {
        val s = _state.value
        if (i !in s.queue.indices || i == s.index) return
        _state.value = s.copy(queue = s.queue.toMutableList().apply { removeAt(i) }, index = if (i < s.index) s.index - 1 else s.index)
    }

    fun moveInQueue(from: Int, to: Int) {
        val s = _state.value
        if (from !in s.queue.indices || to !in s.queue.indices) return
        val q = s.queue.toMutableList()
        q.add(to, q.removeAt(from))
        val cur = s.current
        _state.value = s.copy(queue = q, index = q.indexOfFirst { it === cur }.coerceAtLeast(0))
    }

    fun jumpTo(i: Int) {
        if (i !in _state.value.queue.indices) return
        commitListen()
        _state.update { it.copy(index = i) }
        startCurrent()
    }

    fun togglePlay() {
        val s = _state.value
        if (s.current == null) return
        if (s.isPlaying) engine?.pause() else {
            if (_position.value == 0L && !s.isLoading) startCurrent() else engine?.resume()
        }
    }

    fun next() {
        val s = _state.value
        if (s.queue.isEmpty()) return
        commitListen()
        val nextIndex = when {
            s.index < s.queue.lastIndex -> s.index + 1
            s.repeat == RepeatMode.ALL -> 0
            else -> return
        }
        _state.update { it.copy(index = nextIndex) }
        startCurrent()
    }

    fun previous() {
        val s = _state.value
        if (_position.value > 3_000 || s.index <= 0) {
            seek(0)
            return
        }
        commitListen()
        _state.update { it.copy(index = s.index - 1) }
        startCurrent()
    }

    fun seek(ms: Long) {
        _position.value = ms
        engine?.seek(ms)
    }

    fun setVolume(v: Int) {
        _state.update { it.copy(volume = v.coerceIn(0, 100)) }
        applyVolume()
        settings.update { it.copy(volume = v.coerceIn(0, 100)) }
    }

    fun toggleShuffle() {
        val s = _state.value
        if (!s.shuffle && s.queue.size > 2) {
            val cur = s.queue[s.index]
            val rest = s.queue.filterIndexed { i, _ -> i != s.index }.shuffled()
            _state.value = s.copy(shuffle = true, queue = listOf(cur) + rest, index = 0)
        } else {
            _state.update { it.copy(shuffle = !it.shuffle) }
        }
    }

    fun cycleRepeat() = _state.update {
        it.copy(repeat = when (it.repeat) { RepeatMode.OFF -> RepeatMode.ALL; RepeatMode.ALL -> RepeatMode.ONE; RepeatMode.ONE -> RepeatMode.OFF })
    }

    fun clearError() = _state.update { it.copy(error = null) }

    fun clearQueue() {
        commitListen()
        engine?.stop()
        _state.update { it.copy(queue = emptyList(), index = -1, isPlaying = false, durationMs = 0) }
        _position.value = 0
    }

    fun shutdown() {
        commitListen()
        saveQueue()
        engine?.release()
        proxy.stop()
    }

    // ───────────────────────── interno ─────────────────────────

    private fun startCurrent(startMs: Long = 0) {
        val track = _state.value.current ?: return
        val eng = engine
        if (eng == null) {
            _state.update { it.copy(error = engineError ?: "Motor de audio no disponible") }
            return
        }
        playJob?.cancel()
        _position.value = startMs
        listenedMs = 0
        lastTick = 0
        _state.update { it.copy(isLoading = true, error = null, durationMs = if (track.durationSec > 0) track.durationSec * 1000L else 0) }
        playJob = scope.launch(Dispatchers.IO) {
            val stream = StreamResolver.resolve(track.id)
            stream.onSuccess { s ->
                currentLoudness = s.loudnessDb
                applyVolume()
                eng.play(proxy.urlFor(track.id), startMs)
                // Pre-resuelve la siguiente canción para que el cambio sea inmediato
                _state.value.queue.getOrNull(_state.value.index + 1)?.let { nextTrack -> StreamResolver.resolve(nextTrack.id) }
            }.onFailure { e ->
                _state.update { it.copy(isLoading = false, isPlaying = false, error = "No se pudo reproducir \"${track.title}\": ${e.message?.lineSequence()?.firstOrNull()}") }
            }
            if (_state.value.index >= _state.value.queue.size - 3) loadRadio(initial = false)
            saveQueue()
        }
    }

    private suspend fun loadRadio(initial: Boolean) {
        val endpoint = radioEndpoint ?: return
        if (loadingMore) return
        if (!initial && radioContinuation == null) return
        loadingMore = true
        try {
            YouTube.next(endpoint, if (initial) null else radioContinuation).onSuccess { result ->
                radioContinuation = result.continuation
                val existing = _state.value.queue.map { it.id }.toSet()
                val newTracks = result.items.map { it.toTrack() }.filter { it.id !in existing }
                if (newTracks.isNotEmpty()) _state.update { it.copy(queue = it.queue + newTracks) }
            }
        } finally {
            loadingMore = false
        }
    }

    private fun applyVolume() {
        val base = _state.value.volume
        val loud = currentLoudness
        val factor = if (settings.value.normalizeAudio && loud != null && loud > 0) 10.0.pow(-loud / 20.0) else 1.0
        engine?.setVolume((base * factor).toInt())
    }

    private fun commitListen() {
        val track = _state.value.current ?: return
        val ms = listenedMs
        listenedMs = 0
        if (ms >= 5_000) {
            scope.launch(Dispatchers.IO) { runCatching { db.recordPlay(track, ms) } }
        }
    }

    // Callbacks de VLC (hilo nativo)

    override fun onPlaying() = _state.update { it.copy(isPlaying = true, isLoading = false) }
    override fun onPaused() {
        lastTick = 0
        _state.update { it.copy(isPlaying = false) }
    }

    override fun onFinished() {
        scope.launch {
            val s = _state.value
            if (s.repeat == RepeatMode.ONE) {
                commitListen()
                startCurrent()
            } else if (s.index < s.queue.lastIndex || s.repeat == RepeatMode.ALL) {
                next()
            } else {
                commitListen()
                _state.update { it.copy(isPlaying = false) }
                _position.value = 0
            }
        }
    }

    override fun onError() {
        val track = _state.value.current ?: return
        StreamResolver.invalidate(track.id)
        _state.update { it.copy(isPlaying = false, isLoading = false, error = "Error de reproducción en \"${track.title}\"") }
    }

    override fun onTime(ms: Long) {
        val now = System.currentTimeMillis()
        if (lastTick != 0L && _state.value.isPlaying) listenedMs += (now - lastTick).coerceIn(0, 2_000)
        lastTick = now
        _position.value = ms
    }

    override fun onLength(ms: Long) {
        if (ms > 0) _state.update { it.copy(durationMs = ms) }
    }

    override fun onBuffering(percent: Float) {
        _state.update { it.copy(isLoading = percent < 100f && !it.isPlaying) }
    }

    // ───────────────────────── persistencia de la cola ─────────────────────────

    @Serializable
    private data class SavedTrack(
        val id: String, val title: String, val artists: List<String>, val artistIds: List<String?>,
        val thumbnail: String?, val duration: Int, val albumId: String?, val albumName: String?,
    )

    @Serializable
    private data class SavedQueue(val title: String?, val index: Int, val position: Long, val tracks: List<SavedTrack>)

    private fun saveQueue() {
        if (!settings.value.persistQueue) return
        val s = _state.value
        runCatching {
            val data = SavedQueue(
                s.queueTitle, s.index, _position.value,
                s.queue.take(500).map { t ->
                    SavedTrack(t.id, t.title, t.artists.map { it.name }, t.artists.map { it.id }, t.thumbnail, t.durationSec, t.albumId, t.albumName)
                },
            )
            queueFile.writeText(json.encodeToString(SavedQueue.serializer(), data))
        }
    }

    private fun restoreQueue() {
        runCatching {
            if (!queueFile.exists()) return
            val data = json.decodeFromString(SavedQueue.serializer(), queueFile.readText())
            val tracks = data.tracks.map { t ->
                Track(
                    id = t.id, title = t.title,
                    artists = t.artists.mapIndexed { i, n -> ArtistRef(t.artistIds.getOrNull(i), n) },
                    thumbnail = t.thumbnail, durationSec = t.duration, albumId = t.albumId, albumName = t.albumName,
                    liked = db.isLiked(t.id),
                )
            }
            if (tracks.isNotEmpty()) {
                _state.update { it.copy(queue = tracks, index = data.index.coerceIn(0, tracks.lastIndex), queueTitle = data.title, durationMs = tracks[data.index.coerceIn(0, tracks.lastIndex)].durationSec * 1000L) }
            }
        }
    }
}
