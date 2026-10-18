package com.nova.assistant.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class OrbState { Idle, Listening, Thinking, Speaking }

/** The living orb: breathes when idle, ripples with your voice, spins while thinking. */
@Composable
fun Orb(state: OrbState, level: Float, modifier: Modifier = Modifier) {
    var t by remember { mutableFloatStateOf(0f) }
    val k = remember { FloatArray(2) } // [smooth level, energy] - drawing state, not UI state
    val seeds = remember { List(120) { i -> Triple(i / 120f * 2f * PI.toFloat(), (i * 7919 % 628) / 100f, 0.5f + (i * 31 % 100) / 100f) } }

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceAtMost(0.05f)
                last = now
                t += dt
            }
        }
    }

    Canvas(modifier) {
        val target = when (state) {
            OrbState.Listening -> level
            OrbState.Speaking -> 0.35f + 0.25f * sin(t * 9f) * sin(t * 3.3f)
            OrbState.Thinking -> 0.25f
            OrbState.Idle -> 0f
        }
        k[0] += (target - k[0]) * 0.18f
        k[1] += ((if (state == OrbState.Idle) 0f else 1f) - k[1]) * 0.05f
        val smooth = k[0]
        val energy = k[1]

        val c = Nova.Pearl
        val cx = size.width / 2
        val cy = size.height / 2
        val r = size.minDimension * (0.26f + 0.012f * sin(t * 1.3f) + smooth * 0.04f)

        drawCircle(
            Brush.radialGradient(
                listOf(c.copy(alpha = 0.10f + 0.12f * energy + 0.2f * smooth), c.copy(alpha = 0.03f + 0.04f * energy), c.copy(alpha = 0f)),
                center = Offset(cx, cy), radius = r * 1.9f,
            ),
            radius = r * 1.9f, center = Offset(cx, cy),
        )
        drawCircle(
            Brush.radialGradient(
                listOf(c.copy(alpha = 0.22f + 0.25f * smooth), c.copy(alpha = 0.05f), c.copy(alpha = 0f)),
                center = Offset(cx - r * 0.3f, cy - r * 0.35f), radius = r * 1.1f,
            ),
            radius = r, center = Offset(cx, cy),
        )

        for (ring in 0..2) {
            val amp = r * (0.03f + 0.05f * energy + 0.22f * smooth) * (1 - ring * 0.25f)
            val dir = if (ring % 2 == 0) 1f else -1f
            val rot = t * (0.15f + ring * 0.07f) * dir + if (state == OrbState.Thinking) t * 1.6f else 0f
            val path = Path()
            for (i in 0..seeds.size) {
                val a = seeds[i % seeds.size].first + rot
                val n = sin(a * (3 + ring) + t * 1.7f) * 0.6f + sin(a * 7 - t * 2.3f + ring) * 0.4f
                val rr = r * (1 + ring * 0.09f) + n * amp
                val x = cx + cos(a) * rr
                val y = cy + sin(a) * rr
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, c.copy(alpha = 0.55f - ring * 0.16f), style = Stroke(width = (2.4f - ring * 0.5f), cap = StrokeCap.Round))
        }

        for (i in seeds.indices step 2) {
            val (a0, p, s) = seeds[i]
            val a = a0 - t * 0.12f * s
            val rr = r * (1.28f + 0.18f * sin(t * s + p)) + smooth * r * 0.25f * sin(p + t * 3)
            val alpha = ((0.15f + 0.5f * energy) * (0.5f + 0.5f * sin(t * 2 * s + p))).coerceIn(0f, 1f)
            drawCircle(c.copy(alpha = alpha), radius = 1.2f + s * 1.4f, center = Offset(cx + cos(a) * rr, cy + sin(a) * rr))
        }

        if (state == OrbState.Thinking) {
            drawArc(
                c.copy(alpha = 0.7f), startAngle = (t * 170f) % 360f, sweepAngle = 63f, useCenter = false,
                topLeft = Offset(cx - r * 1.45f, cy - r * 1.45f),
                size = androidx.compose.ui.geometry.Size(r * 2.9f, r * 2.9f),
                style = Stroke(width = 3f, cap = StrokeCap.Round),
            )
        }
    }
}
