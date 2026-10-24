package com.okdrummer.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PadGrid() }
    }
}

/** Pads in spec order: top row left→right, then bottom row left→right (PROJECT.md §1). */
private val PAD_LABELS = listOf(
    "Low tom", "Mid tom", "High tom", "Cymbal",
    "Kick", "Snare", "Closed hat", "Open hat",
)

@Composable
private fun PadGrid() {
    MaterialTheme {
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
                                .border(2.dp, PadColors.edge),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(PAD_LABELS[row * 4 + col], color = PadColors.text, fontSize = 18.sp)
                        }
                    }
                }
            }
        }
    }
}

private object PadColors {
    val surface = Color(0xFF23262C)
    val edge = Color(0xFF3A3E47)
    val text = Color(0xFFD7DAE0)
}
