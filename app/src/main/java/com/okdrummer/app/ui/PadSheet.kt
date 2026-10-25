package com.okdrummer.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.okdrummer.app.Pad

/**
 * Long-press sheet for one pad (PROJECT.md §1): Record new sound / Volume / Revert-if-custom,
 * or the recording view while capture is running.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PadSheet(
    pad: Pad,
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
    ModalBottomSheet(onDismissRequest = onDismiss) {
        if (isRecording) {
            Column(Modifier.fillMaxWidth().padding(16.dp).padding(bottom = 32.dp)) {
                Text("Recording ${pad.label} — max 10 s", fontSize = 18.sp)
                LinearProgressIndicator(
                    progress = { progress },
                    Modifier.fillMaxWidth().padding(vertical = 16.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onCancelRecording) { Text("Cancel") }
                    Button(
                        onClick = onStopRecording,
                        Modifier.padding(start = 12.dp).fillMaxWidth(),
                    ) { Text("Stop & use") }
                }
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(16.dp).padding(bottom = 32.dp)) {
                Text(pad.label, fontSize = 22.sp)
                Text(
                    if (hasCustom) "Custom sound loaded" else "Default sound",
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 2.dp, bottom = 16.dp),
                )
                Button(onClick = onRecordRequest, Modifier.fillMaxWidth()) {
                    Text("Record new sound")
                }
                if (permissionDenied) {
                    Text(
                        "Microphone permission denied — allow it in system settings to record.",
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (hasCustom) {
                    OutlinedButton(onClick = onRevert, Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        Text("Revert to default")
                    }
                }
                Text(
                    "Volume ${percent(volume)}",
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
                Slider(
                    value = volume.coerceIn(0f, 1f),
                    onValueChange = { onVolumeChange(it, false) },
                    onValueChangeFinished = { onVolumeChange(volume.coerceIn(0f, 1f), true) },
                )
            }
        }
    }
}

private fun percent(v: Float) = "%.0f%%".format(java.util.Locale.US, v.coerceIn(0f, 1f) * 100)
