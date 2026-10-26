package com.okdrumdrum.app.engine

import android.content.Context
import android.media.AudioManager
import android.util.Log

/**
 * Sole audio producer (PROJECT.md §2): the mixing is a C++ AAudio callback (low-latency
 * MMAP-capable) so triggers enter the device fast output within one callback period.
 * Never requests audio focus (must mix with concurrently playing media).
 */
class DrumEngine {

    /** Per-trigger latency estimate: app segment (touch → absorbed by callback) + output lag. */
    class LatencyStat(val totalMs: Float, val appMs: Float, val outputMs: Float)

    /** Called from the AAudio callback thread; must be cheap. */
    @Volatile var onLatency: ((LatencyStat) -> Unit)? = null

    var sampleRate = 48_000; private set
    var framesPerCallback = 256; private set

    /** Main thread only. */
    fun start(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val nativeRate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48_000
        val nativeBuffer = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 256
        sampleRate = nativeRate
        val result = nativeStart(nativeRate, nativeBuffer * 2)
        if (result != 0) {
            throw IllegalStateException("AAudio stream failed to open: $result")
        }
        framesPerCallback = nativeFramesPerCallback()
        Log.i(TAG, "aaudio started: rate=$sampleRate framesPerCallback=$framesPerCallback (system $nativeRate/$nativeBuffer)")
    }

    /** Any thread. `pcm` is 16-bit mono at the device native sample rate. */
    fun trigger(pcm: ShortArray, gain: Float = 1f) {
        nativeTrigger(pcm, gain, System.nanoTime())
    }

    fun stop() {
        nativeStop()
    }

    // Invoked from the C++ callback thread; JNI needs the JVM method signature prebuilt.
    private fun onNativeLatency(totalMs: Float, appMs: Float, outputMs: Float) {
        onLatency?.invoke(LatencyStat(totalMs, appMs, outputMs))
    }

    private external fun nativeStart(sampleRate: Int, capacityFrames: Int): Int
    private external fun nativeTrigger(pcm: ShortArray, gain: Float, triggerNanos: Long)
    private external fun nativeFramesPerCallback(): Int
    private external fun nativeStop()

    init {
        System.loadLibrary("drum_engine")
    }

    private companion object {
        const val TAG = "OKDrumDrum"
    }
}
