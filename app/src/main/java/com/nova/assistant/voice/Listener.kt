package com.nova.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Hears one sentence. Uses the phone's on-device recogniser when it has one,
 * otherwise the normal recogniser asked to stay offline.
 */
class Listener(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onLevel: (Float) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private var recognizer: SpeechRecognizer? = null
    private var triedFallback = false

    fun start(forceStandard: Boolean = false) {
        stop()
        val onDevice = !forceStandard && Build.VERSION.SDK_INT >= 31 &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        recognizer = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)

        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) = onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() = onLevel(0f)
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(onPartial)
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                onFinal(text)
            }

            override fun onError(error: Int) {
                // On-device language pack missing etc. -> try the standard recogniser once.
                if (onDevice && !triedFallback && error in setOf(
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                        SpeechRecognizer.ERROR_SERVER,
                        SpeechRecognizer.ERROR_CLIENT,
                    )
                ) {
                    triedFallback = true
                    start(forceStandard = true)
                    return
                }
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> onFinal("")
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> onError("I need microphone permission. Open the Nova app to allow it.")
                    else -> onError("I couldn't hear that.")
                }
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        recognizer?.startListening(intent)
    }

    fun stop() {
        recognizer?.destroy()
        recognizer = null
    }
}
