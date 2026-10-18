package com.nova.assistant.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.nova.assistant.Prefs
import java.util.Locale

/** On-device text-to-speech, preferring a British voice. */
class Speaker(context: Context, private val onState: (speaking: Boolean) -> Unit) {
    private val prefs = Prefs(context)
    private var ready = false
    private var pending: String? = null
    private var onDone: (() -> Unit)? = null

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            pickVoice()
            pending?.let { say(it, onDone) }
            pending = null
        }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = onState(true)
            override fun onDone(id: String?) = finished()
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) = finished()
            override fun onError(id: String?, errorCode: Int) = finished()
        })
    }

    private fun finished() {
        onState(false)
        val cb = onDone
        onDone = null
        cb?.invoke()
    }

    private fun pickVoice() {
        val locale = if (prefs.britishVoice) Locale.UK else Locale.US
        tts.language = locale
        val voices: Set<Voice> = try { tts.voices ?: emptySet() } catch (e: Exception) { emptySet() }
        val best = voices
            .filter { it.locale.language == "en" && it.locale.country == locale.country && !it.isNetworkConnectionRequired }
            .filter { it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true }
            .maxByOrNull { it.quality }
        if (best != null) tts.voice = best
        tts.setSpeechRate(1.0f)
        tts.setPitch(0.95f)
    }

    fun say(text: String, then: (() -> Unit)? = null) {
        onDone = then
        if (!ready) { pending = text; return }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nova")
    }

    fun stop() {
        onDone = null
        tts.stop()
        onState(false)
    }

    fun shutdown() = tts.shutdown()
}
