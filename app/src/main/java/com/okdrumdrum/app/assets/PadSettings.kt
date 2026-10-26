package com.okdrumdrum.app.assets

import android.content.Context
import com.okdrumdrum.app.Bank
import com.okdrumdrum.app.Pad

/**
 * Persisted per-(bank, pad) settings: volume (0..1), the first-launch advisory flag,
 * and the active bank. Call [migrateV1] once at startup: v1.1 flat `vol_<PAD>` keys
 * move to `vol_A_<PAD>` (those settings belong to Bank A after the rename-era data).
 */
class PadSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun activeBank(): Bank =
        Bank.entries.firstOrNull { it.name == prefs.getString(ACTIVE_BANK, null) } ?: Bank.A

    fun setActiveBank(bank: Bank) {
        prefs.edit().putString(ACTIVE_BANK, bank.name).apply()
    }

    fun volume(bank: Bank, pad: Pad): Float = prefs.getFloat("vol_${bank.name}_${pad.name}", 1f)

    fun setVolume(bank: Bank, pad: Pad, value: Float) {
        prefs.edit().putFloat("vol_${bank.name}_${pad.name}", value).apply()
    }

    fun adviceSeen(): Boolean = prefs.getBoolean(ADVICE_SEEN, false)

    fun dismissAdvice() {
        prefs.edit().putBoolean(ADVICE_SEEN, true).apply()
    }

    /** One-time: v1 stored volumes without a bank prefix; they belong to Bank A. */
    fun migrateV1() {
        if (prefs.getBoolean(V1_MIGRATED, false)) return
        val edit = prefs.edit()
        for (pad in Pad.entries) {
            val legacy = prefs.getFloat("vol_${pad.name}", Float.NaN)
            if (!legacy.isNaN() && !prefs.contains("vol_A_${pad.name}")) {
                edit.putFloat("vol_A_${pad.name}", legacy)
            }
            edit.remove("vol_${pad.name}")
        }
        edit.putBoolean(V1_MIGRATED, true).apply()
    }

    private companion object {
        const val PREFS = "okdrummer"
        const val ADVICE_SEEN = "advice_seen"
        const val ACTIVE_BANK = "active_bank"
        const val V1_MIGRATED = "vol_v1_migrated"
    }
}
