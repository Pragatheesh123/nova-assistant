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

    /** English voices installed on this phone that work offline. */
    fun voices(): List<Voice> = try {
        (tts.voices ?: emptySet()).filter {
            it.locale.language == "en" && !it.isNetworkConnectionRequired &&
                it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true
        }.sortedWith(compareBy({ it.locale.country != "GB" }, { it.name }))
    } catch (e: Exception) { emptyList() }

    private fun pickVoice() {
        val uk = prefs.britishVoice
        tts.language = if (uk) Locale.UK else Locale.US
        val all = voices()
        val chosen = all.firstOrNull { it.name == prefs.voiceName }
            // Jarvis-ish: a British male voice. Google's offline UK male voices are rjs, gbd and gbb.
            ?: all.filter { it.locale.country == (if (uk) "GB" else "US") }.let { local ->
                local.firstOrNull { v -> listOf("rjs", "gbd", "gbb", "male").any { v.name.contains(it, ignoreCase = true) } }
                    ?: local.maxByOrNull { it.quality }
            }
        if (chosen != null) tts.voice = chosen
        // a little deeper and more measured than the default
        tts.setPitch(0.86f)
        tts.setSpeechRate(0.96f)
    }

    fun use(v: Voice) {
        prefs.voiceName = v.name
        pickVoice()
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
