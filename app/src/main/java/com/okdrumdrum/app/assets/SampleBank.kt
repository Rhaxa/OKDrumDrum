package com.okdrumdrum.app.assets

import android.content.res.AssetManager
import android.util.Log
import com.okdrumdrum.app.Bank
import com.okdrumdrum.app.Pad
import kotlin.math.abs

/**
 * Loads the default one-shots from assets (PROJECT.md §2). Assets ship pre-baked at both
 * common device native rates × 4 bank renditions; the rate variant nearest the device
 * rate is used for every bank at start, so no runtime resampling and no mixing-thread I/O.
 */
class SampleBank(private val assets: AssetManager) {

    fun load(nativeRate: Int): Map<Bank, Map<Pad, ShortArray>> {
        val rate = ASSET_RATES.minByOrNull { abs(it - nativeRate) } ?: ASSET_RATES.first()
        if (rate != nativeRate) {
            Log.w(TAG, "no assets at native $nativeRate Hz; using $rate Hz variant (slight pitch shift)")
        }
        return Bank.entries.associateWith { bank ->
            Pad.entries.associateWith { pad ->
                WavCodec.read(readAsset("sounds/$rate/${bank.name}/${pad.asset}.wav"), rate)
            }
        }
    }

    private fun readAsset(path: String): ByteArray = assets.open(path).use { stream -> stream.readBytes() }

    private companion object {
        const val TAG = "OKDrumDrum"
        val ASSET_RATES = intArrayOf(48_000, 44_100)
    }
}
