package com.okdrumdrum.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.okdrumdrum.app.Pad

private object PanelColors {
    val background = Color(0xFF16181D)
    val text = Color(0xFFD7DAE0)
    val subText = Color(0xFF8A90A0)
}

/**
 * Full-screen options panel for one pad (PROJECT.md §1): Record new sound / Volume /
 * Revert-if-custom, themed with the ACTIVE BANK accent so the recording target bank is
 * always obvious. All controls visible at once — no scrolling. Replaced a bottom sheet.
 */
@Composable
fun PadSheet(
    pad: Pad,
    accent: Color,
    isRecording: Boolean,
    progress: Float,
    hasCustom: Boolean,
    volume: Float,
    permissionDenied: Boolean,
    onRecordRequest: () -> Unit,
    onStopRecording: () -> Unit,
    onCancelRecording: () -> Unit,
    onRevert: () -> Unit,
    onVolumeChange: (Float, commit: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(PanelColors.background)
            .padding(24.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(pad.label, color = accent, fontSize = 26.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Text(
                    if (hasCustom) "Custom sound loaded" else "Default sound",
                    color = PanelColors.subText,
                    fontSize = 14.sp,
                )
            }
            TextButton(onClick = onDismiss) { Text("Done", color = PanelColors.text, fontSize = 16.sp) }
        }
        Box(Modifier.fillMaxWidth().padding(top = 12.dp).height(4.dp).background(accent))

        if (isRecording) {
            Spacer(Modifier.weight(1f))
            Text("Recording — max 10 s", color = PanelColors.text, fontSize = 20.sp)
            LinearProgressIndicator(
                progress = { progress },
                Modifier.fillMaxWidth().padding(vertical = 24.dp),
                color = accent,
                trackColor = PanelColors.subText.copy(alpha = 0.3f),
            )
            Row {
                OutlinedButton(onClick = onCancelRecording, Modifier.weight(1f)) { Text("Cancel") }
                Spacer(Modifier.padding(8.dp))
                Button(
                    onClick = onStopRecording,
                    Modifier.weight(2f),
                    colors = ButtonDefaults.buttonColors(containerColor = accent),
                ) { Text("Stop & use") }
            }
            Spacer(Modifier.weight(1f))
        } else {
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onRecordRequest,
                Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = accent),
            ) {
                Text("Record new sound", fontSize = 18.sp)
            }
            if (permissionDenied) {
                Text(
                    "Microphone permission denied — allow it in system settings to record.",
                    color = Color(0xFFFF6B6B),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (hasCustom) {
                OutlinedButton(onClick = onRevert, Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    Text("Revert to default", fontSize = 18.sp)
                }
            }
            Text(
                "Volume ${percent(volume)}",
                color = PanelColors.text,
                fontSize = 16.sp,
                modifier = Modifier.padding(top = 24.dp, bottom = 4.dp),
            )
            Slider(
                value = volume.coerceIn(0f, 1f),
                onValueChange = { onVolumeChange(it, false) },
                onValueChangeFinished = { onVolumeChange(volume.coerceIn(0f, 1f), true) },
                colors = SliderDefaults.colors(
                    thumbColor = accent,
                    activeTrackColor = accent,
                ),
            )
            Spacer(Modifier.weight(1f))
        }
    }
}

private fun percent(v: Float) = "%.0f%%".format(java.util.Locale.US, v.coerceIn(0f, 1f) * 100)
