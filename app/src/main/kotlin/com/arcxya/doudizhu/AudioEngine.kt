package com.arcxya.doudizhu

import android.content.Context
import android.media.*
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Original synthesized music and cues, rendered locally once into PCM WAV files. */
class AudioEngine(private val context: Context) {
    private val prefs=context.getSharedPreferences("settings",0)
    var music=prefs.getBoolean("music",true);private set
    var effects=prefs.getBoolean("effects",true);private set
    var volume=prefs.getInt("volume",45);private set
    private var active=false;private var focused=false;private var released=false
    private val attrs=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val manager=context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attrs).setOnAudioFocusChangeListener { change ->
        focused=change==AudioManager.AUDIOFOCUS_GAIN
        if(focused&&active&&music) player?.start() else player?.pause()
    }.build()
    private var player:MediaPlayer?=null
    private val pool=SoundPool.Builder().setMaxStreams(3).setAudioAttributes(attrs).build()
    private val cues=mutableMapOf<String,Int>();private val loaded=mutableSetOf<Int>()
    init {
        pool.setOnLoadCompleteListener { _,id,status->if(status==0)loaded.add(id) }
        val melodies=mapOf("select" to listOf(84),"play" to listOf(67,74),"pass" to listOf(55),"bid" to listOf(72,79),"bomb" to listOf(43,55,67),"turn" to listOf(79,84),"win" to listOf(72,76,79,84),"lose" to listOf(67,64,60),"error" to listOf(54,52))
        melodies.forEach { (name,notes)->cues[name]=pool.load(wave(name,notes,.11,false).absolutePath,1) }
        val tune=listOf(72,76,79,76,74,69,67,69,72,74,76,79,81,79,76,74,72,67,69,72,74,76,74,69,67,69,72,76,74,69,67,0)
        player=MediaPlayer().apply { setAudioAttributes(attrs);setDataSource(wave("music",tune,.48,true).absolutePath);isLooping=true;prepare();setVolume(volume/100f*.3f,volume/100f*.3f) }
    }
    private fun wave(name:String,notes:List<Int>,beat:Double,bass:Boolean):File {
        val f=File(context.cacheDir,"native_v2_$name.wav");if(f.exists())return f
        val rate=16000;val n=(notes.size*beat*rate).toInt();val data=ByteBuffer.allocate(44+n*2).order(ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray());data.putInt(36+n*2);data.put("WAVEfmt ".toByteArray());data.putInt(16);data.putShort(1.toShort());data.putShort(1.toShort());data.putInt(rate);data.putInt(rate*2);data.putShort(2.toShort());data.putShort(16.toShort());data.put("data".toByteArray());data.putInt(n*2)
        for(i in 0 until n){val t=i.toDouble()/rate;val index=(t/beat).toInt().coerceAtMost(notes.lastIndex);val local=t-index*beat;val pitch=notes[index];val freq=440*2.0.pow((pitch-69)/12.0);val envelope=min(1.0,local/.025)*exp(-local*5);var sample=if(pitch==0)0.0 else sin(2*PI*freq*t)*envelope*.28
            if(bass)sample+=sin(2*PI*(if(index/8%2==0)130.81 else 174.61)*t)*.06
            val fade=min(1.0,t/.03)*min(1.0,(n.toDouble()/rate-t)/.06);data.putShort((sample*fade*Short.MAX_VALUE).toInt().coerceIn(-32768,32767).toShort())}
        FileOutputStream(f).use{it.write(data.array())};return f
    }
    fun configure(m:Boolean,e:Boolean,v:Int){music=m;effects=e;volume=v.coerceIn(0,100);prefs.edit().putBoolean("music",m).putBoolean("effects",e).putInt("volume",volume).apply();player?.setVolume(volume/100f*.3f,volume/100f*.3f);if(active)resume()}
    fun resume(){if(released)return;active=true;if(music||effects)focused=manager.requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED;if(music&&focused)player?.start() else player?.pause()}
    fun pause(){active=false;player?.pause();pool.autoPause();manager.abandonAudioFocusRequest(focus);focused=false}
    fun cue(name:String){if(!released&&active&&focused&&effects)cues[name]?.takeIf{it in loaded}?.let{pool.play(it,volume/100f*.6f,volume/100f*.6f,1,0,1f)}}
    fun release(){pause();released=true;player?.release();player=null;pool.release()}
}
