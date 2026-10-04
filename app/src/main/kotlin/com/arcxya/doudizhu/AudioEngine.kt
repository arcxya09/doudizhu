package com.arcxya.doudizhu

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Offline recordings extracted from the reference video; no generated tones. */
class AudioEngine(private val context: Context) {
    private val prefs = context.getSharedPreferences("settings", 0)
    var music = prefs.getBoolean("music", true); private set
    var effects = prefs.getBoolean("effects", true); private set
    var volume = prefs.getInt("volume", 45); private set
    private var active = false
    private var focused = false
    private var released = false
    private var ducked = false
    private var foregroundStream = 0
    private val streams = mutableSetOf<Int>()
    private val handler = Handler(Looper.getMainLooper())
    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attrs).setOnAudioFocusChangeListener({ change ->
            focused = change == AudioManager.AUDIOFOCUS_GAIN
            if (!focused) stopCues()
            syncMusic()
        }, Handler(Looper.getMainLooper())).build()
    private var player: MediaPlayer? = null
    private val pool = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
    private val cues = mutableMapOf<String, Int>()
    private val loaded = mutableSetOf<Int>()
    private val restoreMusic = Runnable { ducked = false; applyMusicVolume() }

    init {
        pool.setOnLoadCompleteListener { _, id, status ->
            if (status == 0) loaded.add(id) else Log.w("OfflineAudio", "Cue load failed: $id/$status")
        }
        CUE_DURATIONS.keys.forEach { name ->
            try {
                context.assets.openFd("audio/$name.wav").use { cues[name] = pool.load(it, 1) }
            } catch (error: Exception) {
                Log.w("OfflineAudio", "Cannot load bundled cue $name", error)
            }
        }
        val candidate = MediaPlayer()
        try {
            candidate.setAudioAttributes(attrs)
            context.assets.openFd("audio/table_loop.wav").use {
                candidate.setDataSource(it.fileDescriptor, it.startOffset, it.length)
            }
            candidate.isLooping = true
            candidate.prepare()
            player = candidate
            applyMusicVolume()
        } catch (error: Exception) {
            candidate.release()
            Log.w("OfflineAudio", "Cannot load bundled background music", error)
        }
    }

    private fun applyMusicVolume() {
        if (released) return
        val level = volume / 100f * .4f * if (ducked) .38f else 1f
        player?.setVolume(level, level)
    }

    private fun syncMusic() {
        if (released) return
        val shouldPlay = active && focused && music && volume > 0
        if (shouldPlay) player?.start() else if (player?.isPlaying == true) player?.pause()
        applyMusicVolume()
    }

    private fun stopCues() {
        streams.forEach { pool.stop(it) }
        streams.clear()
        foregroundStream = 0
        handler.removeCallbacksAndMessages(null)
        ducked = false
    }

    fun configure(m: Boolean, e: Boolean, v: Int) {
        if (released) return
        music = m; effects = e; volume = v.coerceIn(0, 100)
        prefs.edit().putBoolean("music", music).putBoolean("effects", effects)
            .putInt("volume", volume).apply()
        if (!effects || volume == 0) stopCues()
        if (active) resume() else applyMusicVolume()
    }

    fun resume() {
        if (released) return
        active = true
        if ((music || effects) && volume > 0) {
            if (!focused) focused = manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            manager.abandonAudioFocusRequest(focus)
            focused = false
        }
        syncMusic()
    }

    fun pause() {
        if (released) return
        active = false
        syncMusic()
        stopCues()
        manager.abandonAudioFocusRequest(focus)
        focused = false
    }

    fun cue(name: String) {
        if (released || !active || !focused || !effects || volume == 0) return
        // The source has no loss/turn announcement. Keep those silent instead
        // of playing an unrelated success clip or inventing an electronic tone.
        val key = if (name == "error") "select" else name
        val id = cues[key]?.takeIf { it in loaded } ?: return
        val duration = CUE_DURATIONS[key] ?: return
        val isVoiceOrEvent = duration >= 500
        if (isVoiceOrEvent && foregroundStream != 0) {
            pool.stop(foregroundStream)
            streams.remove(foregroundStream)
        }
        val level = volume / 100f * if (key == "select") .5f else .9f
        val stream = pool.play(id, level, level, if (isVoiceOrEvent) 2 else 1, 0, 1f)
        if (stream == 0) return
        streams.add(stream)
        if (isVoiceOrEvent) {
            foregroundStream = stream
            ducked = true
            applyMusicVolume()
            handler.removeCallbacks(restoreMusic)
            handler.postDelayed(restoreMusic, duration.toLong() + 80)
        }
        handler.postDelayed({ streams.remove(stream) }, duration.toLong() + 100)
    }

    fun release() {
        if (released) return
        pause()
        released = true
        player?.release(); player = null
        pool.release()
    }

    /** Device test hook: seek near the loop boundary without waiting a full song. */
    internal fun testSeekMusic(positionMs: Int) {
        if (!released) player?.let { it.seekTo(positionMs.coerceIn(0, (it.duration - 1).coerceAtLeast(0))) }
    }

    /** Read-only diagnostics for device tests; never alter playback state. */
    internal fun testState() = AudioState(active, focused, released,
        player?.isPlaying == true, player?.isLooping == true,
        player?.duration ?: 0, player?.currentPosition ?: 0,
        cues.filterValues { it in loaded }.keys.toSet(), streams.size)

    internal data class AudioState(val active: Boolean, val focused: Boolean,
        val released: Boolean, val playing: Boolean, val looping: Boolean,
        val durationMs: Int, val positionMs: Int, val loadedCues: Set<String>,
        val activeStreams: Int)

    companion object {
        internal val CUE_DURATIONS = linkedMapOf(
            "select" to 190, "play" to 190, "bid" to 840, "bid_pass" to 190,
            "pass" to 730, "pair_k" to 940, "pair_a" to 980, "pair_2" to 865,
            "airplane" to 2370, "bomb" to 2680, "rocket" to 2860, "win" to 2510)
    }
}
