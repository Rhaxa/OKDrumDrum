package com.okdrummer.app

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.okdrummer.app.engine.DrumEngine
import java.util.Locale
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

class MainActivity : ComponentActivity() {

    private val engine = DrumEngine()
    private val latency = mutableStateOf<DrumEngine.LatencyStat?>(null)
    private val overCount = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine.start(this)
        Log.i(TAG, "native sampleRate=${engine.sampleRate} framesPerBuffer=${engine.framesPerBuffer}")

        val tone = synthTestTone(engine.sampleRate)
        engine.onLatency = { stat ->
            latency.value = stat
            if (stat.totalMs > LATENCY_LIMIT_MS) overCount.intValue++
            Log.i(
                TAG,
                String.format(
                    Locale.US, "trigger total=%.1f ms (app %.1f + output %.1f)", stat.totalMs, stat.appMs, stat.outputMs
                )
            )
        }
        setContent { DebugShell(tone) }
    }

    override fun onDestroy() {
        engine.stop()
        super.onDestroy()
    }

    /** TEMPORARY Phase 2 debug tone — replaced by real samples in Phase 3 (PROJECT.md §5). */
    private fun synthTestTone(sr: Int): ShortArray {
        val n = (sr * SECONDS).toInt()
        val fade = (sr * FADE_SECONDS).toInt()
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            var env = exp(-t * 18.0)
            if (i < fade) env *= i.toDouble() / fade
            if (i > n - fade) env *= (n - i).toDouble() / fade
            out[i] = (AMPLITUDE * env * sin(2 * PI * FREQUENCY * t) * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    @Composable
    private fun DebugShell(tone: ShortArray) {
        MaterialTheme {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f)) {
                    PadGrid(tone)
                }
                val stat = latency.value
                val summary = if (stat == null) "Latency: tap any pad" else String.format(
                    Locale.US, "Latency: %.1f ms (app %.1f + output %.1f)  >33ms: %d",
                    stat.totalMs, stat.appMs, stat.outputMs, overCount.intValue
                )
                val good = stat == null || stat.totalMs <= LATENCY_LIMIT_MS
                Text(
                    summary,
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF14161A))
                        .padding(8.dp),
                    color = if (good) Color(0xFF9BE49B) else Color(0xFFFF6B6B),
                    fontSize = 14.sp,
                )
            }
        }
    }

    @Composable
    private fun PadGrid(tone: ShortArray) {
        Column(Modifier.fillMaxSize()) {
            for (row in 0 until 2) {
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    for (col in 0 until 4) {
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(2.dp)
                                .background(PadColors.surface)
                                .border(2.dp, PadColors.edge)
                                // Interim tap handling; replaced by the real touch layer in Phase 4.
                                .pointerInput(tone) {
                                    awaitEachGesture {
                                        awaitFirstDown()
                                        engine.trigger(tone)
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(PAD_LABELS[row * 4 + col], color = PadColors.text, fontSize = 18.sp)
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "OKDrummer"
        const val LATENCY_LIMIT_MS = 33f
        const val FREQUENCY = 880.0
        const val SECONDS = 0.18
        const val FADE_SECONDS = 0.005
        const val AMPLITUDE = 0.75
    }
}

/** Pads in spec order: top row left→right, then bottom row left→right (PROJECT.md §1). */
val PAD_LABELS = listOf(
    "Low tom", "Mid tom", "High tom", "Cymbal",
    "Kick", "Snare", "Closed hat", "Open hat",
)

private object PadColors {
    val surface = Color(0xFF23262C)
    val edge = Color(0xFF3A3E47)
    val text = Color(0xFFD7DAE0)
}
