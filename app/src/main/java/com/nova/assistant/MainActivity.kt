package com.nova.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nova.assistant.assist.NovaInteractionService
import com.nova.assistant.brain.Llm
import com.nova.assistant.ui.Nova
import com.nova.assistant.ui.NovaTheme
import com.nova.assistant.actions.Hands
import com.nova.assistant.assist.OverlayPanel
import com.nova.assistant.ui.HudOrb
import com.nova.assistant.ui.OrbState
import com.nova.assistant.ui.Pill
import com.nova.assistant.voice.Speaker
import com.nova.assistant.wake.WakeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Setup screen. You only need it once; after that Nova lives behind the power button. */
class MainActivity : ComponentActivity() {
    private val prefs by lazy { Prefs(this) }
    private var tick by mutableIntStateOf(0) // bump to refresh statuses
    private var speaker: Speaker? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { NovaTheme { Screen() } }
    }

    override fun onResume() {
        super.onResume()
        tick++
        if (prefs.wakeWord && WakeService.modelReady(this)) WakeService.start(this)
    }

    override fun onDestroy() {
        speaker?.shutdown()
        super.onDestroy()
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private val wantedPerms = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.CALL_PHONE)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    /** Call Nova: the floating panel if allowed, else the full-screen pop-up. */
    private fun summon() {
        if (OverlayPanel.canShow(this)) OverlayPanel.show(this)
        else startActivity(Intent(this, AssistActivity::class.java))
    }

    private fun openAssistantSettings() {
        val tries = listOf(
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent("android.settings.MANAGE_DEFAULT_APPS_SETTINGS"),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (i in tries) if (runCatching { startActivity(i) }.isSuccess) return
    }

    // ------------------------------------------------------------------ UI

    @Composable
    private fun Screen() {
        check(tick >= 0) // read tick so the screen refreshes on resume
        var modelMsg by remember { mutableStateOf("") }
        var voskMsg by remember { mutableStateOf("") }
        var wake by remember { mutableStateOf(prefs.wakeWord) }
        var uk by remember { mutableStateOf(prefs.britishVoice) }
        var copying by remember { mutableStateOf(false) }
        var keyText by remember { mutableStateOf("") }
        var hasKey by remember { mutableStateOf(prefs.claudeKey.isNotBlank() || prefs.groqKey.isNotBlank()) }
        var voiceLabel by remember { mutableStateOf(prefs.voiceName.ifEmpty { "Automatic (British male)" }) }

        val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
        val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            copying = true
            modelMsg = "Copying model…"
            NovaApp.scope.launch {
                val ok = runCatching {
                    Llm.release()
                    val tmp = File(getExternalFilesDir(null), "model.task.copy")
                    contentResolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 20) } }
                    tmp.renameTo(Llm.file(this@MainActivity))
                    val name = uri.lastPathSegment ?: ""
                    prefs.modelUrl = name // remembers whether it's a Gemma model for the prompt format
                }.isSuccess
                withContext(Dispatchers.Main) {
                    copying = false
                    modelMsg = if (ok) "" else "Couldn't copy that file."
                    tick++
                }
            }
        }

        // Watch downloads
        LaunchedEffect(Unit) {
            while (true) {
                Downloads.progress(this@MainActivity, prefs.modelDownloadId)?.let { p ->
                    when {
                        p.running -> modelMsg = "Downloading… " + pct(p.bytes, p.total)
                        p.done -> {
                            val part = File(getExternalFilesDir(null), "model.download")
                            if (part.exists()) { Llm.release(); part.renameTo(Llm.file(this@MainActivity)) }
                            prefs.modelDownloadId = -1; modelMsg = ""; tick++
                        }
                        p.failed -> { prefs.modelDownloadId = -1; modelMsg = "Download failed. Check Wi-Fi, or use “Choose file”." }
                    }
                }
                Downloads.progress(this@MainActivity, prefs.voskDownloadId)?.let { p ->
                    when {
                        p.running -> voskMsg = "Downloading wake word… " + pct(p.bytes, p.total)
                        p.done -> {
                            prefs.voskDownloadId = -1
                            voskMsg = "Installing…"
                            val ok = withContext(Dispatchers.IO) {
                                Downloads.unzipVosk(
                                    this@MainActivity,
                                    File(getExternalFilesDir(null), "vosk.zip"),
                                    WakeService.modelDir(this@MainActivity),
                                )
                            }
                            voskMsg = if (ok) "" else "Couldn't install the wake word. Try again."
                            if (ok && prefs.wakeWord) WakeService.start(this@MainActivity)
                            tick++
                        }
                        p.failed -> { prefs.voskDownloadId = -1; voskMsg = "Download failed. Try again on Wi-Fi." }
                    }
                }
                delay(1000)
            }
        }

        val micOk = granted(Manifest.permission.RECORD_AUDIO)
        val allPerms = wantedPerms.all { granted(it) }
        val isDefault = NovaInteractionService.isDefault(this)
        val modelOk = Llm.installed(this) && prefs.modelDownloadId < 0
        val modelSize = Llm.file(this).length()
        val overlayOk = Settings.canDrawOverlays(this)
        val voskOk = WakeService.modelReady(this)
        val batteryOk = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

        val handsIn = Hands.installed(this)
        val handsOn = Hands.enabled(this)
        val ready = listOf(allPerms, hasKey, overlayOk, isDefault, wake && voskOk, handsIn && handsOn).count { it }

        Column(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF0C0C0C), Nova.Ink, Nova.Ink)))
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp),
        ) {
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("N O V A", style = Nova.mono.copy(color = Nova.Pearl, fontSize = 13.sp, letterSpacing = 3.sp))
                Spacer(Modifier.weight(1f))
                Text("ON-DEVICE · PRIVATE", style = Nova.mono.copy(fontSize = 9.5.sp))
            }
            Box(Modifier.fillMaxWidth().height(310.dp), contentAlignment = Alignment.Center) {
                HudOrb(OrbState.Idle, 0f, Modifier.size(290.dp).clickable { summon() })
            }
            Text(
                "SYSTEMS READY  $ready / 6", style = Nova.mono.copy(color = Nova.Pearl2),
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Tap the core to talk. Everything runs on this phone.",
                style = Nova.body.copy(fontSize = 14.sp), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(26.dp))
            Text("SET UP", style = Nova.mono)
            Spacer(Modifier.height(10.dp))

            Step(
                n = 1, done = allPerms, title = "Allow access",
                text = "Microphone to hear you; contacts and calls so Nova can reach people for you.",
            ) {
                if (!allPerms) Pill("Allow", solid = true, Modifier.fillMaxWidth()) { permLauncher.launch(wantedPerms) }
            }

            Step(
                n = 2, done = hasKey, title = "Free cloud brain · Groq",
                text = if (hasKey) (if (prefs.claudeKey.isNotBlank()) "Using Claude (your paid key). " else "Connected to Groq's free AI. ") +
                    "Real conversation and web search; your words are checked by the leak guard before they're sent. " +
                    "If the free limit runs out, the offline brain takes over."
                else "Free, no card: on console.groq.com sign in, open API Keys, create a key and paste it here. " +
                    "It's stored only on this phone. Optional paid upgrade: a Claude key (starts with sk-ant-).",
            ) {
                if (!hasKey) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(Nova.Surface, RoundedCornerShape(14.dp))
                            .border(1.dp, Nova.Line, RoundedCornerShape(14.dp))
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                    ) {
                        if (keyText.isEmpty()) Text("gsk_…  (or sk-ant-…)", style = Nova.body.copy(color = Nova.Pearl3))
                        BasicTextField(
                            value = keyText, onValueChange = { keyText = it.trim() }, singleLine = true,
                            textStyle = TextStyle(color = Nova.Pearl, fontSize = 15.sp),
                            cursorBrush = SolidColor(Nova.Pearl),
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Pill("Save key", solid = true, Modifier.fillMaxWidth()) {
                        when {
                            keyText.startsWith("sk-ant") -> { prefs.claudeKey = keyText; keyText = ""; hasKey = true }
                            keyText.startsWith("gsk_") -> { prefs.groqKey = keyText; keyText = ""; hasKey = true }
                        }
                    }
                } else {
                    Pill("Remove key", solid = false, Modifier.fillMaxWidth()) {
                        prefs.claudeKey = ""; prefs.groqKey = ""; hasKey = false
                    }
                }
            }

            Step(
                n = 3, done = overlayOk, title = "Floating panel",
                text = if (overlayOk) "Done. Nova appears on top of any app when you call it."
                else "Allow “Display over other apps” so Nova's panel pops up over whatever you're doing.",
            ) {
                if (!overlayOk) Pill("Allow", solid = true, Modifier.fillMaxWidth()) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
            }

            Step(
                n = 4, done = isDefault, title = "Power-button assistant",
                text = if (isDefault) "Done. Hold the power button (or swipe up from a bottom corner) to call Nova."
                else "Choose Nova under “Digital assistant app”. Then holding the power button calls Nova instead of Google.\n" +
                    "Samsung: also Settings › Advanced features › Side button › Press and hold › Digital assistant.",
            ) {
                if (!isDefault) Pill("Open settings", solid = true, Modifier.fillMaxWidth()) { openAssistantSettings() }
            }

            Step(
                n = 5, done = wake && voskOk, title = "“Hey Nova”",
                text = when {
                    voskMsg.isNotEmpty() -> voskMsg
                    !wake -> "Wake Nova hands-free, even with the app closed. Also gives Nova offline ears for the floating panel."
                    !voskOk -> "Needs a small 40 MB download."
                    !batteryOk -> "Listening. Allow background running so Android doesn't stop it."
                    else -> "Listening for “Hey Nova”."
                },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Hands-free", style = Nova.title, modifier = Modifier.weight(1f))
                    Switch(
                        checked = wake,
                        onCheckedChange = { on ->
                            wake = on
                            prefs.wakeWord = on
                            if (on) {
                                if (!micOk) permLauncher.launch(wantedPerms)
                                if (!voskOk && prefs.voskDownloadId < 0) {
                                    prefs.voskDownloadId = Downloads.start(this@MainActivity, Prefs.VOSK_URL, "vosk.zip", "Nova wake word")
                                } else WakeService.start(this@MainActivity)
                            } else WakeService.stop(this@MainActivity)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Nova.Ink, checkedTrackColor = Nova.Pearl,
                            uncheckedThumbColor = Nova.Pearl2, uncheckedTrackColor = Nova.Surface, uncheckedBorderColor = Nova.Line,
                        ),
                    )
                }
                if (wake && !batteryOk) {
                    Spacer(Modifier.height(10.dp))
                    Pill("Run in background", solid = false, Modifier.fillMaxWidth()) {
                        runCatching {
                            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                        }
                    }
                }
            }

            Step(
                n = 6, done = handsIn && handsOn, title = "Nova Hands · Jarvis mode",
                text = when {
                    !handsIn -> "Optional companion app that lets Nova tap, type, scroll and press Send inside other apps. " +
                        "It's separate so Nova itself never gets blocked. Download Nova-Hands.apk from the same page as Nova."
                    !handsOn -> "Turn it on: Accessibility › Installed apps › Nova Hands › On.\n" +
                        "Greyed out? App info › Nova Hands › ⋮ (top right) › Allow restricted settings, then try again."
                    else -> "Active. Try “tap subscribe”, “scroll down” or “read the screen”."
                },
            ) {
                if (!handsIn) Pill("Get Nova Hands", solid = true, Modifier.fillMaxWidth()) {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES))) }
                } else if (!handsOn) Pill("Open Accessibility", solid = true, Modifier.fillMaxWidth()) {
                    runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                }
            }

            Step(n = 7, done = true, title = "Voice", text = "$voiceLabel · works offline") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill("Next voice", solid = false, Modifier.weight(1f)) {
                        val s = speaker ?: Speaker(this@MainActivity) {}.also { speaker = it }
                        val all = s.voices()
                        if (all.isNotEmpty()) {
                            val next = all[(all.indexOfFirst { it.name == prefs.voiceName } + 1) % all.size]
                            s.use(next)
                            voiceLabel = "${next.name} (${next.locale.displayCountry})"
                            s.say("Good evening. This is how I'd sound.")
                        }
                    }
                    Pill("Test voice", solid = true, Modifier.weight(1f)) {
                        val s = speaker ?: Speaker(this@MainActivity) {}.also { speaker = it }
                        s.say("Good evening, sir. All systems are online, and I'm at your service.")
                    }
                }
                Spacer(Modifier.height(10.dp))
                Pill("Auto British male", solid = false, Modifier.fillMaxWidth()) {
                    prefs.voiceName = ""; prefs.britishVoice = true; uk = true
                    voiceLabel = "Automatic (British male)"
                    speaker?.shutdown(); speaker = null
                }
            }

            Step(
                n = 8, done = modelOk, title = "Offline backup brain (optional)",
                text = when {
                    copying -> modelMsg
                    modelMsg.isNotEmpty() -> modelMsg
                    modelOk -> "Installed (${modelSize / 1_000_000} MB). Used when there's no internet."
                    else -> "A small ~1.6 GB model for when you're offline. Optional: Claude is far smarter."
                },
            ) {
                if (!modelOk && prefs.modelDownloadId < 0 && !copying) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Pill("Choose file", solid = false, Modifier.weight(1f)) { pickModel.launch(arrayOf("*/*")) }
                        Pill("Download", solid = true, Modifier.weight(1f)) {
                            prefs.modelUrl = Prefs.DEFAULT_MODEL_URL
                            prefs.modelDownloadId = Downloads.start(this@MainActivity, prefs.modelUrl, "model.download", "Nova AI brain")
                            modelMsg = "Starting download…"
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Pill("Talk to Nova", solid = true, Modifier.fillMaxWidth()) { summon() }
            Spacer(Modifier.height(22.dp))
            Text("TRY SAYING", style = Nova.mono)
            Spacer(Modifier.height(8.dp))
            Text(
                "“Open YouTube and go to MrBeast's channel” · “Play lofi on YouTube” · “Open my WhatsApp chat with Mom” · " +
                    "“WhatsApp Sam saying I'm on my way” · “Call mum” · “Wake me up at 6:30” · “Timer for 10 minutes” · " +
                    "“Remind me in an hour to take the washing out” · “Open insta” · “Torch on” · “Next song” · " +
                    "“Who won the match last night?” · With Nova Hands: “Tap subscribe” · “Scroll down” · “Go back” · “Read the screen”.",
                style = Nova.body.copy(fontSize = 14.sp, lineHeight = 22.sp),
            )
            Spacer(Modifier.height(40.dp))
        }
    }

    companion object {
        const val RELEASES = "https://github.com/Pragatheesh123/nova-assistant/releases/latest"
    }

    private fun pct(b: Long, t: Long) = if (t > 0) "${b * 100 / t}%  (${b / 1_000_000} / ${t / 1_000_000} MB)" else "${b / 1_000_000} MB"
}

@Composable
private fun Step(n: Int, done: Boolean, title: String, text: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .background(Brush.verticalGradient(listOf(Color(0x10FFFFFF), Color(0x05FFFFFF))), RoundedCornerShape(24.dp))
            .border(1.dp, if (done) Nova.Line else Color(0x26FFFFFF), RoundedCornerShape(24.dp))
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(28.dp)
                    .background(if (done) Nova.Pearl else Nova.Ink, CircleShape)
                    .border(1.dp, if (done) Nova.Pearl else Nova.Line, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (done) "✓" else "$n", color = if (done) Nova.Ink else Nova.Pearl2, fontSize = 13.sp)
            }
            Spacer(Modifier.width(12.dp))
            Text(title, style = Nova.title)
        }
        Spacer(Modifier.height(8.dp))
        Text(text, style = Nova.body)
        Spacer(Modifier.height(12.dp))
        content()
    }
}
