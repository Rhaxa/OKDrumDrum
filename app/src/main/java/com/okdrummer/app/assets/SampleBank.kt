package com.okdrummer.app.assets

import android.content.res.AssetManager
import android.util.Log
import com.okdrummer.app.Pad
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Loads the default one-shots from assets (PROJECT.md §2). Assets ship pre-baked at both
 * common device native rates; the variant nearest the device rate is used, so no runtime
 * resampling and no work ever happens on the mixer thread.
 */
class SampleBank(private val assets: AssetManager) {

    fun load(nativeRate: Int): Map<Pad, ShortArray> {
        val rate = ASSET_RATES.minByOrNull { abs(it - nativeRate) } ?: ASSET_RATES.first()
        if (rate != nativeRate) {
            Log.w(TAG, "no assets at native $nativeRate Hz; using $rate Hz variant (slight pitch shift)")
        }
        val dir = "sounds/$rate"
        return Pad.entries.associateWith { pad -> readPcmWav("$dir/${pad.asset}.wav", rate) }
    }

    /** Minimal PCM-16 mono WAV reader: walks chunks, validates the format we ship. */
    private fun readPcmWav(path: String, expectedRate: Int): ShortArray {
        val bytes = assets.open(path).use { stream -> stream.readBytes() }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        require(
            bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()
        ) { "$path: not RIFF" }

        var format = 0
        var channels = 0
        var rate = 0
        var bits = 0
        var dataStart = -1
        var dataSize = 0
        var pos = 12 // RIFF(4) + size(4) + WAVE(4)

        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = bb.getInt(pos + 4)
            when (id) {
                "fmt " -> {
                    format = bb.getShort(pos + 8).toInt()
                    channels = bb.getShort(pos + 10).toInt()
                    rate = bb.getInt(pos + 12)
                    bits = bb.getShort(pos + 22).toInt()
                }
                "data" -> {
                    dataStart = pos + 8
                    dataSize = minOf(size, bytes.size - dataStart)
                }
            }
            pos += 8 + size + (size and 1) // chunks are word-aligned
        }

        require(format == 1) { "$path: not PCM ($format)" }
        require(channels == 1) { "$path: not mono ($channels)" }
        require(bits == 16) { "$path: not 16-bit ($bits)" }
        require(rate == expectedRate) { "$path: rate $rate != expected $expectedRate" }
        require(dataStart >= 0) { "$path: no data chunk" }

        bb.position(dataStart)
        val shorts = ShortArray(dataSize / 2)
        bb.asShortBuffer().get(shorts)
        return shorts
    }

    private companion object {
        const val TAG = "OKDrummer"
        val ASSET_RATES = intArrayOf(48_000, 44_100)
    }
}
