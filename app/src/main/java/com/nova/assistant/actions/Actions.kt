package com.nova.assistant.actions

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.nova.assistant.Prefs
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** A command Nova understood. */
sealed class Command {
    data class Call(val who: String) : Command()
    data class Text(val who: String, val body: String) : Command()
    data class WhatsApp(val who: String, val body: String) : Command()
    data class Alarm(val hour: Int, val minute: Int) : Command()
    data class Timer(val seconds: Int) : Command()
    data class Remind(val atMillis: Long, val what: String, val spoken: String) : Command()
    data class Open(val name: String) : Command()
    data class Torch(val on: Boolean) : Command()
    data class Media(val key: Int, val spoken: String) : Command()
    data class Volume(val change: String, val percent: Int = -1) : Command()
    data class Navigate(val place: String) : Command()
    data class Search(val query: String) : Command()
    data class YouTube(val query: String) : Command()
    data class YouTubeChannel(val name: String) : Command()
    data class YouTubePlay(val query: String) : Command()
    data class WhatsAppChat(val who: String) : Command()
    // on-screen control through Nova Hands
    data class Tap(val target: String) : Command()
    data class TypeText(val text: String) : Command()
    data class Scroll(val down: Boolean) : Command()
    data class Global(val action: String) : Command() // back, home, recents, notifications, quick_settings
    object ReadScreen : Command()
    data class Note(val text: String) : Command()
    object ReadNotes : Command()
    object Time : Command()
    object Date : Command()
    object Battery : Command()
    object Camera : Command()
    data class SettingsPanel(val which: String) : Command()
}

/**
 * What happens after a command. [say] is spoken. [launch] is an activity to open (the pop-up closes).
 * [confirm] means "ask the user first, then run this".
 */
data class Outcome(
    val say: String,
    val launch: Intent? = null,
    val confirm: (suspend () -> Outcome)? = null,
    val confirmLabel: String = "",
    val cancelLabel: String = "Cancel",
    val warning: List<String> = emptyList(), // leak guard findings -> red pop-up, auto-abort
    /** Runs after [launch] (e.g. press Send in WhatsApp). A returned string is spoken. */
    val after: (suspend () -> String?)? = null,
)

class Actions(private val ctx: Context) {
    private val prefs = Prefs(ctx)

