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
import com.okdrummer.app.assets.SampleBank
import com.okdrummer.app.engine.DrumEngine
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val engine = DrumEngine()
    private lateinit var samples: Map<Pad, ShortArray>
    private val latency = mutableStateOf<DrumEngine.LatencyStat?>(null)
    private val overCount = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine.start(this)
        Log.i(TAG, "native sampleRate=${engine.sampleRate} framesPerBuffer=${engine.framesPerBuffer}")

        samples = SampleBank(assets).load(engine.sampleRate)
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
        setContent { Shell() }
    }

    override fun onDestroy() {
        engine.stop()
        super.onDestroy()
    }

    @Composable
    private fun Shell() {
        MaterialTheme {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f)) {
                    PadGrid()
                }
                LatencyLine()
            }
        }
    }

    @Composable
    private fun LatencyLine() {
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

    @Composable
    private fun PadGrid() {
        Column(Modifier.fillMaxSize()) {
            Pad.entries.chunked(COLUMNS).forEach { rowPads ->
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    rowPads.forEach { pad ->
                        val pcm = samples.getValue(pad)
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(2.dp)
                                .background(PadColors.surface)
                                .border(2.dp, PadColors.edge)
                                // Interim tap handling; replaced by the real touch layer in Phase 4.
                                .pointerInput(pcm) {
                                    awaitEachGesture {
                                        awaitFirstDown()
                                        engine.trigger(pcm)
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(pad.label, color = PadColors.text, fontSize = 18.sp)
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "OKDrummer"
        const val LATENCY_LIMIT_MS = 33f
        const val COLUMNS = 4
    }
}

private object PadColors {
    val surface = Color(0xFF23262C)
    val edge = Color(0xFF3A3E47)
    val text = Color(0xFFD7DAE0)
}
