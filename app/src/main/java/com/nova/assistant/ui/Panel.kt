package com.nova.assistant.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nova.assistant.assist.Assistant

private val Shape = RoundedCornerShape(34.dp)

/** A card whose border is a slowly rotating band of light. */
@Composable
fun GlowCard(modifier: Modifier = Modifier, alert: Boolean = false, content: @Composable () -> Unit) {
    val spin by rememberInfiniteTransition(label = "border").animateFloat(
        0f, 360f, infiniteRepeatable(tween(5200, easing = LinearEasing), RepeatMode.Restart), label = "spin",
    )
    val tint = if (alert) Nova.Red else Nova.Pearl
    Box(
        modifier
            .clip(Shape)
            .drawBehind {
                rotate(spin) {
                    drawCircle(
                        Brush.sweepGradient(
                            listOf(tint.copy(alpha = 0.04f), tint.copy(alpha = 0.95f), tint.copy(alpha = 0.04f), tint.copy(alpha = 0.04f),
                                tint.copy(alpha = 0.5f), tint.copy(alpha = 0.04f)),
                            center = center,
                        ),
                        radius = size.maxDimension,
                    )
                }
            }
            .padding(1.4.dp)
            .clip(Shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF0B0B0B), Nova.Ink, Color(0xFF020202))))
            .drawBehind {
                // faint top highlight, like light on glass
                drawCircle(
                    Brush.radialGradient(listOf(Nova.Pearl.copy(alpha = 0.07f), Color.Transparent),
                        center = Offset(size.width / 2, 0f), radius = size.width * 0.7f),
                    radius = size.width * 0.7f, center = Offset(size.width / 2, 0f),
                )
            },
    ) { content() }
}

/** The Nova panel shown when you call it, both as a pop-up and as a floating window. */
@Composable
fun NovaPanel(
    a: Assistant,
    orbSize: Dp,
    onClose: () -> Unit,
    onKeyboard: (() -> Unit)? = null,
    extra: @Composable () -> Unit = {},
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val none = remember { MutableInteractionSource() }
    val alert = a.confirm?.warning?.isNotEmpty() == true

    AnimatedVisibility(
        visible = shown,
        enter = scaleIn(tween(380), initialScale = 0.9f) + fadeIn(tween(260)) + slideInVertically(tween(380)) { -it / 8 },
    ) {
        GlowCard(
            Modifier
                .fillMaxWidth()
                .clickable(interactionSource = none, indication = null) { a.hold() },
            alert = alert,
        ) {
            Column(Modifier.padding(horizontal = 22.dp, vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                // HUD header
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("N O V A", style = Nova.mono.copy(color = Nova.Pearl, fontSize = 12.sp, letterSpacing = 3.sp))
                    Spacer(Modifier.width(10.dp))
                    StatusChip(a)
                    Spacer(Modifier.weight(1f))
                    if (onKeyboard != null) {
                        IconChip("⌨") { a.stopListening(); onKeyboard() }
                        Spacer(Modifier.width(8.dp))
                    }
                    IconChip("✕") { onClose() }
                }

                Box(
                    Modifier
                        .padding(top = 6.dp)
                        .size(orbSize)
                        .clickable(interactionSource = none, indication = null) { a.listen() },
                    contentAlignment = Alignment.Center,
                ) { HudOrb(a.orb, a.level, Modifier.size(orbSize)) }

                if (a.heard.isNotEmpty()) {
                    Text(
                        "“${a.heard}”", style = Nova.body.copy(fontSize = 15.sp), textAlign = TextAlign.Center, maxLines = 3,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (a.step.isNotEmpty()) {
                    Text("▸ ${a.step.uppercase()}", style = Nova.mono, modifier = Modifier.padding(top = 6.dp))
                }
                if (a.answer.isNotEmpty()) {
                    Text(
                        a.answer, style = Nova.answer, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 12.dp).fillMaxWidth(),
                    )
                }

                a.confirm?.let { c ->
                    if (c.warning.isNotEmpty()) {
                        Column(
                            Modifier
                                .padding(top = 14.dp)
                                .fillMaxWidth()
                                .background(Color(0x14FF5A46), RoundedCornerShape(18.dp))
                                .border(1.dp, Color(0x66FF5A46), RoundedCornerShape(18.dp))
                                .padding(14.dp),
                        ) {
                            Text("⚠ DATA ABOUT TO LEAVE YOUR PHONE", style = Nova.mono.copy(color = Nova.Red, letterSpacing = 1.5.sp))
                            for (w in c.warning) Text(w, style = Nova.body.copy(color = Nova.Pearl), modifier = Modifier.padding(top = 6.dp))
                            if (a.abortIn > 0) {
                                Text("AUTO-ABORT IN ${a.abortIn}s", style = Nova.mono.copy(fontSize = 10.sp), modifier = Modifier.padding(top = 8.dp))
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 16.dp)) {
                        if (c.warning.isNotEmpty()) {
                            Pill(c.confirmLabel.ifEmpty { "Yes" }, solid = false, Modifier.weight(1f)) { a.acceptConfirm(c) }
                            Pill(c.cancelLabel, solid = true, Modifier.weight(1f)) { a.cancelConfirm() }
                        } else {
                            Pill(c.cancelLabel, solid = false, Modifier.weight(1f)) { a.cancelConfirm() }
                            Pill(c.confirmLabel.ifEmpty { "Yes" }, solid = true, Modifier.weight(1f)) { a.acceptConfirm(c) }
                        }
                    }
                }
                extra()
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun StatusChip(a: Assistant) {
    val label = when (a.orb) {
        OrbState.Listening -> "LISTENING"
        OrbState.Thinking -> "PROCESSING"
        OrbState.Speaking -> "SPEAKING"
        OrbState.Idle -> "ONLINE"
    }
    val blink by rememberInfiniteTransition(label = "blink").animateFloat(
        0.3f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a",
    )
    Row(
        Modifier
            .background(Nova.Surface, RoundedCornerShape(99.dp))
            .border(1.dp, Nova.Line, RoundedCornerShape(99.dp))
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(Nova.Pearl.copy(alpha = if (a.orb == OrbState.Idle) 0.6f else blink), CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label, style = Nova.mono.copy(fontSize = 9.5.sp, color = Nova.Pearl2))
    }
}

@Composable
fun IconChip(glyph: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .background(Nova.Surface, CircleShape)
            .border(1.dp, Nova.Line, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(glyph, color = Nova.Pearl2, fontSize = 13.sp) }
}

@Composable
fun Pill(label: String, solid: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(50.dp)
            .background(if (solid) Nova.Pearl else Nova.Surface, RoundedCornerShape(99.dp))
            .border(1.dp, if (solid) Nova.Pearl else Nova.Line, RoundedCornerShape(99.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (solid) Nova.Ink else Nova.Pearl, fontSize = 15.sp) }
}
