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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nova.assistant.assist.NovaInteractionService
import com.nova.assistant.brain.Llm
import com.nova.assistant.ui.Nova
import com.nova.assistant.ui.NovaTheme
import com.nova.assistant.ui.Orb
import com.nova.assistant.ui.OrbState
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
        add(Manifest.permission.SEND_SMS)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

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

        Column(
            Modifier
                .fillMaxSize()
                .background(Nova.Ink)
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp),
        ) {
            Spacer(Modifier.height(18.dp))
            Text("NOVA", style = Nova.wordmark)
            Box(Modifier.fillMaxWidth().height(250.dp), contentAlignment = Alignment.Center) {
                Orb(OrbState.Idle, 0f, Modifier.size(240.dp).clickable { startActivity(Intent(this@MainActivity, AssistActivity::class.java)) })
            }
            Text(
                "Your private assistant. Everything runs on this phone.",
                style = Nova.body, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(26.dp))
            Text("SET UP", style = Nova.kicker)
            Spacer(Modifier.height(10.dp))

            Step(
                n = 1, done = allPerms, title = "Allow access",
                text = "Microphone to hear you; contacts, calls and texts so Nova can reach people for you.",
            ) {
                if (!allPerms) Pill("Allow", solid = true, Modifier.fillMaxWidth()) { permLauncher.launch(wantedPerms) }
            }

            Step(
                n = 2, done = isDefault, title = "Make Nova your assistant",
                text = if (isDefault) "Done. Hold the power button (or swipe up from a bottom corner) to call Nova."
                else "Choose Nova under “Digital assistant app”. Then holding the power button calls Nova instead of Google.\n" +
                    "Samsung: also set Settings › Advanced features › Side button › Press and hold › Digital assistant.",
            ) {
                if (!isDefault) Pill("Open settings", solid = true, Modifier.fillMaxWidth()) { openAssistantSettings() }
            }

            Step(
                n = 3, done = modelOk, title = "AI brain",
                text = when {
                    copying -> modelMsg
                    modelMsg.isNotEmpty() -> modelMsg
                    modelOk -> "Installed (${modelSize / 1_000_000} MB). Nova can chat and understand anything."
                    else -> "A ~1.6 GB AI model that runs on your phone. Best on Wi-Fi. Commands work without it."
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

            Step(
                n = 4, done = wake && voskOk && overlayOk, title = "“Hey Nova”",
                text = when {
                    voskMsg.isNotEmpty() -> voskMsg
                    !wake -> "Optional. Wake Nova hands-free by voice. Uses a little battery."
                    !voskOk -> "Needs a small 40 MB wake-word download."
                    !overlayOk -> "Allow “Display over other apps” so Nova can pop up when you call."
                    !batteryOk -> "Listening. Tip: allow Nova to run in the background so Android doesn't stop it."
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
                if (wake && !overlayOk) {
                    Spacer(Modifier.height(10.dp))
                    Pill("Allow pop-up", solid = true, Modifier.fillMaxWidth()) {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    }
                }
                if (wake && overlayOk && !batteryOk) {
                    Spacer(Modifier.height(10.dp))
                    Pill("Run in background", solid = false, Modifier.fillMaxWidth()) {
                        runCatching {
                            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                        }
                    }
                }
            }

            Step(n = 5, done = true, title = "Voice", text = if (uk) "British English" else "American English") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill(if (uk) "Switch to US" else "Switch to UK", solid = false, Modifier.weight(1f)) {
                        uk = !uk; prefs.britishVoice = uk
                        speaker?.shutdown(); speaker = null
                    }
                    Pill("Test voice", solid = true, Modifier.weight(1f)) {
                        val s = speaker ?: Speaker(this@MainActivity) {}.also { speaker = it }
                        s.say("Good to meet you. I'm Nova, and I live right here on your phone.")
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Pill("Talk to Nova now", solid = true, Modifier.fillMaxWidth()) {
                startActivity(Intent(this@MainActivity, AssistActivity::class.java))
            }
            Spacer(Modifier.height(18.dp))
            Text("TRY SAYING", style = Nova.kicker)
            Spacer(Modifier.height(8.dp))
            Text(
                "“Call mum” · “Text Sam saying I'm on my way” · “Wake me up at 6:30” · “Timer for 10 minutes” · " +
                    "“Remind me in an hour to take the washing out” · “Open WhatsApp” · “Torch on” · “Next song” · " +
                    "“Volume 40” · “Navigate to the airport” · “Play lofi on YouTube” · “Take a note…” · " +
                    "“What's my battery?” · and anything else once the AI brain is installed.",
                style = Nova.body.copy(fontSize = 14.sp, lineHeight = 22.sp),
            )
            Spacer(Modifier.height(40.dp))
        }
    }

    private fun pct(b: Long, t: Long) = if (t > 0) "${b * 100 / t}%  (${b / 1_000_000} / ${t / 1_000_000} MB)" else "${b / 1_000_000} MB"
}

@Composable
private fun Step(n: Int, done: Boolean, title: String, text: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .background(Nova.Surface, RoundedCornerShape(24.dp))
            .border(1.dp, Nova.Line, RoundedCornerShape(24.dp))
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
