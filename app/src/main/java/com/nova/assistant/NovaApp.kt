package com.nova.assistant

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class NovaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_WAKE, "Hey Nova listening", NotificationManager.IMPORTANCE_MIN)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_REMIND, "Reminders", NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_WAKEUP, "Nova pop-up", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    companion object {
        const val CH_WAKE = "wake"
        const val CH_REMIND = "remind"
        const val CH_WAKEUP = "wakeup"
        lateinit var instance: NovaApp
            private set

        /** Work that should outlive a screen (downloads, model loading). */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}

/** Small typed wrapper around SharedPreferences. */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("nova", Context.MODE_PRIVATE)

    var wakeWord: Boolean
        get() = p.getBoolean("wake", false)
        set(v) = p.edit().putBoolean("wake", v).apply()

    var britishVoice: Boolean
        get() = p.getBoolean("uk", true)
        set(v) = p.edit().putBoolean("uk", v).apply()

    var modelUrl: String
        get() = p.getString("modelUrl", null) ?: DEFAULT_MODEL_URL
        set(v) = p.edit().putString("modelUrl", v).apply()

    var modelDownloadId: Long
        get() = p.getLong("modelDl", -1)
        set(v) = p.edit().putLong("modelDl", v).apply()

    var voskDownloadId: Long
        get() = p.getLong("voskDl", -1)
        set(v) = p.edit().putLong("voskDl", v).apply()

    /** Free cloud brain (Groq). */
    var groqKey: String
        get() = p.getString("groqKey", "") ?: ""
        set(v) = p.edit().putString("groqKey", v.trim()).apply()

    /** Optional paid brain (Claude); used instead of Groq when set. */
    var claudeKey: String
        get() = p.getString("claudeKey", "") ?: ""
        set(v) = p.edit().putString("claudeKey", v.trim()).apply()

    /** Chosen TTS voice name ("" = automatic British male). */
    var voiceName: String
        get() = p.getString("voiceName", "") ?: ""
        set(v) = p.edit().putString("voiceName", v).apply()

    /** JSON {facts:[...], routines:[{name,trigger,steps:[...]}]} */
    var profile: String
        get() = p.getString("profile", "") ?: ""
        set(v) = p.edit().putString("profile", v).apply()

    /** JSON {loud, speed, n}: the user's normal speaking voice, for tone-of-voice hints. */
    var voiceBaseline: String
        get() = p.getString("voiceBase", "") ?: ""
        set(v) = p.edit().putString("voiceBase", v).apply()

    var notes: String
        get() = p.getString("notes", "") ?: ""
        set(v) = p.edit().putString("notes", v).apply()

    companion object {
        // Qwen2.5 1.5B Instruct converted for on-device use by Google's LiteRT community (~1.6 GB).
        const val DEFAULT_MODEL_URL =
            "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/" +
                "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"
        const val VOSK_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    }
}
