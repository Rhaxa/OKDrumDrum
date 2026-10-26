package com.okdrumdrum.app.assets

import android.content.Context
import com.okdrumdrum.app.Pad

/** Persisted per-pad settings: volume (0..1) and the first-launch advisory flag. */
class PadSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun volume(pad: Pad): Float = prefs.getFloat("vol_${pad.name}", 1f)

    fun setVolume(pad: Pad, value: Float) {
        prefs.edit().putFloat("vol_${pad.name}", value).apply()
    }

    fun adviceSeen(): Boolean = prefs.getBoolean(ADVICE_SEEN, false)

    fun dismissAdvice() {
        prefs.edit().putBoolean(ADVICE_SEEN, true).apply()
    }

    private companion object {
        const val PREFS = "okdrummer"
        const val ADVICE_SEEN = "advice_seen"
    }
}
