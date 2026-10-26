package com.okdrumdrum.app.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Process
import java.util.Arrays
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Sole audio producer (PROJECT.md §2): an always-on pull mixer on an AudioTrack fast path.
 * The mixer thread writes silence by default; a trigger enqueues a voice that starts mixing
 * at the next chunk, so a hit enters the output buffer within one chunk (~1 buffer latency).
 * Never requests audio focus (must mix with concurrently playing media).
 */
class DrumEngine {

    /** Per-trigger latency estimate: app segment (touch→mix) + output segment (buffer lag). */
    class LatencyStat(val totalMs: Float, val appMs: Float, val outputMs: Float)

    /** Called from the mixer thread; must be cheap. */
    @Volatile var onLatency: ((LatencyStat) -> Unit)? = null

    var sampleRate = 48_000; private set
    var framesPerBuffer = 256; private set

    @Volatile private var running = false
    private var track: AudioTrack? = null
    private var mixerThread: Thread? = null

    private class Voice(val pcm: ShortArray, val gain: Float) { var pos = 0 }

    /** (voice, trigger nanos). Pair allocated on the caller thread, never on the mixer. */
    private val pending = ConcurrentLinkedQueue<Pair<Voice, Long>>()

    /** Main thread only. */
    fun start(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        sampleRate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48_000
        framesPerBuffer = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 256

        val minBufBytes =
            AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufferBytes = maxOf(minBufBytes, framesPerBuffer * 2 * 2)

        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferBytes)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
            .also { t ->
                // Prime the buffer with silence so the write loop paces steadily from t0.
                t.write(ShortArray(bufferBytes / 2), 0, bufferBytes / 2, AudioTrack.WRITE_BLOCKING)
                t.play()
            }

        running = true
        mixerThread = Thread({ mixerLoop(track!!) }, "DrumMixer").also { it.start() }
    }

    /** Any thread. `pcm` is 16-bit mono at the device native sample rate. */
    fun trigger(pcm: ShortArray, gain: Float = 1f) {
        pending.add(Voice(pcm, gain) to System.nanoTime())
    }

    fun stop() {
        if (!running) return
        running = false
        mixerThread?.join(200)
        mixerThread = null
        track?.let { t ->
            runCatching { t.stop() }
            runCatching { t.release() }
        }
        track = null
    }

    private fun mixerLoop(track: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val sr = sampleRate
        val chunk = ShortArray(framesPerBuffer)
        val acc = IntArray(framesPerBuffer)
        val ts = AudioTimestamp()
        val voices = ArrayList<Voice>(MAX_VOICES)
        var framesWritten = 0L
        var lagFrames = 0f
        var sinceCalibration = CALIBRATE_EVERY_CHUNKS

        while (running) {
            val now = System.nanoTime()

            // Calibrate playhead lag: how far ahead of actual playback our writes sit.
            if (++sinceCalibration >= CALIBRATE_EVERY_CHUNKS && track.getTimestamp(ts)) {
                val expectedNowFrames = ts.framePosition + (now - ts.nanoTime) * sr / 1_000_000_000L
                lagFrames = (framesWritten - expectedNowFrames).coerceAtLeast(0L).toFloat()
                sinceCalibration = 0
            }

            // Absorb triggers at a chunk boundary; each voice starts at the chunk just written.
            while (true) {
                val item = pending.poll() ?: break
                if (voices.size == MAX_VOICES) voices.removeAt(0)
                voices.add(item.first)
                val appMs = (now - item.second) / 1_000_000f
                val outputMs = lagFrames / sr * 1000f
                onLatency?.invoke(LatencyStat(appMs + outputMs, appMs, outputMs))
            }

            Arrays.fill(acc, 0)
            val it = voices.iterator()
            while (it.hasNext()) {
                val v = it.next()
                var i = 0
                while (i < chunk.size && v.pos < v.pcm.size) {
                    acc[i++] += (v.pcm[v.pos++] * v.gain).toInt()
                }
                if (v.pos >= v.pcm.size) it.remove()
            }
            for (i in chunk.indices) {
                chunk[i] = acc[i].coerceIn(-32768, 32767).toShort()
            }

            val written = track.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
            if (written < 0) break
            framesWritten += written.toLong()
        }
    }

    private companion object {
        const val MAX_VOICES = 16
        const val CALIBRATE_EVERY_CHUNKS = 96
    }
}
