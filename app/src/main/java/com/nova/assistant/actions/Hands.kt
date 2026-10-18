package com.nova.assistant.actions

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Talks to the optional "Nova Hands" companion app, an accessibility service that can tap,
 * type, scroll and read the screen in other apps. It's a separate app so the main Nova app
 * stays free of the accessibility permission (which makes Play Protect block installs).
 * Both apps are signed with the same key; the CONTROL permission is signature-only, so no other
 * app can send Hands commands or fake its replies.
 */
object Hands {
    const val PACKAGE = "com.nova.hands"
    const val PERMISSION = "com.nova.assistant.permission.CONTROL"
    private const val ACTION_DO = "com.nova.hands.DO"
    private const val ACTION_RESULT = "com.nova.assistant.HANDS_RESULT"

    fun installed(ctx: Context) = try {
        ctx.packageManager.getPackageInfo(PACKAGE, 0); true
    } catch (e: Exception) {
        false
    }

    fun enabled(ctx: Context): Boolean {
        val on = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return on.contains("$PACKAGE/", ignoreCase = true)
    }

    fun ready(ctx: Context) = installed(ctx) && enabled(ctx)

    /** Runs a command in Hands and returns its reply ("ok", "not found: …", screen text…), or null on timeout. */
    suspend fun call(ctx: Context, cmd: String, arg: String = "", timeoutMs: Long = 9000): String? {
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<String>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getStringExtra("id") == id) done.complete(i.getStringExtra("result").orEmpty())
            }
        }
        ContextCompat.registerReceiver(
            ctx.applicationContext, receiver, IntentFilter(ACTION_RESULT), PERMISSION, null, ContextCompat.RECEIVER_EXPORTED,
        )
        return try {
            ctx.sendBroadcast(
                Intent(ACTION_DO).setPackage(PACKAGE).putExtra("id", id).putExtra("cmd", cmd).putExtra("arg", arg),
                PERMISSION,
            )
            withTimeoutOrNull(timeoutMs) { done.await() }
        } finally {
            runCatching { ctx.applicationContext.unregisterReceiver(receiver) }
        }
    }

    const val NOT_READY = "For that I need Nova Hands. Open the Nova app and set it up in step 6."
}
