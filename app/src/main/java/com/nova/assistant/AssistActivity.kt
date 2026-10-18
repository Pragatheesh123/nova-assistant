package com.nova.assistant

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.nova.assistant.assist.Assistant
import com.nova.assistant.ui.Nova
import com.nova.assistant.ui.NovaPanel
import com.nova.assistant.ui.NovaTheme
import com.nova.assistant.wake.WakeService

/** Full-screen host for the Nova panel (power-button / in-app). Typing lives here. */
class AssistActivity : ComponentActivity() {
    private lateinit var a: Assistant
    private var typing by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        WakeService.pause()
        a = Assistant(this, lifecycleScope) { finish() }
        typing = intent.getBooleanExtra(EXTRA_TYPE, false)
        setContent { NovaTheme { Screen() } }
        if (!typing) a.listen()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        typing = intent.getBooleanExtra(EXTRA_TYPE, false)
        if (!typing) a.listen()
    }

    override fun onDestroy() {
        a.release()
        WakeService.resume()
        super.onDestroy()
    }

    @Composable
    private fun Screen() {
        val none = remember { MutableInteractionSource() }
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0x33000000), Color(0xCC000000))))
                .clickable(interactionSource = none, indication = null) { finish() },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(Modifier.padding(horizontal = 12.dp).navigationBarsPadding().imePadding().padding(bottom = 12.dp)) {
                NovaPanel(
                    a, orbSize = 190.dp, onClose = { finish() },
                    onKeyboard = { a.hold(); typing = !typing },
                ) {
                    if (typing) TypeBox { text -> typing = false; a.ask(text) }
                }
            }
        }
    }

    @Composable
    private fun TypeBox(onSend: (String) -> Unit) {
        var value by remember { mutableStateOf("") }
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        Box(
            Modifier
                .padding(top = 14.dp)
                .fillMaxWidth()
                .background(Nova.Surface, RoundedCornerShape(99.dp))
                .border(1.dp, Nova.Line, RoundedCornerShape(99.dp))
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            if (value.isEmpty()) Text("Type to Nova…", style = Nova.body.copy(color = Nova.Pearl3))
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                textStyle = TextStyle(color = Nova.Pearl, fontSize = 16.sp),
                cursorBrush = SolidColor(Nova.Pearl),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (value.isNotBlank()) onSend(value.trim()) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
    }

    companion object {
        const val EXTRA_TYPE = "type"
    }
}
