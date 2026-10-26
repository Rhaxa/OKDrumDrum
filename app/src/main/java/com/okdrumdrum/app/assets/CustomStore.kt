package com.okdrumdrum.app.assets

import android.content.Context
import android.util.Log
import com.okdrumdrum.app.Bank
import com.okdrumdrum.app.Pad
import java.io.File

/**
 * Owns the per-(bank, pad) custom sounds in filesDir/custom/<bank>/ (PROJECT.md §2).
 * On first run it migrates v1.1 customs (filesDir/custom/) into Bank A so nothing
 * written by earlier versions is lost.
 */
class CustomStore(context: Context) {

    private val dir = File(context.filesDir, CUSTOM_DIR)
    private val migrated = File(context.filesDir, MIGRATED_FLAG)

    /** Custom sounds present on storage for [bank], keyed by pad. Main thread only. */
    fun load(bank: Bank, rate: Int): Map<Pad, ShortArray> {
        migrateV1(rate)
        val out = mutableMapOf<Pad, ShortArray>()
        for (pad in Pad.entries) {
            val file = file(bank, pad)
            if (!file.isFile) continue
            try {
                out[pad] = WavCodec.read(file.readBytes(), rate)
            } catch (e: Exception) {
                Log.w(TAG, "custom ${bank.name}/${pad.asset}: unreadable, ignoring: ${e.message}")
            }
        }
        return out
    }

    fun save(bank: Bank, pad: Pad, rate: Int, pcm: ShortArray) {
        file(bank, pad).parentFile!!.mkdirs()
        WavCodec.write(file(bank, pad), rate, pcm)
    }

    fun delete(bank: Bank, pad: Pad) {
        file(bank, pad).delete()
    }

    /** One-time: pre-1.2 customs sat directly in custom/ with no bank prefix. */
    private fun migrateV1(rate: Int) {
        if (migrated.isFile) return
        migrated.parentFile!!.mkdirs()
        val legacy = dir
        if (legacy.isDirectory) {
            val target = dir.resolve(Bank.A.name)
            target.mkdirs()
            for (pad in Pad.entries) {
                val file = File(legacy, "${pad.asset}.wav")
                if (file.isFile && !target.resolve("${pad.asset}.wav").exists()) {
                    file.renameTo(target.resolve("${pad.asset}.wav"))
                    Log.i(TAG, "migrated v1 custom into Bank A: ${pad.asset}")
                }
            }
        }
        migrated.createNewFile()
    }

    private fun file(bank: Bank, pad: Pad) = dir.resolve(bank.name).resolve("${pad.asset}.wav")

    private companion object {
        const val TAG = "OKDrumDrum"
        const val CUSTOM_DIR = "custom"
        const val MIGRATED_FLAG = "custom/.migrated_v1"
    }
}
