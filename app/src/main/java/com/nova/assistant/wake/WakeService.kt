package com.nova.assistant.wake

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import com.nova.assistant.AssistActivity
import com.nova.assistant.MainActivity
import com.nova.assistant.NovaApp
import com.nova.assistant.Prefs
import com.nova.assistant.R
import com.nova.assistant.assist.NovaInteractionService
import com.nova.assistant.assist.OverlayPanel
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File

/**
 * Listens for "Hey Nova" completely offline (Vosk, with a two-word grammar so it only
 * tries to recognise the wake word). Pauses itself while the pop-up is using the mic.
 */
class WakeService : Service(), RecognitionListener {
    private var model: Model? = null
    private var speech: SpeechService? = null
    private var lastTrigger = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Prefs(this).wakeWord || !modelReady(this) ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(ID, notification())
            }
        } catch (e: Exception) {
            // Android refuses to start a microphone service from the background; the app will retry when opened.
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_PAUSE -> pauseListening()
            else -> startListening()
        }
        return START_STICKY
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, NovaApp.CH_WAKE)
            .setSmallIcon(R.drawable.ic_stat_nova)
            .setContentTitle("Nova is listening for “Hey Nova”")
            .setContentText("Everything stays on your phone.")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun startListening() {
        if (speech != null) return
        try {
            val m = model ?: Model(modelDir(this).absolutePath).also { model = it }
            // Sound-alike words soak up near misses (TV, videos) so only a clear "hey nova" wins.
            val grammar = listOf(
                "hey nova", "okay nova", "nova", "hey", "okay", "no", "over", "know", "november", "novel", "now",
                "hey now", "hey no", "a nova", "bossa nova", "casanova", "[unk]",
            ).joinToString(",", "[", "]") { "\"$it\"" }
            val rec = Recognizer(m, 16000f, grammar).apply { setWords(true) }
            speech = SpeechService(rec, 16000f).also { it.startListening(this) }
        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun pauseListening() {
        speech?.stop()
        speech?.shutdown()
        speech = null
    }

    /** Only a complete, confident "hey nova" / "okay nova" counts. */
    private fun heard(json: String) {
        val o = try { JSONObject(json) } catch (e: Exception) { return }
        val text = o.optString("text")
        if (text != "hey nova" && text != "okay nova") return
        val words = o.optJSONArray("result") ?: return
        for (i in 0 until words.length()) if (words.getJSONObject(i).optDouble("conf", 0.0) < 0.8) return
        val now = System.currentTimeMillis()
        if (now - lastTrigger < 3000) return
        lastTrigger = now
        pauseListening()
        // a short chime so you know it heard you
        runCatching {
            android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 60)
                .startTone(android.media.ToneGenerator.TONE_PROP_ACK, 150)
        }
        openNova(this)
    }

    override fun onPartialResult(hypothesis: String) {} // partial guesses caused false pop-ups
    override fun onResult(hypothesis: String) = heard(hypothesis)
    override fun onFinalResult(hypothesis: String) = heard(hypothesis)
    override fun onError(exception: Exception) { pauseListening(); startListening() }
    override fun onTimeout() { pauseListening(); startListening() }

    override fun onDestroy() {
        pauseListening()
        model?.close()
        model = null
        instance = null
        super.onDestroy()
    }

    companion object {
        private const val ID = 7
        private const val ACTION_PAUSE = "pause"
        @Volatile private var instance: WakeService? = null

        fun modelDir(ctx: Context) = File(ctx.filesDir, "vosk")
        fun modelReady(ctx: Context) = File(modelDir(ctx), "am/final.mdl").exists()

        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, WakeService::class.java))
            } catch (e: Exception) { /* not allowed right now */ }
        }

        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, WakeService::class.java))

        /** Called by the pop-up: let go of the microphone. */
        fun pause() { instance?.pauseListening() }

        /** Called when the pop-up closes. */
        fun resume() { instance?.startListening() }

        /** Show the Nova pop-up from the background. */
        fun openNova(ctx: Context) {
            if (OverlayPanel.canShow(ctx)) {
                android.os.Handler(android.os.Looper.getMainLooper()).post { OverlayPanel.show(ctx) }
                return
            }
            val svc = NovaInteractionService.instance
            if (svc != null) {
                try { svc.showSession(Bundle(), 0); return } catch (e: Exception) { }
            }
            val i = Intent(ctx, AssistActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Settings.canDrawOverlays(ctx)) {
                try { ctx.startActivity(i); return } catch (e: Exception) { }
            }
            // Last resort: a heads-up notification you tap.
            val pi = PendingIntent.getActivity(ctx, 1, i, PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(ctx, NovaApp.CH_WAKEUP)
                .setSmallIcon(R.drawable.ic_stat_nova)
                .setContentTitle("Nova")
                .setContentText("Tap to talk")
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .setAutoCancel(true)
                .build()
            ctx.getSystemService(NotificationManager::class.java).notify(8, n)
        }
    }
}
