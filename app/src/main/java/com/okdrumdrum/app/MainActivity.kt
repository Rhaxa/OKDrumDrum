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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.okdrumdrum.app.assets.CustomStore
import com.okdrumdrum.app.assets.PadSettings
import com.okdrumdrum.app.assets.SampleBank
import com.okdrumdrum.app.engine.DrumEngine
import com.okdrumdrum.app.engine.MicRecorder
import com.okdrumdrum.app.ui.PadSheet

class MainActivity : ComponentActivity() {

    private val engine = DrumEngine()
    private lateinit var settings: PadSettings
    private lateinit var customStore: CustomStore

    // Reactive state owned here; UI and gestures read straight from it.
    private val bankDefaults = mutableStateOf<Map<Bank, Map<Pad, ShortArray>>>(emptyMap())
    private val customs = mutableStateMapOf<Bank, Map<Pad, ShortArray>>()
    private val volumes = mutableStateMapOf<Bank, MutableMap<Pad, Float>>()
    private val activeBank = mutableStateOf(Bank.A)
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
        Log.i(TAG, "native sampleRate=${engine.sampleRate} framesPerCallback=${engine.framesPerCallback}")

        settings = PadSettings(this)
        settings.migrateV1()
        customStore = CustomStore(this)
        bankDefaults.value = SampleBank(assets).load(engine.sampleRate)
        activeBank.value = settings.activeBank()
        for (bank in Bank.entries) {
            val customsInBank = customStore.load(bank, engine.sampleRate)
            if (customsInBank.isNotEmpty()) customs[bank] = customsInBank
            volumes[bank] = Pad.entries.associateWith { pad -> settings.volume(bank, pad) }.toMutableMap()
        }
        showAdvice.value = !settings.adviceSeen()

