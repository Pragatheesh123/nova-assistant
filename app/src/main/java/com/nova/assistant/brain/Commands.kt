package com.nova.assistant.brain

import android.view.KeyEvent
import com.nova.assistant.actions.Command
import java.util.Calendar

/** Instant, offline understanding of everyday commands. Returns null if it isn't a command. */
object Commands {

    fun parse(input: String): Command? {
        val t = normalise(input)
        if (t.isEmpty()) return null
        // Message/note text keeps the capitalisation you spoke with.
        fun keep(lower: String): String {
            val i = input.lowercase().replace("’", "'").lastIndexOf(lower)
            val s = if (i >= 0) input.substring(i, i + lower.length) else lower
            return s.replaceFirstChar { it.uppercase() }
        }

        // --- calls & messages
        Regex("^(?:call|ring|phone|dial) (.+?)(?: (?:on|using) (?:speaker|loudspeaker))?$").find(t)?.let {
            return Command.Call(it.groupValues[1])
        }
        Regex("^(?:send )?(?:a )?(?:whatsapp|whats app)(?: message)? (?:to )?(.+?) (?:saying|that|says) (.+)$").find(t)?.let {
            return Command.WhatsApp(it.groupValues[1], keep(it.groupValues[2]))
        }
        Regex("^(?:open |show |go to )?(?:my )?(?:whatsapp|whats app) chat (?:with|of|for) (.+)$").find(t)?.let {
            return Command.WhatsAppChat(it.groupValues[1])
        }
        Regex("^(?:open|show|go to) (?:my )?(.+?)(?:(?:'s|s')? (?:whatsapp|whats app) chat|(?:'s|s') (?:whatsapp|whats app))$").find(t)?.let {
            return Command.WhatsAppChat(it.groupValues[1])
        }
        Regex("^(?:open|show|go to) (?:my )?chat (?:with|of) (.+?)(?: on whatsapp)?$").find(t)?.let {
            return Command.WhatsAppChat(it.groupValues[1])
        }
        Regex("^(?:whatsapp|message) (.+?)(?: on whatsapp)?$").find(t)?.takeIf { "saying" !in t && " that " !in t }?.let {
            if (it.groupValues[1] !in setOf("open", "chat")) return Command.WhatsAppChat(it.groupValues[1])
        }
        Regex("^(?:text|message|sms|send (?:a )?(?:text|message|sms)(?: to)?) (.+?) (?:saying|that|says) (.+)$").find(t)?.let {
            return Command.Text(it.groupValues[1], keep(it.groupValues[2]))
        }
        Regex("^(?:tell) (.+?) (?:that )(.+)$").find(t)?.let {
            return Command.Text(it.groupValues[1], keep(it.groupValues[2]))
        }

        // --- alarms, timers, reminders
        Regex("^(?:set (?:an |a )?alarm|wake me(?: up)?|alarm)(?: for| at)? (.+)$").find(t)?.let { m ->
            clock(m.groupValues[1])?.let { return Command.Alarm(it.first, it.second) }
        }
        Regex("(?:timer|countdown)(?: for)? (.+)$").find(t)?.let { m ->
            seconds(m.groupValues[1])?.let { return Command.Timer(it) }
        }
        Regex("^(?:set (?:a |an )?)?(.+?) timer$").find(t)?.let { m ->
            seconds(m.groupValues[1])?.let { return Command.Timer(it) }
        }
        Regex("^remind me (in .+?|at .+?|tomorrow at .+?) (?:to|that|about) (.+)$").find(t)?.let { m ->
            remindAt(m.groupValues[1])?.let { return Command.Remind(it, keep(m.groupValues[2]), m.groupValues[1]) }
        }
        Regex("^remind me (?:to|that|about) (.+?) (in .+|at .+|tomorrow at .+)$").find(t)?.let { m ->
            remindAt(m.groupValues[2])?.let { return Command.Remind(it, keep(m.groupValues[1]), m.groupValues[2]) }
        }

        // --- on-screen control (Nova Hands)
        when {
            Regex("^scroll( down)?$|^scroll down( a bit)?$|^page down$").matches(t) -> return Command.Scroll(true)
            Regex("^scroll up( a bit)?$|^page up$").matches(t) -> return Command.Scroll(false)
            Regex("^(go )?back$|^press back$").matches(t) -> return Command.Global("back")
            Regex("^(go )?(to )?(the )?home( screen)?$").matches(t) -> return Command.Global("home")
            Regex("^(show |open )?(recent apps|recents)$").matches(t) -> return Command.Global("recents")
            Regex("^(show|open|pull down)( my| the)? notifications$").matches(t) -> return Command.Global("notifications")
            Regex("^(open|show)( the)? quick settings$").matches(t) -> return Command.Global("quick_settings")
            Regex("^(read (the |my )?screen|read (this|it) (out|to me)|what('s| is) on (the |my )?screen)$").matches(t) -> return Command.ReadScreen
        }

        Regex("^(?:tap|click|press|hit|select|touch)(?: on)?(?: the)? (.+?)(?: button| icon| tab| option)?$").find(t)?.let {
            return Command.Tap(keep(it.groupValues[1]))
        }
        Regex("^(?:type|enter|write)(?: in)? (.+)$").find(t)?.let { return Command.TypeText(keep(it.groupValues[1])) }

        // --- torch
        if (Regex("\\b(torch|flashlight|flash ?light|flash)\\b").containsMatchIn(t)) {
            if (Regex("\\b(off|disable|stop)\\b").containsMatchIn(t)) return Command.Torch(false)
            if (Regex("\\b(on|enable|start)\\b").containsMatchIn(t)) return Command.Torch(true)
        }

        // --- media & volume
        when {
            Regex("^(pause|stop)( the)?( music| song| video| playback)?$").matches(t) ->
                return Command.Media(KeyEvent.KEYCODE_MEDIA_PAUSE, "Paused.")
            Regex("^(resume|play|continue|unpause)( the)?( music| song| video| playback)?$").matches(t) ->
                return Command.Media(KeyEvent.KEYCODE_MEDIA_PLAY, "Playing.")
            Regex("^(next|skip)( this)?( song| track)?$").matches(t) || t == "play the next song" ->
                return Command.Media(KeyEvent.KEYCODE_MEDIA_NEXT, "Skipping.")
            Regex("^(previous|last)( song| track)?$|^go back a (song|track)$").matches(t) ->
                return Command.Media(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "Going back.")
        }
        Regex("volume (?:to )?(\\d{1,3})(?: percent| %)?").find(t)?.let {
            return Command.Volume("set", it.groupValues[1].toInt())
        }
        when {
            Regex("\\b(volume up|turn (it )?up|louder|increase (the )?volume)\\b").containsMatchIn(t) -> return Command.Volume("up")
            Regex("\\b(volume down|turn (it )?down|quieter|lower (the )?volume|decrease (the )?volume)\\b").containsMatchIn(t) -> return Command.Volume("down")
            Regex("^(unmute)( the)?( volume| sound| phone)?$").matches(t) -> return Command.Volume("unmute")
            Regex("^(mute)( the)?( volume| sound| phone)?$").matches(t) -> return Command.Volume("mute")
            Regex("^(max(imum)? volume|volume max(imum)?|full volume)$").matches(t) -> return Command.Volume("set", 100)
        }

        // --- maps, search, youtube
        Regex("^(?:navigate|directions|take me|drive|get me|show me the way|how do i get) (?:to )?(.+)$").find(t)?.let {
            return Command.Navigate(it.groupValues[1])
        }
        Regex("^(?:open |go to |launch )?(?:youtube|yt) (?:and |then )?(?:go to|open|show(?: me)?|find) (.+?)(?:'s|s')? (?:youtube )?channel$").find(t)?.let {
            return Command.YouTubeChannel(it.groupValues[1])
        }
        Regex("^(?:go to|open|show(?: me)?|find) (.+?)(?:'s|s')? (?:youtube |yt )?channel(?: on (?:youtube|yt))?$").find(t)?.let {
            return Command.YouTubeChannel(it.groupValues[1])
        }
        Regex("^(?:open |go to |launch )?(?:youtube|yt) (?:and |then )?(?:search|look up|find)(?: for)? (.+)$").find(t)?.let {
            return Command.YouTube(it.groupValues[1])
        }
        Regex("^play (.+?) on (?:youtube|yt)$").find(t)?.let { return Command.YouTubePlay(it.groupValues[1]) }
        Regex("^(?:open |go to )?(?:youtube|yt) (?:and |then )?play (.+)$").find(t)?.let { return Command.YouTubePlay(it.groupValues[1]) }
        Regex("^(?:youtube|search youtube for) (.+)$").find(t)?.let { return Command.YouTube(it.groupValues[1]) }
        Regex("^(?:search|google|look up|search for|search the web for) (.+)$").find(t)?.let {
            return Command.Search(it.groupValues[1].removePrefix("for "))
        }

        // --- apps & settings
        Regex("^(?:open|launch|start|go to|show me|show) (?:the )?(wi-?fi|bluetooth|mobile data|internet)(?: settings)?$").find(t)?.let {
            return Command.SettingsPanel(it.groupValues[1])
        }
        Regex("^turn (?:on|off) (?:the )?(wi-?fi|bluetooth|mobile data|internet)$").find(t)?.let {
            return Command.SettingsPanel(it.groupValues[1])
        }
        if (Regex("^(take a (photo|picture|selfie|pic)|open (the )?camera)$").matches(t)) return Command.Camera
        Regex("^(?:open|launch|start|run) (.+)$").find(t)?.let { return Command.Open(it.groupValues[1]) }

        // --- notes
        Regex("^(?:take a note|make a note|add a note|note down|note|write down|remember)(?: that| to)? (.+)$").find(t)?.let {
            return Command.Note(keep(it.groupValues[1]))
        }
        if (Regex("(read|what are|show|tell me) (me )?my notes").containsMatchIn(t)) return Command.ReadNotes

        // --- quick facts
        if (Regex("^(what('s| is) the time|what time is it|time|tell me the time|what time)$").matches(t)) return Command.Time
        if (Regex("^(what('s| is) (the date|today('s date)?)|what day is it( today)?|date|today's date)$").matches(t)) return Command.Date
        if (Regex("\\bbattery\\b").containsMatchIn(t)) return Command.Battery

        return null
    }

