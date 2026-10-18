package com.nova.hands

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** One screen: explains Hands and opens the Accessibility settings to switch it on. */
class SetupActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val pearl = Color.parseColor("#F4F1EA")
        val dim = Color.parseColor("#A3F4F1EA")
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#050505"))
        }
        root.addView(TextView(this).apply {
            text = "N O V A   H A N D S"; setTextColor(pearl); textSize = 14f; typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
        })
        status = TextView(this).apply { setTextColor(pearl); textSize = 20f; gravity = Gravity.CENTER; setPadding(0, pad, 0, pad / 2) }
        root.addView(status)
        root.addView(TextView(this).apply {
            setTextColor(dim); textSize = 14f; gravity = Gravity.CENTER
            text = "Lets Nova tap, type, scroll and press Send for you, only when you ask by voice.\n\n" +
                "Turn it on: Accessibility › Installed apps › Nova Hands › On.\n\n" +
                "If the switch is greyed out: open Settings › Apps › Nova Hands, tap ⋮ (top right) › " +
                "Allow restricted settings, then try again."
        })
        root.addView(Button(this).apply {
            text = "Open Accessibility settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = pad })
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        val on = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains("$packageName/", ignoreCase = true) == true
        status.text = if (on) "Active ✓  Nova has hands." else "Not switched on yet"
    }
}
