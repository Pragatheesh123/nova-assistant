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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

enum class OrbState { Idle, Listening, Thinking, Speaking }

/**
 * Jarvis-style HUD: rotating tick ring, sweeping arc segments, a voice spectrum
 * that reacts to your voice, and a glowing core. Monochrome pearl on black.
 */
@Composable
fun HudOrb(state: OrbState, level: Float, modifier: Modifier = Modifier) {
    var t by remember { mutableFloatStateOf(0f) }
    val k = remember { FloatArray(3) } // smoothed level, energy, spin speed
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) withFrameNanos { now ->
            t += if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceAtMost(0.05f)
            last = now
        }
    }

    Canvas(modifier) {
        val target = when (state) {
            OrbState.Listening -> level.coerceIn(0f, 1f)
            OrbState.Speaking -> 0.45f + 0.35f * abs(sin(t * 7.3f)) * abs(sin(t * 2.1f + 1f))
            OrbState.Thinking -> 0.2f
            OrbState.Idle -> 0f
        }
        k[0] += (target - k[0]) * 0.2f
        k[1] += ((if (state == OrbState.Idle) 0.15f else 1f) - k[1]) * 0.05f
        k[2] += ((if (state == OrbState.Thinking) 4f else 1f) - k[2]) * 0.04f
        val lvl = k[0]
        val energy = k[1]
        val spin = k[2]

        val c = Nova.Pearl
        val cx = size.width / 2
        val cy = size.height / 2
        val R = size.minDimension / 2 * 0.96f
        val center = Offset(cx, cy)
        val px = size.minDimension / 300f // scale strokes with size

        // ambient glow
        drawCircle(
            Brush.radialGradient(
                listOf(c.copy(alpha = 0.10f + 0.18f * energy * (0.6f + lvl)), c.copy(alpha = 0.02f), c.copy(alpha = 0f)),
                center = center, radius = R,
            ),
            radius = R, center = center,
        )

        // outer hairline + tick ring
        drawCircle(c.copy(alpha = 0.18f + 0.1f * energy), radius = R * 0.985f, center = center, style = Stroke(px))
        rotate(degrees = t * 6f * spin, pivot = center) {
            for (i in 0 until 90) {
                val a = i / 90f * 2 * PI.toFloat()
                val long = i % 6 == 0
                val r1 = R * (if (long) 0.86f else 0.9f)
                val r2 = R * 0.95f
                drawLine(
                    c.copy(alpha = if (long) 0.55f else 0.22f),
                    Offset(cx + cos(a) * r1, cy + sin(a) * r1), Offset(cx + cos(a) * r2, cy + sin(a) * r2),
                    strokeWidth = if (long) 1.6f * px else px,
                )
            }
        }

        // three bright arc segments sweeping the other way
        rotate(degrees = -t * 40f * spin, pivot = center) {
            val r = R * 0.79f
            for (j in 0..2) {
                drawArc(
                    c.copy(alpha = 0.35f + 0.5f * energy), startAngle = j * 120f, sweepAngle = 38f + 14f * sin(t * 1.3f + j),
                    useCenter = false, topLeft = Offset(cx - r, cy - r), size = Size(2 * r, 2 * r),
                    style = Stroke(2.6f * px, cap = StrokeCap.Round),
                )
            }
        }

        // voice spectrum
        val bars = 72
        val base = R * 0.6f
        for (i in 0 until bars) {
            val a = i / bars.toFloat() * 2 * PI.toFloat() + t * 0.2f
            val n = 0.5f + 0.5f * sin(i * 0.9f + t * 9f) * sin(i * 0.37f - t * 5.1f)
            val len = R * (0.015f + (0.05f * energy + 0.2f * lvl) * n)
            drawLine(
                c.copy(alpha = 0.25f + 0.55f * energy),
                Offset(cx + cos(a) * base, cy + sin(a) * base),
                Offset(cx + cos(a) * (base + len), cy + sin(a) * (base + len)),
                strokeWidth = 1.8f * px, cap = StrokeCap.Round,
            )
        }

        // inner rings: one solid, one dashed and rotating
        drawCircle(c.copy(alpha = 0.55f), radius = R * 0.56f, center = center, style = Stroke(1.4f * px))
        rotate(degrees = t * 25f * spin, pivot = center) { dashedRing(center, R * 0.47f, 24, c.copy(alpha = 0.45f), 1.6f * px) }

        // orbiting marker
        val oa = t * 1.4f * spin
        drawCircle(c.copy(alpha = 0.9f), radius = 2.6f * px, center = Offset(cx + cos(oa) * R * 0.7f, cy + sin(oa) * R * 0.7f))

        // core
        val coreR = R * (0.3f + 0.05f * sin(t * 1.6f) * (1 - energy) + 0.08f * lvl)
        drawCircle(
            Brush.radialGradient(
                listOf(c.copy(alpha = 0.95f), c.copy(alpha = 0.35f + 0.3f * energy), c.copy(alpha = 0f)),
                center = center, radius = coreR * 1.25f,
            ),
            radius = coreR * 1.25f, center = center,
        )
        drawCircle(Nova.Ink.copy(alpha = 0.55f), radius = coreR * 0.42f, center = center)
        drawCircle(c, radius = coreR * 0.42f, center = center, style = Stroke(1.2f * px))

        // thinking: scanning sector
        if (state == OrbState.Thinking) {
            rotate(degrees = t * 260f, pivot = center) {
                drawArc(
                    Brush.sweepGradient(listOf(c.copy(alpha = 0f), c.copy(alpha = 0.35f)), center = center),
                    startAngle = 0f, sweepAngle = 70f, useCenter = true,
                    topLeft = Offset(cx - R * 0.95f, cy - R * 0.95f), size = Size(R * 1.9f, R * 1.9f),
                )
            }
        }
    }
}

private fun DrawScope.dashedRing(center: Offset, r: Float, dashes: Int, color: androidx.compose.ui.graphics.Color, w: Float) {
    val step = 360f / dashes
    for (i in 0 until dashes) {
        drawArc(
            color, startAngle = i * step, sweepAngle = step * 0.55f, useCenter = false,
            topLeft = Offset(center.x - r, center.y - r), size = Size(2 * r, 2 * r), style = Stroke(w),
        )
    }
}
