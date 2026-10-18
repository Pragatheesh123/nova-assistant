package com.nova.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.nova.assistant.actions.Actions
import com.nova.assistant.actions.Outcome
import com.nova.assistant.brain.Brain
import com.nova.assistant.brain.Llm
import com.nova.assistant.ui.Nova
import com.nova.assistant.ui.NovaTheme
import com.nova.assistant.ui.Orb
import com.nova.assistant.ui.OrbState
import com.nova.assistant.voice.Listener
import com.nova.assistant.voice.Speaker
import com.nova.assistant.wake.WakeService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The Siri-style pop-up: listens, thinks, answers or acts, then gets out of the way. */
class AssistActivity : ComponentActivity() {
    private var orb by mutableStateOf(OrbState.Idle)
    private var level by mutableFloatStateOf(0f)
    private var heard by mutableStateOf("")
    private var answer by mutableStateOf("")
    private var confirm by mutableStateOf<Outcome?>(null)
    private var typing by mutableStateOf(false)

    private lateinit var speaker: Speaker
    private lateinit var listener: Listener
    private lateinit var brain: Brain
    private var closer: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        WakeService.pause()

        brain = Brain(this)
        speaker = Speaker(this) { speaking ->
            runOnUiThread { if (speaking) orb = OrbState.Speaking else if (orb == OrbState.Speaking) orb = OrbState.Idle }
        }
        listener = Listener(
            this,
            onPartial = { heard = it },
            onLevel = { level = it },
            onFinal = { text -> onHeard(text) },
            onError = { msg -> orb = OrbState.Idle; answer = msg; autoClose(4000) },
        )
        NovaApp.scope.launch { Llm.warmUp(applicationContext) }

        setContent { NovaTheme { Panel() } }
        listen()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        listen() // summoned again while open: start a new question
    }

    private fun listen() {
        closer?.cancel()
        speaker.stop()
        confirm = null
        typing = false
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            answer = "I need the microphone. Open the Nova app once to allow it, or tap the keyboard to type."
            return
        }
        heard = ""
        answer = ""
        orb = OrbState.Listening
        listener.start()
    }

    private fun onHeard(text: String) {
        level = 0f
        if (text.isBlank()) {
            orb = OrbState.Idle
            if (answer.isEmpty()) answer = "I didn't hear anything. Tap the orb to try again."
            autoClose(4000)
            return
        }
        heard = text
        ask(text)
    }

    private fun ask(text: String) {
        orb = OrbState.Thinking
        answer = ""
        lifecycleScope.launch {
            val out = try { brain.handle(text) } catch (e: Exception) { Outcome("Sorry, something went wrong.") }
            present(out)
        }
    }

    private fun present(out: Outcome) {
        answer = out.say
        if (out.confirm != null) {
            confirm = out
            orb = OrbState.Idle
            speaker.say(out.say)
            return
        }
        val launched = out.launch?.let { Actions.safeStart(this, it) } ?: false
        if (out.launch != null && !launched) answer = "I couldn't find an app on your phone for that."
        orb = OrbState.Speaking
        speaker.say(answer) { runOnUiThread { autoClose(if (launched) 300 else 3500) } }
    }

    private fun autoClose(ms: Long) {
        closer?.cancel()
        closer = lifecycleScope.launch { delay(ms); finish() }
    }

    private fun userTouched() { closer?.cancel() }

    override fun onDestroy() {
        listener.stop()
        speaker.shutdown()
        WakeService.resume()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ UI

    @Composable
    private fun Panel() {
        var shown by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { shown = true }
        val none = remember { MutableInteractionSource() }

        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000))))
                .clickable(interactionSource = none, indication = null) { finish() },
            contentAlignment = Alignment.BottomCenter,
        ) {
            AnimatedVisibility(
                visible = shown,
                enter = slideInVertically(tween(420)) { it / 2 } + fadeIn(tween(300)),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp)
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(bottom = 10.dp)
                        .background(Nova.Ink, RoundedCornerShape(30.dp))
                        .border(1.dp, Nova.Line, RoundedCornerShape(30.dp))
                        .clickable(interactionSource = none, indication = null) { userTouched() }
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Orb(
                            orb, level,
                            Modifier
                                .size(64.dp)
                                .clickable(interactionSource = none, indication = null) { listen() },
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                when (orb) {
                                    OrbState.Listening -> "NOVA · LISTENING"
                                    OrbState.Thinking -> "NOVA · THINKING"
                                    OrbState.Speaking -> "NOVA"
                                    OrbState.Idle -> "NOVA"
                                },
                                style = Nova.kicker,
                            )
                            if (heard.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Text("“$heard”", style = Nova.body, maxLines = 3)
                            }
                        }
                        RoundIcon("⌨") { userTouched(); listener.stop(); orb = OrbState.Idle; typing = !typing }
                    }

                    if (answer.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        Text(answer, style = Nova.answer)
                    }

                    confirm?.let { c ->
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Pill("Cancel", solid = false, Modifier.weight(1f)) { confirm = null; answer = "Okay, cancelled."; autoClose(1200) }
                            Pill(c.confirmLabel.ifEmpty { "Yes" }, solid = true, Modifier.weight(1f)) {
                                confirm = null
                                present(c.confirm!!.invoke())
                            }
                        }
                    }

                    if (typing) {
                        Spacer(Modifier.height(14.dp))
                        TypeBox { text -> typing = false; heard = text; ask(text) }
                    }
                }
            }
        }
    }

    @Composable
    private fun TypeBox(onSend: (String) -> Unit) {
        var value by remember { mutableStateOf("") }
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        Box(
            Modifier
                .fillMaxWidth()
                .background(Nova.Surface, RoundedCornerShape(99.dp))
                .border(1.dp, Nova.Line, RoundedCornerShape(99.dp))
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            if (value.isEmpty()) Text("Type to Nova…", style = Nova.body.copy(color = Nova.Pearl3))
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                textStyle = TextStyle(color = Nova.Pearl, fontSize = 16.sp),
                cursorBrush = SolidColor(Nova.Pearl),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (value.isNotBlank()) onSend(value.trim()) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
    }
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
    ) {
        Text(label, color = if (solid) Nova.Ink else Nova.Pearl, fontSize = 15.sp)
    }
}

@Composable
fun RoundIcon(glyph: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .background(Nova.Surface, CircleShape)
            .border(1.dp, Nova.Line, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = Nova.Pearl2, fontSize = 16.sp)
    }
}
