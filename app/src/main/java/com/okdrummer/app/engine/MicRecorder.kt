package com.okdrummer.app.engine

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission

/**
 * Mic capture for custom pad sounds (PROJECT.md §2): mono 16-bit PCM at the device native
 * rate — the exact format the mixer consumes, so a saved hit is triggerable with no rework.
 * Callbacks are invoked from the recorder thread; they must be cheap and thread-safe.
 */
class MicRecorder(
    private val sampleRate: Int,
    private val maxSeconds: Float = 10f,
) {

    @Volatile var onProgress: ((Float) -> Unit)? = null
    @Volatile var onComplete: ((ShortArray?) -> Unit)? = null

    @Volatile private var active = false
    @Volatile private var cancelled = false
    private var thread: Thread? = null

    /** Callers MUST hold RECORD_AUDIO (only entered after the runtime grant in MainActivity). */
    @RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    fun start() {
        val minBytes = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBytes, sampleRate * 2), // ≳200 ms headroom
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            onComplete?.invoke(null)
            return
        }
        record.startRecording()
        active = true
        cancelled = false
        thread = Thread({ readLoop(record) }, "MicRecorder").also { it.start() }
    }

    /** Stop and deliver what was captured (≤ 10 s). */
    fun stop() {
        active = false
    }

    /** Stop and discard. */
    fun cancel() {
        cancelled = true
        active = false
    }

    private fun readLoop(record: AudioRecord) {
        val maxFrames = (sampleRate * maxSeconds).toInt()
        val chunks = ArrayList<ShortArray>()
        val buf = ShortArray(sampleRate / 4) // 250 ms reads
        var frames = 0
        while (active && frames < maxFrames) {
            val read = record.read(buf, 0, buf.size)
            if (read <= 0) break
            chunks.add(buf.copyOf(read))
            frames += read
            onProgress?.invoke(minOf(1f, frames.toFloat() / maxFrames))
        }
        record.stop()
        record.release()
        onComplete?.invoke(if (cancelled || frames == 0) null else join(chunks, frames))
        thread = null
    }

    private fun join(chunks: List<ShortArray>, total: Int): ShortArray {
        val out = ShortArray(total)
        var pos = 0
        for (chunk in chunks) {
            System.arraycopy(chunk, 0, out, pos, chunk.size)
            pos += chunk.size
        }
        return out
    }
}
