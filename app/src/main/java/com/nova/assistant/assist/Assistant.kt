package com.nova.assistant.assist

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nova.assistant.NovaApp
import com.nova.assistant.actions.Actions
import com.nova.assistant.actions.Outcome
import com.nova.assistant.brain.Brain
import com.nova.assistant.brain.Llm
import com.nova.assistant.ui.OrbState
import com.nova.assistant.voice.Listener
import com.nova.assistant.voice.Speaker
import com.nova.assistant.voice.VoskEars
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One conversation turn: listen -> think -> answer / act -> close.
 * Shared by the full-screen pop-up (AssistActivity) and the floating panel (OverlayPanel).
 */
class Assistant(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val close: () -> Unit,
) {
    var orb by mutableStateOf(OrbState.Idle)
    var level by mutableFloatStateOf(0f)
    var heard by mutableStateOf("")
    var answer by mutableStateOf("")
    var step by mutableStateOf("")
    var confirm by mutableStateOf<Outcome?>(null)
    var abortIn by mutableIntStateOf(0)

    private val main = Handler(Looper.getMainLooper())
    private val brain = Brain(ctx)
    private var closer: Job? = null
    private var abortTimer: Job? = null
    private var turn: Job? = null

    private val speaker = Speaker(ctx) { speaking ->
        main.post { if (speaking) orb = OrbState.Speaking else if (orb == OrbState.Speaking) orb = OrbState.Idle }
    }
    private val vosk = VoskEars(
        ctx,
        onPartial = { t -> main.post { heard = t } },
        onLevel = { l -> main.post { level = l } },
        onFinal = { t -> main.post { onHeard(t) } },
        onError = { m -> main.post { failed(m) } },
    )
    // tone of voice: loudness samples while you speak
    private val levels = ArrayList<Float>()
    private var speechStart = 0L
    private var cues = ""

    private val listener = Listener(
        ctx,
        onPartial = { heard = it },
        onLevel = { l ->
            level = l
            if (orb == OrbState.Listening) {
                levels += l
                if (l > 0.2f && speechStart == 0L) speechStart = System.currentTimeMillis()
            }
        },
        onFinal = { onHeard(it) },
        onError = { failed(it) },
        onBlocked = {
            if (vosk.available()) vosk.start()
            else failed("Android won't let me use the mic from here. Turn on “Hey Nova” in the app (it adds offline ears), or tap the keyboard.")
        },
    )

    init {
        NovaApp.scope.launch { Llm.warmUp(ctx.applicationContext) }
    }

    fun listen() {
        closer?.cancel()
        abortTimer?.cancel(); abortIn = 0
        turn?.cancel()
        speaker.stop()
        vosk.stop()
        confirm = null
        step = ""
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            answer = "I need the microphone. Open the Nova app once to allow it."
            return
        }
        heard = ""
        answer = ""
        levels.clear(); speechStart = 0L; cues = ""
        orb = OrbState.Listening
        listener.start()
    }

    private fun failed(msg: String) {
        orb = OrbState.Idle
        level = 0f
        answer = msg
        autoClose(6000)
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
        cues = toneOfVoice(text)
        ask(text)
    }

    /** "quieter than usual, faster than usual" compared with how this user normally talks. */
    private fun toneOfVoice(text: String): String {
        val voiced = levels.filter { it > 0.12f }
        val secs = if (speechStart > 0) (System.currentTimeMillis() - speechStart) / 1000f else 0f
        val words = text.trim().split(Regex("\\s+")).size
        if (voiced.size < 5 || secs < 0.6f) return ""
        val loud = voiced.average().toFloat()
        val speed = words / secs
        val prefs = com.nova.assistant.Prefs(ctx)
        val b = runCatching { org.json.JSONObject(prefs.voiceBaseline) }.getOrNull()
        val bl = b?.optDouble("loud", loud.toDouble())?.toFloat() ?: loud
        val bs = b?.optDouble("speed", speed.toDouble())?.toFloat() ?: speed
        val n = b?.optInt("n", 0) ?: 0
        val out = mutableListOf<String>()
        if (n >= 5) {
            if (loud > bl * 1.3f) out += "louder than usual" else if (loud < bl * 0.7f) out += "quieter than usual"
            if (speed > bs * 1.25f) out += "faster than usual" else if (speed < bs * 0.75f) out += "slower than usual"
        }
        val k = 0.15f
        prefs.voiceBaseline = org.json.JSONObject()
            .put("loud", (bl * (1 - k) + loud * k).toDouble()).put("speed", (bs * (1 - k) + speed * k).toDouble()).put("n", n + 1)
            .toString()
        return out.joinToString(", ").ifEmpty { "normal for them" }
    }

    fun ask(text: String) {
        closer?.cancel()
        heard = text
        orb = OrbState.Thinking
        answer = ""
        turn = scope.launch {
            val out = try {
                brain.handle(text, { s -> main.post { step = s } }, cues).also { cues = "" }
            } catch (e: Exception) {
                Outcome("Sorry, something went wrong.")
            }
            step = ""
            present(out)
        }
    }

    private fun present(out: Outcome) {
        answer = out.say
        if (out.confirm != null) {
            confirm = out
            orb = OrbState.Idle
            speaker.say(out.say)
            if (out.warning.isNotEmpty()) startAbortCountdown()
            return
        }
        val launched = out.launch?.let { Actions.safeStart(ctx, it) } ?: false
        if (out.launch != null && !launched) answer = "I couldn't find an app on your phone for that."
        val after = out.after
        if (after != null && (out.launch == null || launched)) {
            if (answer.isNotBlank()) speaker.say(answer)
            orb = OrbState.Thinking
            turn = scope.launch {
                val extra = runCatching { after() }.getOrNull()
                if (extra != null) {
                    answer = extra
                    orb = OrbState.Speaking
                    speaker.say(extra) { main.post { autoClose(2500) } }
                } else {
                    orb = OrbState.Idle
                    autoClose(1800)
                }
            }
            return
        }
        if (answer.isBlank()) { autoClose(800); return }
        orb = OrbState.Speaking
        speaker.say(answer) { main.post { autoClose(if (launched) 600 else 3500) } }
    }

    /** Leak warnings abort on their own if you don't answer. */
    private fun startAbortCountdown() {
        abortTimer?.cancel()
        closer?.cancel()
        abortTimer = scope.launch {
            for (s in 25 downTo 1) { abortIn = s; delay(1000) }
            abortIn = 0
            if (confirm != null) cancelConfirm(timedOut = true)
        }
    }

    fun cancelConfirm(timedOut: Boolean = false) {
        val wasWarning = confirm?.warning?.isNotEmpty() == true
        abortTimer?.cancel(); abortIn = 0
        confirm = null
        answer = when {
            wasWarning && timedOut -> "No answer, so I aborted. Nothing was sent."
            wasWarning -> "Aborted. Nothing was sent."
            else -> "Okay, cancelled."
        }
        speaker.say(answer)
        autoClose(2500)
    }

    fun acceptConfirm(c: Outcome) {
        abortTimer?.cancel(); abortIn = 0
        confirm = null
        orb = OrbState.Thinking
        turn = scope.launch {
            val out = try { c.confirm!!.invoke() } catch (e: Exception) { Outcome("Sorry, that didn't work.") }
            present(out)
        }
    }

    fun autoClose(ms: Long) {
        closer?.cancel()
        closer = scope.launch { delay(ms); close() }
    }

    /** The user is interacting: don't vanish on them. */
    fun hold() { closer?.cancel() }

    fun stopListening() {
        listener.stop()
        vosk.stop()
        orb = OrbState.Idle
        level = 0f
    }

    fun release() {
        closer?.cancel(); abortTimer?.cancel(); turn?.cancel()
        listener.stop()
        vosk.stop()
        speaker.shutdown()
    }
}