    // ------------------------------------------------------------------ helpers

    private fun normalise(s: String) = s.lowercase()
        .replace(Regex("^(hey |ok |okay |hi )?nova[,!.]?\\s*"), "")
        .replace(Regex("^(can you|could you|would you|will you|please|can u|i want you to|i need you to)\\s+"), "")
        .replace(Regex("^(please)\\s+"), "")
        .replace(Regex("^open up\\s+"), "open ")
        .replace(Regex("[?!.,]+$"), "")
        .replace(Regex("\\s+please$"), "")
        .replace("’", "'")
        .trim()

    private val numberWords = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "fifteen" to 15,
        "twenty" to 20, "thirty" to 30, "forty" to 40, "forty five" to 45, "fifty" to 50, "sixty" to 60, "ninety" to 90,
    )

    private fun num(s: String): Int? = s.trim().toIntOrNull() ?: numberWords[s.trim()]

    /** "5 minutes", "1 hour and 30 minutes", "half an hour", "90 seconds" -> seconds */
    fun seconds(s: String): Int? {
        val t = s.trim()
        if (Regex("^half an? hour$").matches(t)) return 1800
        if (Regex("^(a )?quarter of an hour$").matches(t)) return 900
        var total = 0
        var any = false
        Regex("(\\d+|[a-z]+(?: [a-z]+)?) (hours?|hrs?|minutes?|mins?|seconds?|secs?)").findAll(t).forEach { m ->
            val words = m.groupValues[1]
            val n = num(words) ?: num(words.substringAfterLast(' ')) ?: return@forEach
            any = true
            total += n * when {
                m.groupValues[2].startsWith("h") -> 3600
                m.groupValues[2].startsWith("m") -> 60
                else -> 1
            }
        }
        if (Regex("and a half").containsMatchIn(t) && any) total += if (t.contains("hour")) 1800 else 30
        return if (any && total > 0) total else null
    }

    /** "7", "7:30", "7 30 am", "half past 6", "6 pm", "18:00", "noon" -> (hour, minute) */
    fun clock(raw: String): Pair<Int, Int>? {
        val s = raw.trim().replace(".", "").replace("a m", "am").replace("p m", "pm")
            .replace(" o'clock", "").replace(" oclock", "").removeSuffix(" tomorrow").removeSuffix(" tonight")
        if (s == "noon" || s == "midday") return 12 to 0
        if (s == "midnight") return 0 to 0
        Regex("^half past (\\w+)(?: (am|pm|in the morning|in the evening|at night))?$").find(s)?.let {
            val h = num(it.groupValues[1]) ?: return null
            return ampm(h, 30, it.groupValues[2])
        }
        Regex("^quarter past (\\w+)(?: (am|pm))?$").find(s)?.let {
            val h = num(it.groupValues[1]) ?: return null
            return ampm(h, 15, it.groupValues[2])
        }
        Regex("^(\\d{1,2})(?:[: ](\\d{2}))? ?(am|pm|in the morning|in the evening|in the afternoon|at night)?$").find(s)?.let {
            val h = it.groupValues[1].toInt()
            val m = it.groupValues[2].ifEmpty { "0" }.toInt()
            if (h > 23 || m > 59) return null
            return ampm(h, m, it.groupValues[3])
        }
        Regex("^(\\w+)(?: (am|pm|in the morning|in the evening|at night))?$").find(s)?.let {
            val h = num(it.groupValues[1]) ?: return null
            return ampm(h, 0, it.groupValues[2])
        }
        return null
    }

    private fun ampm(h: Int, m: Int, suffix: String): Pair<Int, Int> {
        val pm = suffix == "pm" || suffix.contains("evening") || suffix.contains("afternoon") || suffix.contains("night")
        val am = suffix == "am" || suffix.contains("morning")
        val hour = when {
            pm && h < 12 -> h + 12
            am && h == 12 -> 0
            else -> h
        }
        return hour to m
    }

    private fun remindAt(s: String): Long? {
        val now = System.currentTimeMillis()
        if (s.startsWith("in ")) return seconds(s.removePrefix("in "))?.let { now + it * 1000L }
        val tomorrow = s.startsWith("tomorrow")
        val (h, m) = clock(s.removePrefix("tomorrow ").removePrefix("at ")) ?: return null
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0)
            if (tomorrow || timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }
}
