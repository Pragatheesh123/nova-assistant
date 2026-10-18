package com.nova.assistant.actions

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.nova.assistant.NovaApp
import com.nova.assistant.R

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33
        ) return
        val what = intent.getStringExtra("what") ?: "Reminder"
        val n = Notification.Builder(context, NovaApp.CH_REMIND)
            .setSmallIcon(R.drawable.ic_stat_nova)
            .setContentTitle("Nova reminder")
            .setContentText(what.replaceFirstChar { it.uppercase() })
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(what.hashCode(), n)
    }
}
