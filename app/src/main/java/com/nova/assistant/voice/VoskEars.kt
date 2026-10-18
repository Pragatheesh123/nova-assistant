package com.nova.assistant.voice

import android.content.Context
import com.nova.assistant.wake.WakeService
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * Offline backup ears (Vosk). Used when Android blocks the normal recogniser, e.g. when
 * Nova pops up over another app. Needs the wake-word model from setup step 4.
 */
class VoskEars(
    private val ctx: Context,
    private val onPartial: (String) -> Unit,
    private val onLevel: (Float) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onError: (String) -> Unit,
) : RecognitionListener {
    private var speech: SpeechService? = null
    private var lastPartial = ""
    private var done = false

    fun available() = WakeService.modelReady(ctx)

    fun start() {
        stop()
        done = false
        lastPartial = ""
        try {
            val m = model ?: Model(WakeService.modelDir(ctx).absolutePath).also { model = it }
            speech = SpeechService(Recognizer(m, 16000f), 16000f).also { it.startListening(this, 9000) }
        } catch (e: Exception) {
            onError("I can't use the microphone right now. Tap the keyboard to type.")
        }
    }

    fun stop() {
        speech?.stop()
        speech?.shutdown()
        speech = null
    }

    private fun finish(text: String) {
        if (done) return
        done = true
        stop()
        onLevel(0f)
        onFinal(text)
    }

    override fun onPartialResult(hypothesis: String) {
        val p = runCatching { JSONObject(hypothesis).optString("partial") }.getOrDefault("")
        if (p.isNotBlank() && p != lastPartial) {
            lastPartial = p
            onPartial(p)
            onLevel(0.6f) // Vosk has no level meter; pulse while words arrive
        } else onLevel(0.1f)
    }

    override fun onResult(hypothesis: String) {
        val text = runCatching { JSONObject(hypothesis).optString("text") }.getOrDefault("")
        if (text.isNotBlank()) finish(text)
    }

    override fun onFinalResult(hypothesis: String) {
        val text = runCatching { JSONObject(hypothesis).optString("text") }.getOrDefault("")
        finish(text.ifBlank { lastPartial })
    }

    override fun onError(exception: Exception) { if (!done) { done = true; stop(); onError("I couldn't hear that.") } }
    override fun onTimeout() = finish(lastPartial)

    companion object {
        private var model: Model? = null
    }
}
