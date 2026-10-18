package com.nova.assistant.assist

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import com.nova.assistant.AssistActivity

/**
 * Android binds this while Nova is the phone's "Digital assistant app".
 * Holding the power/home button (or "Hey Nova") calls showSession(), which opens the pop-up.
 */
class NovaInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        instance = this
    }

    override fun onShutdown() {
        instance = null
        super.onShutdown()
    }

    companion object {
        @Volatile
        var instance: NovaInteractionService? = null
            private set

        /** True if the system says Nova is the active assistant. */
        fun isDefault(ctx: Context) = try {
            VoiceInteractionService.isActiveService(ctx, android.content.ComponentName(ctx, NovaInteractionService::class.java))
        } catch (e: Exception) {
            false
        }
    }
}

class NovaSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = NovaSession(this)
}

class NovaSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // Prefer the floating panel over whatever app you're in.
        if (OverlayPanel.canShow(context)) {
            OverlayPanel.show(context)
            hide()
            return
        }
        val intent = Intent(context, AssistActivity::class.java)
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                startAssistantActivity(intent)
            } catch (e: Exception) {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } else {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        hide()
    }
}

/** Required by Android for an assistant app; Nova does its own listening in the pop-up. */
class NovaRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener?.error(android.speech.SpeechRecognizer.ERROR_CLIENT)
    }
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}
