package com.okdrummer.app.assets

import android.content.Context
import android.util.Log
import com.okdrummer.app.Pad
import java.io.File

/** Owns the per-pad custom sounds in filesDir/custom/ (PROJECT.md §2). */
class CustomStore(context: Context) {

    private val dir = File(context.filesDir, "custom")

    /** Custom sounds that exist on storage, keyed by pad. Main thread only. */
    fun load(rate: Int): Map<Pad, ShortArray> {
        val out = mutableMapOf<Pad, ShortArray>()
        for (pad in Pad.entries) {
            val file = file(pad)
            if (!file.isFile) continue
            try {
                out[pad] = WavCodec.read(file.readBytes(), rate)
            } catch (e: Exception) {
                Log.w(TAG, "custom ${pad.asset}: unreadable, ignoring: ${e.message}")
            }
        }
        return out
    }

    fun save(pad: Pad, rate: Int, pcm: ShortArray) {
        dir.mkdirs()
        WavCodec.write(file(pad), rate, pcm)
    }

    fun delete(pad: Pad) {
        file(pad).delete()
    }

    private fun file(pad: Pad) = File(dir, "${pad.asset}.wav")

    private companion object {
        const val TAG = "OKDrummer"
    }
}
