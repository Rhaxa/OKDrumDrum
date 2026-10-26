package com.okdrumdrum.app

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.okdrumdrum.app.assets.CustomStore
import com.okdrumdrum.app.assets.PadSettings
import com.okdrumdrum.app.assets.SampleBank
import com.okdrumdrum.app.engine.DrumEngine
import com.okdrumdrum.app.engine.MicRecorder
import com.okdrumdrum.app.ui.PadSheet
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val engine = DrumEngine()
    private lateinit var settings: PadSettings
    private lateinit var customStore: CustomStore
    private lateinit var defaults: Map<Pad, ShortArray>

    // Reactive state owned here; UI and gestures read straight from it.
    private val samples = mutableStateOf<Map<Pad, ShortArray>>(emptyMap())
    private val customPads = mutableStateMapOf<Pad, Boolean>()
    private val volumes = mutableStateMapOf<Pad, Float>()
    private val sheetPad = mutableStateOf<Pad?>(null)
    private val recordingPad = mutableStateOf<Pad?>(null)
    private val recordProgress = mutableStateOf(0f)
    private val permissionDenied = mutableStateOf(false)
    private val showAdvice = mutableStateOf(false)

    private var recorder: MicRecorder? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) beginRecording() else permissionDenied.value = true
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine.start(this)
        Log.i(TAG, "native sampleRate=${engine.sampleRate} framesPerBuffer=${engine.framesPerBuffer}")

        settings = PadSettings(this)
        customStore = CustomStore(this)
        defaults = SampleBank(assets).load(engine.sampleRate)
        val customs = customStore.load(engine.sampleRate)
        for (pad in Pad.entries) {
            volumes[pad] = settings.volume(pad)
            customPads[pad] = customs.containsKey(pad)
        }
        samples.value = Pad.entries.associateWith { pad -> customs[pad] ?: defaults.getValue(pad) }
        showAdvice.value = !settings.adviceSeen()

        engine.onLatency = { stat ->
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
        recorder?.cancel()
        recorder = null
        engine.stop()
        super.onDestroy()
    }

    @Composable
    private fun Shell() {
        MaterialTheme {
            PadGrid()
            AdviceDialog()
            sheetPad.value?.let { pad ->
                // Full-screen panel; covers the grid entirely, so pads can't be hit underneath.
                PadSheet(
                    pad = pad,
                    isRecording = recordingPad.value != null,
                    progress = recordProgress.value,
                    hasCustom = customPads[pad] == true,
                    volume = volumes[pad] ?: 1f,
                    permissionDenied = permissionDenied.value,
                    onRecordRequest = {
                        permissionDenied.value = false
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onStopRecording = { recorder?.stop() },
                    onCancelRecording = { recorder?.cancel() },
                    onRevert = { revert(pad) },
                    onVolumeChange = { value, commit -> setVolume(pad, value, commit) },
                    onDismiss = { sheetPad.value = null },
                )
            }
        }
    }

    @Composable
    private fun AdviceDialog() {
        if (!showAdvice.value) return
        AlertDialog(
            onDismissRequest = { showAdvice.value = false },
            title = { Text("Heads up for best feel") },
            text = {
                Text(
                    "Use the phone speaker or wired headphones — Bluetooth adds huge latency. " +
                        "Battery-saver can disable the fast audio path too. With a song playing in your " +
                        "media player, both keep playing together fine."
                )
            },
            confirmButton = {
                TextButton(onClick = { showAdvice.value = false }) { Text("Got it") }
            },
            dismissButton = {
                TextButton(onClick = { settings.dismissAdvice(); showAdvice.value = false }) {
                    Text("Don't show again")
                }
            },
        )
    }

    @Composable
    private fun PadGrid() {
        Column(Modifier.fillMaxSize()) {
            Pad.entries.chunked(COLUMNS).forEach { rowPads ->
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    rowPads.forEach { pad ->
                        PadCell(pad, samples.value.getValue(pad), customPads[pad] == true, Modifier.weight(1f))
                    }
                }
            }
        }
    }

    /** Owns one pad's full press lifecycle: trigger on touch-down, sheet on long-press. */
    @Composable
    private fun PadCell(pad: Pad, pcm: ShortArray, hasCustom: Boolean, modifier: Modifier) {
        val pressed = remember { mutableStateOf(false) }
        Box(
            modifier
                .fillMaxHeight()
                .padding(2.dp)
                .background(if (pressed.value) PadColors.pressedSurface else PadColors.surface)
                .border(2.dp, if (pressed.value) PadColors.pressedEdge else PadColors.edge)
                .pointerInput(pcm) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        pressed.value = true
                        engine.trigger(pcm, volumes[pad] ?: 1f)
                        val longPress = awaitLongPressOrCancellation(down.id)
                        if (longPress != null) {
                            tick()
                            sheetPad.value = pad
                        }
                        pressed.value = false
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(pad.label, color = if (pressed.value) PadColors.pressedText else PadColors.text, fontSize = 18.sp)
            if (hasCustom) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(10.dp)
                        .clip(RectangleShape)
                        .background(PadColors.pressedEdge)
                )
            }
        }
    }

    // ---- Sheet actions (main thread) ----

    /** Short haptic tick via direct Vibrator — bypasses the system touch-feedback setting. */
    private fun tick() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private fun setVolume(pad: Pad, value: Float, commit: Boolean) {
        val clamped = value.coerceIn(0f, 1f)
        volumes[pad] = clamped
        if (commit) settings.setVolume(pad, clamped)
    }

    private fun beginRecording() {
        val pad = sheetPad.value ?: return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            permissionDenied.value = true
            return
        }
        recordingPad.value = pad
        recordProgress.value = 0f
        recorder = MicRecorder(engine.sampleRate).also { r ->
            r.onProgress = { fraction -> recordProgress.value = fraction }
            r.onComplete = { pcm -> onRecordDone(pad, pcm) }
            r.start()
        }
    }

    private fun onRecordDone(pad: Pad, pcm: ShortArray?) {
        recorder = null
        recordingPad.value = null
        if (pcm == null) {
            sheetPad.value = null // cancelled or empty; keep the pad as-is
            return
        }
        customStore.save(pad, engine.sampleRate, pcm)
        customPads[pad] = true
        samples.value = samples.value + (pad to pcm)
        sheetPad.value = null
        engine.trigger(pcm, volumes[pad] ?: 1f) // audible confirmation of the fresh hit
    }

    private fun revert(pad: Pad) {
        customStore.delete(pad)
        customPads[pad] = false
        samples.value = samples.value + (pad to defaults.getValue(pad))
        sheetPad.value = null
        engine.trigger(defaults.getValue(pad), volumes[pad] ?: 1f) // audible confirmation
    }

    private companion object {
        const val TAG = "OKDrumDrum"
        const val COLUMNS = 4
    }
}

private object PadColors {
    val surface = Color(0xFF23262C)
    val edge = Color(0xFF3A3E47)
    val text = Color(0xFFD7DAE0)
    val pressedSurface = Color(0xFF39414E)
    val pressedEdge = Color(0xFF7EA6FF)
    val pressedText = Color(0xFFF2F4F8)
}
