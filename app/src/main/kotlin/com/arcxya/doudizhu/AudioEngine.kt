package com.arcxya.doudizhu

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.SoundPool
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.InputStream
import java.util.concurrent.Executors

// Music and effects used to share one slider that also capped music at 0.4 while effects reached
// 0.9, so the background music could never be raised above the cues. Each channel now owns a level
// that can reach its own ceiling.
private const val MUSIC_MAX = 1f
private const val EFFECT_MAX = .9f
/** The deal cue is 3.9 s of continuous sound, so it sits below the short spoken cues. */
private const val DEAL_TRIM = .75f
private const val DEFAULT_MUSIC_VOLUME = 60
private const val DEFAULT_EFFECT_VOLUME = 55

/** All sounds are bundled or copied from a file explicitly chosen on this device. */
class AudioEngine(context: Context) {
    private val context = context.applicationContext
    private val prefs = context.getSharedPreferences("settings", 0)
    var music = prefs.getBoolean("music", true); private set
    var effects = prefs.getBoolean("effects", true); private set
    var musicVolume = storedLevel("musicVolume", DEFAULT_MUSIC_VOLUME); private set
    var effectVolume = storedLevel("effectVolume", DEFAULT_EFFECT_VOLUME); private set
    /** Falls back to the single slider older builds stored, so an existing choice is not reset. */
    private fun storedLevel(key: String, fallback: Int) = when {
        prefs.contains(key) -> prefs.getInt(key, fallback)
        prefs.contains("volume") -> prefs.getInt("volume", fallback)
        else -> fallback
    }
    var isMusicBusy = true; private set
    val currentMusicName get() = currentSelection?.name ?: "默认背景音乐"
    val hasCustomMusic get() = currentSelection != null
    var onMusicChanged: (() -> Unit)? = null
    private var active = false
    private var focused = false
    @Volatile private var released = false
    private var ducked = false
    private var pendingDeal = false
    private var lastCue: String? = null
    private var foregroundStream = 0
    private val streams = mutableSetOf<Int>()
    private val handler = Handler(Looper.getMainLooper())
    private val store = LocalMusicStore(this.context)
    private var currentSelection: LocalMusicStore.Selection? = null
    private var pendingPlayer: MediaPlayer? = null
    private var pendingSelection: LocalMusicStore.Selection? = null
    private var recoverDefault = false
    private var preparationTimeout: Runnable? = null
    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attrs).setOnAudioFocusChangeListener({ change ->
            if (!released) {
                focused = change == AudioManager.AUDIOFOCUS_GAIN
                if (!focused) stopCues()
                syncMusic()
                flushDeal()
            }
        }, handler).build()
    private var player: MediaPlayer? = null
    private val pool = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
    private val cues = mutableMapOf<String, Int>()
    private val loaded = mutableSetOf<Int>()
    private val restoreMusic = Runnable { ducked = false; applyMusicVolume() }

    init {
        pool.setOnLoadCompleteListener { _, id, status ->
            if (!released) {
                if (status == 0) loaded.add(id) else Log.w("OfflineAudio", "Cue load failed: $id/$status")
                flushDeal()
            }
        }
        CUE_DURATIONS.keys.forEach { name ->
            try {
                context.assets.openFd("audio/$name.wav").use { cues[name] = pool.load(it, 1) }
            } catch (error: Exception) { Log.w("OfflineAudio", "Cannot load bundled cue $name", error) }
        }
        FILE_WORKER.execute {
            val saved = store.read()
            handler.post {
                if (!released) loadInitialMusic(saved)
            }
        }
    }

    private fun loadInitialMusic(saved: LocalMusicStore.Selection?) {
        prepareCandidate(saved, { adopt(it, saved) }, {
            if (saved != null) loadInitialMusic(null)
            else { isMusicBusy = false; onMusicChanged?.invoke() }
        })
    }

    data class MusicImportResult(val success: Boolean, val message: String)

    fun importMusic(uri: Uri, onResult: (MusicImportResult) -> Unit) {
        beginImport({ store.stage(uri) }, onResult)
    }

    /** Streams are consumed and closed on the file worker, including failure paths. */
    internal fun importMusic(input: InputStream, displayName: String, onResult: (MusicImportResult) -> Unit) {
        if (released || isMusicBusy) {
            FILE_WORKER.execute { runCatching { input.close() } }
            if (!released) onResult(MusicImportResult(false, "音乐正在处理，请稍候"))
            return
        }
        beginImport({ store.stage(input, displayName) }, onResult)
    }

    private fun beginImport(stage: () -> LocalMusicStore.Selection, onResult: (MusicImportResult) -> Unit) {
        if (released) return
        if (isMusicBusy) { onResult(MusicImportResult(false, "音乐正在处理，请稍候")); return }
        isMusicBusy = true; onMusicChanged?.invoke()
        FILE_WORKER.execute {
            val result = runCatching(stage)
            handler.post {
                if (released) {
                    result.getOrNull()?.let { imported -> discard(imported) }
                    return@post
                }
                val imported = result.getOrNull()
                if (imported == null) {
                    finishFailure(result.exceptionOrNull()?.message ?: "无法读取所选音频", onResult)
                } else prepareCandidate(imported, { candidate ->
                    commitCandidate(candidate, imported, onResult)
                }, { message ->
                    discard(imported)
                    finishFailure(message, onResult)
                })
            }
        }
    }

    fun restoreDefaultMusic(onResult: (MusicImportResult) -> Unit) {
        if (released) return
        if (isMusicBusy) { onResult(MusicImportResult(false, "音乐正在处理，请稍候")); return }
        isMusicBusy = true; onMusicChanged?.invoke()
        prepareCandidate(null, { commitCandidate(it, null, onResult) }, { finishFailure(it, onResult) })
    }

    /** Preparing a replacement never touches the currently playing music. */
    private fun prepareCandidate(selection: LocalMusicStore.Selection?, ready: (MediaPlayer) -> Unit, failed: (String) -> Unit) {
        val candidate = MediaPlayer()
        pendingPlayer = candidate; pendingSelection = selection
        fun fail() {
            if (pendingPlayer !== candidate) return
            preparationTimeout?.let { handler.removeCallbacks(it) }; preparationTimeout = null
            pendingPlayer = null; pendingSelection = null; candidate.release()
            if (!released) failed("无法播放这个文件，请选择有效的音频")
        }
        candidate.setOnPreparedListener {
            if (released || pendingPlayer !== candidate) return@setOnPreparedListener
            preparationTimeout?.let { handler.removeCallbacks(it) }; preparationTimeout = null
            if (candidate.duration <= 0) { fail(); return@setOnPreparedListener }
            candidate.setOnPreparedListener(null)
            candidate.setOnErrorListener(null)
            ready(candidate)
        }
        candidate.setOnErrorListener { _, _, _ -> fail(); true }
        try {
            candidate.setAudioAttributes(attrs)
            if (selection != null) candidate.setDataSource(selection.file.absolutePath)
            else context.assets.openFd("audio/table_loop.ogg").use {
                candidate.setDataSource(it.fileDescriptor, it.startOffset, it.length)
            }
            candidate.isLooping = true
            candidate.setVolume(0f, 0f)
            preparationTimeout = Runnable { fail() }.also { handler.postDelayed(it, 20000) }
            candidate.prepareAsync()
        } catch (_: Exception) { fail() }
    }

    private fun commitCandidate(candidate: MediaPlayer, selection: LocalMusicStore.Selection?, onResult: (MusicImportResult) -> Unit) {
        val previous = currentSelection
        FILE_WORKER.execute {
            val result = runCatching { store.commit(selection) }
            handler.post {
                if (released) {
                    // A completed atomic selection remains valid for the next Activity.
                    selection?.let { discard(it) }
                    if (result.isSuccess) previous?.let { discard(it) }
                    return@post
                }
                if (result.isSuccess) {
                    adopt(candidate, selection)
                    previous?.let { discard(it) }
                    onResult(MusicImportResult(true, if (selection == null) "已恢复默认音乐" else "已使用所选音乐"))
                } else {
                    if (pendingPlayer === candidate) { pendingPlayer = null; pendingSelection = null; candidate.release() }
                    selection?.let { discard(it) }
                    finishFailure("无法保存音乐，原来的音乐已保留", onResult)
                }
            }
        }
    }

    private fun adopt(candidate: MediaPlayer, selection: LocalMusicStore.Selection?) {
        if (released) return
        player?.release()
        player = candidate; pendingPlayer = null; pendingSelection = null; currentSelection = selection
        recoverDefault = false
        candidate.setOnErrorListener { failed, what, extra ->
            Log.w("OfflineAudio", "Playback failed: $what/$extra")
            recoverMusic(failed)
            true
        }
        isMusicBusy = false
        syncMusic()
        onMusicChanged?.invoke()
    }

    private fun finishFailure(message: String, callback: (MusicImportResult) -> Unit) {
        isMusicBusy = false; onMusicChanged?.invoke()
        callback(MusicImportResult(false, message))
        recoverMusicIfNeeded()
    }

    private fun discard(selection: LocalMusicStore.Selection) {
        FILE_WORKER.execute {
            runCatching { store.discardIfUnselected(selection) }
                .onFailure { Log.w("OfflineAudio", "Cannot clean unused local music", it) }
        }
    }

    private fun recoverMusic(failed: MediaPlayer) {
        if (released || player !== failed) return
        val broken = currentSelection
        player = null; currentSelection = null
        failed.setOnErrorListener(null)
        failed.release()
        if (broken != null) {
            recoverDefault = true
            FILE_WORKER.execute {
                runCatching { store.forgetIfSelected(broken) }
                    .onFailure { Log.w("OfflineAudio", "Cannot clear broken local music", it) }
            }
        }
        // A replacement being prepared or committed owns pendingPlayer until it finishes.
        recoverMusicIfNeeded()
        onMusicChanged?.invoke()
    }

    private fun recoverMusicIfNeeded() {
        if (released || isMusicBusy || !recoverDefault) return
        recoverDefault = false; isMusicBusy = true
        loadInitialMusic(null)
    }

    private inline fun withMusicPlayer(action: (MediaPlayer) -> Unit) {
        val current = player ?: return
        try { action(current) }
        catch (error: IllegalStateException) {
            Log.w("OfflineAudio", "Cannot use music player; recovering", error)
            recoverMusic(current)
        }
    }

    private fun applyMusicVolume() {
        if (released) return
        val level = musicVolume / 100f * MUSIC_MAX * if (ducked) .38f else 1f
        withMusicPlayer { it.setVolume(level, level) }
    }
    private fun syncMusic() {
        if (released) return
        withMusicPlayer {
            if (active && focused && music && musicVolume > 0) it.start()
            else if (it.isPlaying) it.pause()
        }
        applyMusicVolume()
    }
    private fun stopCues() {
        streams.forEach { pool.stop(it) }; streams.clear()
        foregroundStream = 0; pendingDeal = false
        handler.removeCallbacks(restoreMusic)
        ducked = false
    }
    fun configure(m: Boolean, e: Boolean, musicLevel: Int, effectLevel: Int) {
        if (released) return
        music = m; effects = e
        musicVolume = musicLevel.coerceIn(0, 100); effectVolume = effectLevel.coerceIn(0, 100)
        prefs.edit().putBoolean("music", music).putBoolean("effects", effects)
            .putInt("musicVolume", musicVolume).putInt("effectVolume", effectVolume).apply()
        if (!effects || effectVolume == 0) stopCues()
        if (active) resume() else applyMusicVolume()
    }
    fun resume() {
        if (released) return
        active = true
        if ((music && musicVolume > 0) || (effects && effectVolume > 0)) {
            if (!focused) focused = manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else { manager.abandonAudioFocusRequest(focus); focused = false }
        syncMusic(); flushDeal()
    }
    fun pause() {
        if (released) return
        active = false; syncMusic(); stopCues()
        manager.abandonAudioFocusRequest(focus); focused = false
    }
    /** Called once for a newly dealt table, never for a restored table. */
    fun requestDeal() {
        if (released || !effects || effectVolume == 0) return
        pendingDeal = true; flushDeal()
    }
    private fun flushDeal() {
        if (pendingDeal && !released && active && focused && effects && effectVolume > 0 && cues["deal"] in loaded) {
            pendingDeal = false; cue("deal")
        }
    }
    fun cue(name: String) {
        if (released || !active || !focused || !effects || effectVolume == 0) return
        val key = if (name == "error") "select" else name
        // If play has already advanced, a slowly loaded shuffle must not interrupt it.
        if (key != "deal") pendingDeal = false
        val id = cues[key]?.takeIf { it in loaded } ?: return
        val duration = CUE_DURATIONS[key] ?: return
        // Some spoken ranks are shorter than 500 ms; classification is semantic.
        val isVoiceOrEvent = key != "select" && key != "play"
        if (isVoiceOrEvent && foregroundStream != 0) { pool.stop(foregroundStream); streams.remove(foregroundStream) }
        val trim = when (key) { "deal" -> DEAL_TRIM; "select" -> .5f; else -> 1f }
        val level = effectVolume / 100f * EFFECT_MAX * trim
        val stream = pool.play(id, level, level, if (isVoiceOrEvent) 2 else 1, 0, 1f)
        if (stream == 0) return
        lastCue = key; streams.add(stream)
        if (isVoiceOrEvent) {
            foregroundStream = stream; ducked = true; applyMusicVolume()
            handler.removeCallbacks(restoreMusic); handler.postDelayed(restoreMusic, duration.toLong() + 80)
        }
        handler.postDelayed({ streams.remove(stream) }, duration.toLong() + 100)
    }
    fun release() {
        if (released) return
        pause(); released = true; onMusicChanged = null
        preparationTimeout?.let { handler.removeCallbacks(it) }; preparationTimeout = null
        pendingPlayer?.release(); pendingPlayer = null
        pendingSelection?.let { discard(it) }; pendingSelection = null
        player?.release(); player = null; pool.release()
    }
    internal fun testSeekMusic(positionMs: Int) {
        if (!released) player?.let { it.seekTo(positionMs.coerceIn(0, (it.duration - 1).coerceAtLeast(0))) }
    }
    internal fun testState() = AudioState(active, focused, released,
        player?.isPlaying == true, player?.isLooping == true, player?.duration ?: 0, player?.currentPosition ?: 0,
        cues.filterValues { it in loaded }.keys.toSet(), streams.size,
        if (hasCustomMusic) "local" else "bundled", currentMusicName, pendingDeal, lastCue)
    internal data class AudioState(val active: Boolean, val focused: Boolean, val released: Boolean,
        val playing: Boolean, val looping: Boolean, val durationMs: Int, val positionMs: Int,
        val loadedCues: Set<String>, val activeStreams: Int, val musicSource: String,
        val musicName: String, val pendingDeal: Boolean, val lastCue: String?)

    companion object {
        private val FILE_WORKER = Executors.newSingleThreadExecutor { work -> Thread(work, "LocalMusicFiles") }
        internal val CUE_DURATIONS = linkedMapOf(
            "select" to 183,
            "play" to 98,
            "deal" to 3944,
            "bid" to 679,
            "bid_pass" to 522,
            "pass" to 784,
            "bomb" to 2879,
            "rocket" to 1700,
            "airplane" to 1876,
            "win" to 4881,
            "lose" to 5725,
            "straight" to 784,
            "pairs" to 548,
            "triple_single" to 940,
            "triple_pair" to 1175,
            "four_single" to 705,
            "four_pair" to 1045,
            "single_3" to 496,
            "pair_3" to 575,
            "triple_3" to 862,
            "single_4" to 470,
            "pair_4" to 705,
            "triple_4" to 810,
            "single_5" to 653,
            "pair_5" to 653,
            "triple_5" to 1071,
            "single_6" to 418,
            "pair_6" to 601,
            "triple_6" to 810,
            "single_7" to 548,
            "pair_7" to 601,
            "triple_7" to 940,
            "single_8" to 444,
            "pair_8" to 679,
            "triple_8" to 810,
            "single_9" to 575,
            "pair_9" to 575,
            "triple_9" to 966,
            "single_10" to 522,
            "pair_10" to 757,
            "triple_10" to 888,
            "single_11" to 522,
            "pair_11" to 522,
            "triple_11" to 836,
            "single_12" to 575,
            "pair_12" to 522,
            "triple_12" to 914,
            "single_13" to 522,
            "pair_k" to 522,
            "triple_13" to 862,
            "single_14" to 444,
            "pair_a" to 601,
            "triple_14" to 862,
            "single_15" to 522,
            "pair_2" to 522,
            "triple_15" to 914,
            "single_16" to 784,
            "single_17" to 731,
            "bid_1" to 425,
            "bid_2" to 525,
            "bid_3" to 576,
            "cannot_beat" to 1078)
    }
}
