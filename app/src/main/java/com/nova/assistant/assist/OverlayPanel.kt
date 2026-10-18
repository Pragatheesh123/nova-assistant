package com.nova.assistant.assist

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.nova.assistant.AssistActivity
import com.nova.assistant.ui.NovaPanel
import com.nova.assistant.ui.NovaTheme
import com.nova.assistant.wake.WakeService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The floating Nova panel: appears on top of whatever app you're in (needs "Display over other
 * apps"), without opening Nova. Touches outside it still reach the app underneath.
 */
object OverlayPanel {
    private var view: ComposeView? = null
    private var owner: Owner? = null
    private var assistant: Assistant? = null
    private var scope: CoroutineScope? = null

    fun canShow(ctx: Context) = Settings.canDrawOverlays(ctx)

    @SuppressLint("ClickableViewAccessibility")
    fun show(context: Context) {
        val ctx = context.applicationContext
        if (view != null) { assistant?.listen(); return }
        WakeService.pause()
        val wm = ctx.getSystemService(WindowManager::class.java)
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val a = Assistant(ctx, sc) { hide(ctx) }
        val o = Owner().also { it.start() }
        val v = ComposeView(ctx).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setContent {
                NovaTheme {
                    Box(Modifier.padding(horizontal = 10.dp)) {
                        NovaPanel(
                            a, orbSize = 150.dp, onClose = { hide(ctx) },
                            onKeyboard = {
                                hide(ctx)
                                ctx.startActivity(
                                    Intent(ctx, AssistActivity::class.java)
                                        .putExtra(AssistActivity.EXTRA_TYPE, true)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            },
                        )
                    }
                }
            }
            // A tap anywhere outside the panel dismisses it.
            setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_OUTSIDE) hide(ctx); false }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (48 * ctx.resources.displayMetrics.density).toInt()
            windowAnimations = android.R.style.Animation_Toast
        }
        try {
            wm.addView(v, lp)
        } catch (e: Exception) {
            o.stop(); sc.cancel(); a.release(); WakeService.resume()
            return
        }
        view = v; owner = o; assistant = a; scope = sc
        a.listen()
    }

    fun hide(context: Context) {
        val v = view ?: return
        view = null
        runCatching { context.applicationContext.getSystemService(WindowManager::class.java).removeView(v) }
        assistant?.release(); assistant = null
        owner?.stop(); owner = null
        scope?.cancel(); scope = null
        WakeService.resume()
    }

    /** Minimal lifecycle so Compose can run outside an Activity. */
    private class Owner : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

        fun start() {
            saved.performRestore(null)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        fun stop() = registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
}
