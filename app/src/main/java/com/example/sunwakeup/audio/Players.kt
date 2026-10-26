package com.example.sunwakeup.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.PowerManager
import android.os.Process
import android.util.Log
import com.example.sunwakeup.data.SoundConfig
import com.example.sunwakeup.data.SoundType
import kotlin.math.tanh

/** A looping sound whose loudness can be changed while it plays. */
interface SoundPlayer {
    /** Perceived loudness 0..1. Changes are smoothed, so calling this often is fine. */
    fun setVolume(volume: Float)
    fun stop()
}

/** Squares the perceived loudness so sliders and ramps sound even. */
private fun gainFor(volume: Float) = volume.coerceIn(0f, 1f).let { it * it }

private fun attributes(alarm: Boolean): AudioAttributes = AudioAttributes.Builder()
    .setUsage(if (alarm) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA)
    .setContentType(
        if (alarm) AudioAttributes.CONTENT_TYPE_SONIFICATION else AudioAttributes.CONTENT_TYPE_MUSIC
    )
    .build()

/**
 * Starts [config] silently; raise the volume with [SoundPlayer.setVolume].
 * [alarm] routes the sound through the alarm stream so it plays during Do Not Disturb.
 */
fun createPlayer(context: Context, config: SoundConfig, alarm: Boolean): SoundPlayer? = try {
    when (config.type) {
        SoundType.SYSTEM_ALARM -> {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            UriPlayer(context, uri, alarm)
        }
        SoundType.CUSTOM -> config.customUri?.let { UriPlayer(context, Uri.parse(it), alarm) }
        else -> SynthPlayer(generatorFor(config.type), alarm)
    }
} catch (e: Exception) {
    Log.w("SunWakeup", "Could not play ${config.type}", e)
    // Fall back to something that always works rather than staying silent.
    if (alarm) SynthPlayer(generatorFor(SoundType.CHIMES), true) else null
}

/**
 * Plays a [Generator] for as long as needed. It runs for hours at night, so it writes in large chunks
 * into a deep buffer in the power-saving mode, which lets the processor sleep between writes; volume
 * changes still come through within half a second.
 */
class SynthPlayer(private val generator: Generator, alarm: Boolean) : SoundPlayer {
    @Volatile private var targetGain = 0f
    @Volatile private var running = true

    private val track: AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(attributes(alarm))
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
        )
        .setBufferSizeInBytes(
            maxOf(
                AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT),
                BUFFER_SAMPLES * Float.SIZE_BYTES,
            )
        )
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_POWER_SAVING)
        .build()

    private val thread = Thread({
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val chunk = FloatArray(CHUNK_SAMPLES)
        var gain = 0f
        // Reaches a new target in about half a second without clicks.
        val smoothing = 1f / (SAMPLE_RATE * 0.15f)
        track.play()
        while (running) {
            for (i in chunk.indices) {
                gain += (targetGain - gain) * smoothing
                chunk[i] = tanh(generator.next() * gain * 1.2f)
            }
            if (track.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING) < 0) break
        }
        // Stop at once rather than play out what is still buffered.
        runCatching {
            track.pause()
            track.flush()
        }
        track.release()
    }, "SunWakeup-synth").apply { start() }

    override fun setVolume(volume: Float) {
        targetGain = gainFor(volume)
    }

    override fun stop() {
        running = false
    }

    private companion object {
        /** Half a second of sound buffered ahead, filled a tenth of a second at a time. */
        const val BUFFER_SAMPLES = SAMPLE_RATE / 2
        const val CHUNK_SAMPLES = 4096
    }
}

class UriPlayer(context: Context, uri: Uri, alarm: Boolean) : SoundPlayer {
    private val player = MediaPlayer().apply {
        setAudioAttributes(attributes(alarm))
        setDataSource(context, uri)
        isLooping = true
        setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
        setVolume(0f, 0f)
        prepare()
        start()
    }

    override fun setVolume(volume: Float) {
        val g = gainFor(volume)
        runCatching { player.setVolume(g, g) }
    }

    override fun stop() {
        runCatching { player.stop() }
        player.release()
    }
}