        engine.onLatency = { stat ->
            Log.i(
                TAG,
                String.format(
                    java.util.Locale.US, "trigger total=%.1f ms (app %.1f + output %.1f)",
                    stat.totalMs, stat.appMs, stat.outputMs
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

    // ---- helpers over (bank, pad) state ----

    private fun sampleOf(bank: Bank, pad: Pad): ShortArray =
        customs[bank]?.get(pad) ?: bankDefaults.value.getValue(bank).getValue(pad)

    private fun hasCustom(bank: Bank, pad: Pad): Boolean = customs[bank]?.containsKey(pad) == true

    // ---- composables ----

    @Composable
    private fun Shell() {
        MaterialTheme {
            Column(Modifier.fillMaxSize()) {
                TopBar()
                PadGrid(Modifier.weight(1f))
            }
            AdviceDialog()
            sheetPad.value?.let { pad ->
                // Full-screen panel; covers the grid entirely, so pads can't be hit underneath.
                PadSheet(
                    pad = pad,
                    accent = Color(activeBank.value.accent),
                    isRecording = recordingPad.value != null,
                    progress = recordProgress.value,
                    hasCustom = hasCustom(activeBank.value, pad),
                    volume = volumes[activeBank.value]?.get(pad) ?: 1f,
                    permissionDenied = permissionDenied.value,
                    onRecordRequest = {
                        permissionDenied.value = false
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onStopRecording = { recorder?.stop() },
                    onCancelRecording = { recorder?.cancel() },
                    onRevert = { revert(activeBank.value, pad) },
                    onVolumeChange = { value, commit -> setVolume(activeBank.value, pad, value, commit) },
                    onDismiss = { sheetPad.value = null },
                )
            }
        }
    }

    /** Central branded pill flanked by the two bank button groups (PROJECT.md §1). */
    @Composable
    private fun TopBar() {
        // Same black as the pill fill (user asset) so the logo sits on its own color.
        Row(
            Modifier
                .fillMaxWidth()
                .background(Colors.pillFill)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BankButtons(Modifier.weight(1.3f))
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Pill("OKDRUMDRUM")
            }
            BankButtons(Modifier.weight(1.3f))
        }
    }

    @Composable
    private fun BankButtons(modifier: Modifier) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Bank.entries.forEach { bank ->
                val accent = Color(bank.accent)
                Box(
                    Modifier
                        .weight(1f)
                        .height(BankButtonHeight)
                        .drawBehind { drawBankFace(accent) }
                        .clickable { selectBank(bank) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(bank.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    @Composable
    private fun Pill(text: String) {
        Box(
            Modifier
                .background(Colors.pillFill)
                .border(1.dp, Colors.pillStroke, RoundedCornerShape(50))
                .padding(horizontal = 18.dp, vertical = 8.dp)
        ) {
            Text(
                text,
                color = Colors.pillStroke,
                fontSize = 14.sp,
                letterSpacing = 3.sp,
                fontWeight = FontWeight.Bold,
            )
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
    private fun PadGrid(modifier: Modifier) {
        Column(modifier) {
            Pad.entries.chunked(COLUMNS).forEach { rowPads ->
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    rowPads.forEach { pad ->
                        PadCell(pad, Modifier.weight(1f))
                    }
                }
            }
        }
    }

    /** Owns one pad's full press lifecycle: trigger on touch-down, sheet on long-press. */
    @Composable
    private fun PadCell(pad: Pad, modifier: Modifier) {
        val pressed = remember { mutableStateOf(false) }
        val bank = activeBank.value
        // Bank switch feedback: short cross-fade of the hairline/lip color, no layout shift.
        val accent by animateColorAsState(
            targetValue = Color(bank.accent),
            animationSpec = tween(BANK_FADE_MS),
            label = "padAccent",
        )
        Box(
            modifier
                .fillMaxHeight()
                .padding(3.dp)
                .drawBehind { drawPadFace(accent, pressed.value) }
                .pointerInput(bank, pad) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        pressed.value = true
                        engine.trigger(sampleOf(bank, pad), volumes[bank]?.get(pad) ?: 1f)
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
            Text(pad.label, color = Colors.padText, fontSize = 14.sp)
            if (hasCustom(bank, pad)) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(10.dp)
                        .clip(RectangleShape)
                        .background(accent)
                )
            }
        }
    }

    // ---- actions (main thread) ----

    private fun selectBank(bank: Bank) {
        if (activeBank.value == bank) return
        activeBank.value = bank
        settings.setActiveBank(bank)
    }

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

    private fun setVolume(bank: Bank, pad: Pad, value: Float, commit: Boolean) {
        val clamped = value.coerceIn(0f, 1f)
        // Structural write on the tracked outer map — mutating a plain inner map
        // would not recompose the slider (v1.2 regression guard).
        volumes[bank] = volumes[bank].orEmpty().toMutableMap().apply { put(pad, clamped) }
        if (commit) settings.setVolume(bank, pad, clamped)
    }

    private fun beginRecording() {
        val pad = sheetPad.value ?: return
        val bank = activeBank.value
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            permissionDenied.value = true
            return
        }
        recordingPad.value = pad
        recordProgress.value = 0f
        recorder = MicRecorder(engine.sampleRate).also { r ->
            r.onProgress = { fraction -> recordProgress.value = fraction }
            r.onComplete = { pcm -> onRecordDone(bank, pad, pcm) }
            r.start()
        }
    }

    private fun onRecordDone(bank: Bank, pad: Pad, pcm: ShortArray?) {
        recorder = null
        recordingPad.value = null
        if (pcm == null) {
            sheetPad.value = null // cancelled or empty; keep the pad as-is
            return
        }
        customStore.save(bank, pad, engine.sampleRate, pcm)
        customs[bank] = (customs[bank] ?: emptyMap()) + (pad to pcm)
        sheetPad.value = null
        engine.trigger(pcm, volumes[bank]?.get(pad) ?: 1f) // audible confirmation of the fresh hit
    }

    private fun revert(bank: Bank, pad: Pad) {
        customStore.delete(bank, pad)
        if (customs[bank]?.containsKey(pad) == true) {
            customs[bank] = (customs[bank] ?: emptyMap()) - pad
        }
        sheetPad.value = null
        engine.trigger(sampleOf(bank, pad), volumes[bank]?.get(pad) ?: 1f) // audible confirmation
    }

    private companion object {
        const val TAG = "OKDrumDrum"
        const val COLUMNS = 4
        const val BANK_FADE_MS = 160
        val BankButtonHeight = 40.dp
    }
}

private object Colors {
    val background = Color(0xFF14161A)
    val text = Color(0xFFD7DAE0)
    val padText = Color(0x4DFFFFFF) // low-contrast tiny label
    val pillFill = Color(0xFF000000)
    val pillStroke = Color(0xFFFFFFFF)
}

/**
 * Geometry lifted from the user's SVG assets (PROJECT.md §1):
 *  - pad:          224×166, corner r=8, 3-unit accent bottom lip, 1-unit hairline stroke
 *                  around a textured face (7×6 dot tile: base #222222, dot #282828, dots
 *                  r=1 at tile positions (1,1) and (5,4)).
 *  - bank button:  64×64 square, corner r=8, 4-unit accent lip, same face untextured.
 * Accent uses the registered bank colors (Bank.accent), replacing the asset's blue.
 * SVG <pattern> has no Compose equivalent, so the texture is painted directly in
 * drawBehind at the asset's exact coordinates — resolution-independent, no bitmaps.
 */
private object Studio {
    const val PAD_W = 224f
    const val PAD_H = 166f
    const val BTN = 64f
    val faceBase = Color(0xFF222222)
    val faceDot = Color(0xFF282828)
}

/** Pad face: accent plate visible only as the bottom lip, textured dark face, hairline. */
private fun DrawScope.drawPadFace(accent: Color, pressed: Boolean) {
    val s = size.width / Studio.PAD_W // design→device-px scale
    val corner = CornerRadius(8f * s)
    val lip = 3f * s
    val hair = 1f * s
    drawRoundRect(color = accent, cornerRadius = corner)
    drawRoundRect(
        color = Studio.faceBase,
        size = Size(size.width, size.height - lip),
        cornerRadius = corner,
    )
    // Dot texture, tiled in design space (7×6), clipped strictly inside the face's
    // rounded outline so no dot ever paints past the pad's edge.
    val faceBottom = size.height - lip
    val facePath = Path().apply {
        addRoundRect(RoundRect(0f, 0f, size.width, faceBottom, CornerRadius(8f * s)))
    }
    clipPath(facePath) {
        var ty = 0f
        while (ty + 2f * s <= faceBottom) {
            var tx = 0f
            while (tx + 2f * s <= size.width) {
                drawCircle(Studio.faceDot, radius = s, center = Offset(tx + 1f * s, ty + 1f * s))
                drawCircle(Studio.faceDot, radius = s, center = Offset(tx + 5f * s, ty + 4f * s))
                tx += 7f * s
            }
            ty += 6f * s
        }
    }
    drawRoundRect(
        color = accent,
        topLeft = Offset(hair / 2, hair / 2),
        size = Size(size.width - hair, size.height - lip - hair),
        cornerRadius = CornerRadius(8f * s - hair / 2),
        style = Stroke(width = hair),
    )
    if (pressed) {
        drawRoundRect(color = accent.copy(alpha = 0.15f), cornerRadius = corner)
    }
}

/** Bank button face: same recipe, untextured, 4-unit lip; proportions from the 64×64 asset. */
private fun DrawScope.drawBankFace(accent: Color) {
    val s = size.width / Studio.BTN
    val corner = CornerRadius(8f * s)
    val lip = 4f * s
    val hair = 1f * s
    drawRoundRect(color = accent, cornerRadius = corner)
    drawRoundRect(
        color = Studio.faceBase,
        size = Size(size.width, size.height - lip),
        cornerRadius = corner,
    )
    drawRoundRect(
        color = accent,
        topLeft = Offset(hair / 2, hair / 2),
        size = Size(size.width - hair, size.height - lip - hair),
        cornerRadius = CornerRadius(8f * s - hair / 2),
        style = Stroke(width = hair),
    )
}