    fun run(c: Command): Outcome = try {
        when (c) {
            is Command.Call -> call(c.who)
            is Command.Text -> text(c.who, c.body)
            is Command.WhatsApp -> whatsapp(c.who, c.body)
            is Command.Alarm -> alarm(c.hour, c.minute)
            is Command.Timer -> timer(c.seconds)
            is Command.Remind -> remind(c)
            is Command.Open -> open(c.name)
            is Command.Torch -> torch(c.on)
            is Command.Media -> media(c)
            is Command.Volume -> volume(c)
            is Command.Navigate -> Outcome("Getting directions to ${c.place}.", navigateIntent(c.place))
            is Command.Search -> search(c.query)
            is Command.YouTube -> youtube(c.query)
            is Command.YouTubeChannel -> youtubeChannel(c.name)
            is Command.YouTubePlay -> youtubePlay(c.query)
            is Command.WhatsAppChat -> whatsappChat(c.who)
            is Command.Tap -> hands("Tapping ${c.target}.") {
                val r = Hands.call(ctx, "tap", c.target)
                if (r == null || r.startsWith("not found")) "I couldn't find ${c.target} on the screen." else null
            }
            is Command.TypeText -> hands("Typing.") {
                val r = Hands.call(ctx, "type", c.text)
                if (r == null || r.startsWith("not found")) "I couldn't find a text box to type in." else null
            }
            is Command.Scroll -> hands("") { Hands.call(ctx, "scroll", if (c.down) "down" else "up"); null }
            is Command.Global -> hands("") { Hands.call(ctx, "global", c.action); null }
            Command.ReadScreen -> hands("Reading the screen.") {
                Hands.call(ctx, "read")?.takeIf { it.isNotBlank() }?.take(600) ?: "I couldn't read anything on the screen."
            }
            is Command.Note -> {
                val stamp = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date())
                prefs.notes = (prefs.notes + "\n$stamp — ${c.text}").trim()
                Outcome("Noted.")
            }
            Command.ReadNotes -> {
                val last = prefs.notes.lines().filter { it.isNotBlank() }.takeLast(3)
                if (last.isEmpty()) Outcome("You don't have any notes yet.")
                else Outcome("Your latest notes: " + last.joinToString(". ") { it.substringAfter("— ") })
            }
            Command.Time -> Outcome("It's " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()) + ".")
            Command.Date -> Outcome("Today is " + SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date()) + ".")
            Command.Battery -> battery()
            Command.Camera -> Outcome("Opening the camera.", Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
            is Command.SettingsPanel -> settingsPanel(c.which)
        }
    } catch (e: SecurityException) {
        Outcome("I don't have permission for that yet. Open the Nova app and allow it.")
    } catch (e: Exception) {
        Outcome("Sorry, that didn't work.")
    }

    // ------------------------------------------------------------------ contacts

    /** [sure] = the name matched clearly (exact / first name / starts with), not a fuzzy guess. */
    data class Contact(val name: String, val number: String, val sure: Boolean = true)

    private fun has(perm: String) =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    fun findContact(who: String): Contact? {
        val digits = who.filter { it.isDigit() || it == '+' }
        if (digits.count { it.isDigit() } >= 3) return Contact(who, digits)
        if (!has(Manifest.permission.READ_CONTACTS)) return null
        val wanted = who.lowercase().removePrefix("my ").trim()
        val found = mutableListOf<Contact>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            null, null, null,
        )?.use { cur ->
            while (cur.moveToNext()) {
                val name = cur.getString(0) ?: continue
                val num = cur.getString(1) ?: continue
                found += Contact(name, num)
            }
        }
        return found.firstOrNull { it.name.equals(wanted, true) }
            ?: found.firstOrNull { it.name.lowercase().split(" ").firstOrNull() == wanted }
            ?: found.firstOrNull { it.name.lowercase().startsWith(wanted) }
            ?: found.firstOrNull { it.name.lowercase().contains(wanted) }?.copy(sure = false)
            ?: found.minByOrNull { Fuzzy.distance(it.name.lowercase(), wanted) }
                ?.takeIf { Fuzzy.distance(it.name.lowercase(), wanted) <= 2 }?.copy(sure = false)
    }

    private fun noContact(who: String) =
        if (!has(Manifest.permission.READ_CONTACTS)) Outcome("I need access to your contacts first. Open the Nova app to allow it.")
        else Outcome("I couldn't find $who in your contacts.")

    private fun call(who: String): Outcome {
        val c = findContact(who) ?: return noContact(who)
        val uri = Uri.parse("tel:" + Uri.encode(c.number))
        val intent = if (has(Manifest.permission.CALL_PHONE)) Intent(Intent.ACTION_CALL, uri) else Intent(Intent.ACTION_DIAL, uri)
        return Outcome("Calling ${c.name}.", intent)
    }

    private fun text(who: String, body: String): Outcome {
        val c = findContact(who) ?: return noContact(who)
        // Opens your messaging app with the text ready; you tap send. (Sending silently needs
        // the SMS permission, which makes Android's Play Protect block the install.)
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(c.number))).putExtra("sms_body", body)
        return Outcome("Here's your message to ${c.name}. Tap send.", i)
    }

    /** WhatsApp needs the full international number (wa.me/919876543210). */
    private fun intlNumber(raw: String): String {
        val tm = ctx.getSystemService(android.telephony.TelephonyManager::class.java)
        val iso = (tm?.simCountryIso?.takeIf { it.isNotBlank() } ?: tm?.networkCountryIso?.takeIf { it.isNotBlank() }
            ?: Locale.getDefault().country).uppercase()
        val e164 = android.telephony.PhoneNumberUtils.formatNumberToE164(raw, iso)
        return (e164 ?: raw).filter { it.isDigit() }
    }

    private fun whatsappIntent(number: String, body: String?): Intent {
        val url = "https://wa.me/" + intlNumber(number) + (body?.let { "?text=" + Uri.encode(it) } ?: "")
        val app = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.whatsapp")
        return if (app.resolveActivity(ctx.packageManager) != null) app else Intent(Intent.ACTION_VIEW, Uri.parse(url))
    }

    private fun whatsapp(who: String, body: String): Outcome {
        val c = findContact(who) ?: return noContact(who)
        val i = whatsappIntent(c.number, body)
        // With Nova Hands and a clear contact match, press Send for you. Otherwise leave it for you to check.
        if (Hands.ready(ctx) && c.sure) {
            return Outcome("Sending “$body” to ${c.name} on WhatsApp.", i, after = {
                kotlinx.coroutines.delay(1200)
                val r = Hands.call(ctx, "send")
                if (r == "ok") "Sent." else "I opened the chat. Tap send to finish."
            })
        }
        return Outcome("Opening WhatsApp for ${c.name}. Tap send.", i)
    }

    private fun whatsappChat(who: String): Outcome {
        val c = findContact(who) ?: return noContact(who)
        return Outcome("Opening your chat with ${c.name}.", whatsappIntent(c.number, null))
    }

    // ------------------------------------------------------------------ time

    private fun alarm(h: Int, m: Int): Outcome {
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Nova")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m) }
        return Outcome("Alarm set for " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(cal.time) + ".")
    }

    private fun timer(seconds: Int): Outcome {
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Nova")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return Outcome("Timer set for ${Spoken.duration(seconds)}.")
    }

    private fun remind(r: Command.Remind): Outcome {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, (r.atMillis % Int.MAX_VALUE).toInt(),
            Intent(ctx, ReminderReceiver::class.java).putExtra("what", r.what),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.atMillis, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.atMillis, pi)
        return Outcome("Okay, I'll remind you ${r.spoken}.")
    }

    // ------------------------------------------------------------------ apps & web

    private val sites = mapOf(
        "youtube" to "https://youtube.com", "google" to "https://google.com", "gmail" to "https://mail.google.com",
        "instagram" to "https://instagram.com", "netflix" to "https://netflix.com", "chatgpt" to "https://chatgpt.com",
        "twitter" to "https://x.com", "x" to "https://x.com", "facebook" to "https://facebook.com",
        "amazon" to "https://amazon.com", "wikipedia" to "https://wikipedia.org", "reddit" to "https://reddit.com",
    )

    private val nicknames = mapOf(
        "yt" to "youtube", "you tube" to "youtube", "insta" to "instagram", "ig" to "instagram",
        "wa" to "whatsapp", "whats app" to "whatsapp", "whatsup" to "whatsapp", "whatsapp business" to "whatsapp business",
        "fb" to "facebook", "gpay" to "google pay", "g pay" to "google pay", "tg" to "telegram", "snap" to "snapchat",
        "twitter" to "x", "play store" to "play store", "playstore" to "play store", "gmail" to "gmail",
        "maps" to "maps", "google maps" to "maps", "phone pe" to "phonepe", "yt music" to "youtube music",
        "chat gpt" to "chatgpt", "files" to "files", "file manager" to "files", "photos" to "photos", "gallery" to "gallery",
    )
    private val packages = mapOf(
        "youtube" to "com.google.android.youtube", "whatsapp" to "com.whatsapp", "instagram" to "com.instagram.android",
        "facebook" to "com.facebook.katana", "google pay" to "com.google.android.apps.nbu.paisa.user",
        "telegram" to "org.telegram.messenger", "snapchat" to "com.snapchat.android", "x" to "com.twitter.android",
        "play store" to "com.android.vending", "gmail" to "com.google.android.gm", "maps" to "com.google.android.apps.maps",
        "phonepe" to "com.phonepe.app", "paytm" to "net.one97.paytm", "spotify" to "com.spotify.music",
        "chrome" to "com.android.chrome", "youtube music" to "com.google.android.apps.youtube.music",
        "chatgpt" to "com.openai.chatgpt", "netflix" to "com.netflix.mediaclient", "amazon" to "in.amazon.mShop.android.shopping",
        "photos" to "com.google.android.apps.photos", "calculator" to "com.google.android.calculator",
        "clock" to "com.google.android.deskclock", "calendar" to "com.google.android.calendar",
    )

    private fun open(raw: String): Outcome {
        var name = raw.lowercase().removePrefix("up ").removePrefix("the ").removePrefix("my ")
            .removeSuffix(" app").removeSuffix(" application").trim()
        name = nicknames[name] ?: name
        packages[name]?.let { pkg ->
            ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { return Outcome("Opening ${raw.trim()}.", it) }
        }
        when (name) {
            "camera" -> return run(Command.Camera)
            "settings" -> return Outcome("Opening settings.", Intent(Settings.ACTION_SETTINGS))
            "wifi", "wi-fi", "bluetooth" -> return settingsPanel(name)
        }
        val pm = ctx.packageManager
        val apps = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0,
        ).map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        val match = apps.firstOrNull { it.first.equals(name, true) }
            ?: apps.firstOrNull { it.first.lowercase().startsWith(name) }
            ?: apps.firstOrNull { it.first.lowercase().contains(name) }
            ?: apps.minByOrNull { Fuzzy.distance(it.first.lowercase(), name) }
                ?.takeIf { Fuzzy.distance(it.first.lowercase(), name) <= maxOf(1, name.length / 4) }
        if (match != null) {
            val i = pm.getLaunchIntentForPackage(match.second) ?: return Outcome("I can't open ${match.first}.")
            return Outcome("Opening ${match.first}.", i)
        }
        val url = sites[name] ?: if ('.' in name && ' ' !in name) "https://$name" else null
        if (url != null) return Outcome("Opening $name.", Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        return Outcome("I couldn't find an app called $raw.")
    }

    private fun navigateIntent(place: String): Intent {
        val maps = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(place)))
            .setPackage("com.google.android.apps.maps")
        return if (maps.resolveActivity(ctx.packageManager) != null) maps
        else Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(place)))
    }

    private fun search(q: String): Outcome {
        val i = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q)
        val target = if (i.resolveActivity(ctx.packageManager) != null) i
        else Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q)))
        return Outcome("Searching for $q.", target)
    }

    private fun youtube(q: String): Outcome {
        val app = Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra("query", q)
        val i = if (app.resolveActivity(ctx.packageManager) != null) app
        else Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q)))
        return Outcome("Finding $q on YouTube.", i)
    }

    private fun youtubeChannel(name: String): Outcome {
        // YouTube search filtered to channels; the channel is the first result.
        val url = "https://www.youtube.com/results?search_query=" + Uri.encode(name) + "&sp=EgIQAg%253D%253D"
        val app = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.google.android.youtube")
        val i = if (app.resolveActivity(ctx.packageManager) != null) app else Intent(Intent.ACTION_VIEW, Uri.parse(url))
        return Outcome("Here's the $name channel on YouTube.", i)
    }

    private fun youtubePlay(q: String): Outcome {
        val base = youtube(q)
        if (!Hands.ready(ctx)) return base
        return base.copy(say = "Playing $q.", after = {
            val r = Hands.call(ctx, "play_first")
            if (r == "ok") null else "Here are the results for $q."
        })
    }

    /** On-screen control needs Nova Hands. */
    private fun hands(say: String, block: suspend () -> String?): Outcome =
        if (!Hands.ready(ctx)) Outcome(Hands.NOT_READY) else Outcome(say, after = block)

    private fun settingsPanel(which: String): Outcome {
        val i = when {
            which.startsWith("wi") && Build.VERSION.SDK_INT >= 29 -> Intent(Settings.Panel.ACTION_WIFI)
            which.startsWith("wi") -> Intent(Settings.ACTION_WIFI_SETTINGS)
            which.startsWith("blue") -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            which.startsWith("data") || which.startsWith("internet") ->
                if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY) else Intent(Settings.ACTION_WIRELESS_SETTINGS)
            else -> Intent(Settings.ACTION_SETTINGS)
        }
        // Android doesn't let apps flip Wi-Fi/Bluetooth directly any more, so show the quick panel.
        return Outcome("Here you go.", i)
    }

    // ------------------------------------------------------------------ device

    private fun torch(on: Boolean): Outcome {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return Outcome("This phone doesn't have a torch.")
        cm.setTorchMode(id, on)
        return Outcome(if (on) "Torch on." else "Torch off.")
    }

    private fun media(c: Command.Media): Outcome {
        val am = ctx.getSystemService(AudioManager::class.java)
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, c.key))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, c.key))
        return Outcome(c.spoken)
    }

    private fun volume(c: Command.Volume): Outcome {
        val am = ctx.getSystemService(AudioManager::class.java)
        val s = AudioManager.STREAM_MUSIC
        val max = am.getStreamMaxVolume(s)
        when (c.change) {
            "up" -> repeat(2) { am.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI) }
            "down" -> repeat(2) { am.adjustStreamVolume(s, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI) }
            "mute" -> am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            "unmute" -> am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
            "set" -> am.setStreamVolume(s, (max * c.percent.coerceIn(0, 100) / 100f).toInt(), AudioManager.FLAG_SHOW_UI)
        }
        return Outcome(
            when (c.change) {
                "set" -> "Volume at ${c.percent} percent."
                "mute" -> "Muted."
                else -> "Done."
            },
        )
    }

    private fun battery(): Outcome {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        return Outcome("You're at $pct percent" + (if (charging) " and charging." else "."))
    }

    companion object {
        fun safeStart(ctx: Context, i: Intent): Boolean = try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }
}

object Fuzzy {
    fun distance(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }
}

object Spoken {
    fun duration(s: Int): String {
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        val parts = buildList {
            if (h > 0) add("$h hour" + if (h > 1) "s" else "")
            if (m > 0) add("$m minute" + if (m > 1) "s" else "")
            if (sec > 0) add("$sec second" + if (sec > 1) "s" else "")
        }
        return parts.joinToString(" and ").ifEmpty { "0 seconds" }
    }
}
