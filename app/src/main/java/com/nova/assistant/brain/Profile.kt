package com.nova.assistant.brain

import android.content.Context
import com.nova.assistant.Prefs
import org.json.JSONArray
import org.json.JSONObject

/** What Nova remembers about you, and your routines. Stored only on the phone. */
class Profile(ctx: Context) {
    private val prefs = Prefs(ctx)

    private fun load(): JSONObject =
        runCatching { JSONObject(prefs.profile) }.getOrDefault(JSONObject())
            .apply { if (!has("facts")) put("facts", JSONArray()); if (!has("routines")) put("routines", JSONArray()) }

    private fun JSONArray.strings() = (0 until length()).map { getString(it) }

    fun facts(): List<String> = load().getJSONArray("facts").strings()

    fun routines(): List<Triple<String, String, List<String>>> {
        val a = load().getJSONArray("routines")
        return (0 until a.length()).map {
            val r = a.getJSONObject(it)
            Triple(r.getString("name"), r.getString("trigger"), r.getJSONArray("steps").strings())
        }
    }

    fun remember(fact: String): String {
        val p = load()
        val f = (p.getJSONArray("facts").strings() + fact.trim()).distinct().takeLast(60)
        p.put("facts", JSONArray(f)); prefs.profile = p.toString()
        return "Remembered."
    }

    fun forget(about: String): String {
        val p = load()
        val before = p.getJSONArray("facts").strings()
        val after = before.filterNot { it.contains(about, ignoreCase = true) }
        p.put("facts", JSONArray(after)); prefs.profile = p.toString()
        return "Forgot ${before.size - after.size} thing(s)."
    }

    fun saveRoutine(name: String, trigger: String, steps: List<String>): String {
        val p = load()
        val keep = JSONArray()
        routines().filterNot { it.first.equals(name, true) }.forEach { (n, t, s) ->
            keep.put(JSONObject().put("name", n).put("trigger", t).put("steps", JSONArray(s)))
        }
        keep.put(JSONObject().put("name", name.trim()).put("trigger", trigger.trim()).put("steps", JSONArray(steps.filter { it.isNotBlank() })))
        p.put("routines", keep); prefs.profile = p.toString()
        return "Routine '$name' saved with ${steps.size} steps. Say \"$trigger\" to run it."
    }

    fun deleteRoutine(name: String): String {
        val before = routines()
        val p = load()
        val keep = JSONArray()
        before.filterNot { it.first.equals(name, true) }.forEach { (n, t, s) ->
            keep.put(JSONObject().put("name", n).put("trigger", t).put("steps", JSONArray(s)))
        }
        p.put("routines", keep); prefs.profile = p.toString()
        return if (keep.length() < before.size) "Deleted." else "No routine called $name."
    }

    fun routineSteps(name: String): String {
        val r = routines().firstOrNull { it.first.equals(name, true) }
            ?: return "No routine called $name. Known: ${routines().joinToString { it.first }.ifEmpty { "none" }}."
        return "Carry out these steps now, in order, using your tools:\n" +
            r.third.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")
    }

    fun describe(): String = buildString {
        val f = facts()
        if (f.isNotEmpty()) append("What you know about the user:\n").append(f.joinToString("\n") { "- $it" })
        val r = routines()
        if (r.isNotEmpty()) {
            if (isNotEmpty()) append("\n\n")
            append("The user's routines (run with run_routine when they say the trigger or ask):\n")
            append(r.joinToString("\n") { (n, t, s) -> "- $n (trigger: \"$t\"): " + s.joinToString(" → ") })
        }
    }
}
