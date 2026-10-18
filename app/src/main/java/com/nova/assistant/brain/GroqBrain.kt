package com.nova.assistant.brain

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Nova's free cloud brain: open models on Groq's free tier (OpenAI-compatible API).
 * When a model hits its free limit, the next one takes over. Returns null when all are used up
 * (or offline) so the caller can fall back to the on-device brain.
 */
class GroqBrain(private val key: String, private val url: String = URL) {
    class LimitReached : Exception()

    private val http = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()
    private val json = ObjectMapper()
    private val history = ArrayList<Pair<String, String>>()

    private fun post(body: ObjectNode): JsonNode {
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer $key")
            .post(json.writeValueAsString(body).toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { r ->
            if (r.code == 429 || r.code == 413) throw LimitReached()
            if (r.code == 401) throw SecurityException("bad key")
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
            return json.readTree(r.body!!.string())["choices"][0]["message"]
        }
    }

    private fun toolsJson(tools: List<ClaudeBrain.ToolSpec>): ArrayNode {
        val arr = json.createArrayNode()
        for (t in tools + SEARCH) {
            val fn = json.createObjectNode().put("name", t.name).put("description", t.description)
            val params = json.createObjectNode().put("type", "object")
            params.set<JsonNode>("properties", json.valueToTree(t.props))
            params.set<JsonNode>("required", json.valueToTree(t.required))
            fn.set<JsonNode>("parameters", params)
            arr.add(json.createObjectNode().put("type", "function").set<JsonNode>("function", fn))
        }
        return arr
    }

    /** A web question, answered by a Groq model with built-in browser search. */
    private fun search(query: String): String? {
        for (model in SEARCH_MODELS) {
            try {
                val body = json.createObjectNode().put("model", model).put("max_completion_tokens", 1024).put("reasoning_effort", "low")
                body.set<JsonNode>("tools", json.createArrayNode().add(json.createObjectNode().put("type", "browser_search")))
                body.put("tool_choice", "auto")
                body.set<JsonNode>("messages", json.createArrayNode().add(
                    json.createObjectNode().put("role", "user").put("content", "Search the web and give the key facts, with the source sites, for: $query"),
                ))
                return post(body)["content"]?.asText()?.takeIf { it.isNotBlank() }
            } catch (e: Exception) { /* try the next one */ }
        }
        return null
    }

    /**
     * One turn. [run] executes a phone tool; [fallbackSearch] is used if Groq's search fails.
     * Returns the spoken reply, or null if Groq can't be used right now.
     */
    suspend fun think(
        system: String,
        userText: String,
        tools: List<ClaudeBrain.ToolSpec>,
        thorough: Boolean,
        fallbackSearch: suspend (String) -> String,
        run: suspend (String, Map<String, Any?>) -> String,
    ): String? = withContext(Dispatchers.IO) {
        val msgs = json.createArrayNode()
        msgs.add(json.createObjectNode().put("role", "system").put("content", system))
        for ((u, a) in history) {
            msgs.add(json.createObjectNode().put("role", "user").put("content", u))
            msgs.add(json.createObjectNode().put("role", "assistant").put("content", a))
        }
        msgs.add(json.createObjectNode().put("role", "user").put("content", userText))
        val toolArr = toolsJson(tools)
        val now = System.currentTimeMillis()
        val models = MODELS.filter { (resting[it] ?: 0L) < now }.ifEmpty { MODELS }
        var mi = 0
        var steps = 0
        while (steps < 12) {
            val model = models[mi]
            val body = json.createObjectNode().put("model", model).put("temperature", 0.6).put("max_completion_tokens", 1024)
            body.set<JsonNode>("messages", msgs)
            body.set<JsonNode>("tools", toolArr)
            body.put("tool_choice", "auto")
            if (model.startsWith("openai/gpt-oss")) body.put("reasoning_effort", if (thorough) "medium" else "low")
            val msg = try {
                post(body)
            } catch (e: LimitReached) {
                resting[model] = System.currentTimeMillis() + 60_000 // rest it for a minute
                mi++
                if (mi >= models.size) return@withContext null
                continue
            } catch (e: SecurityException) {
                return@withContext "My Groq key isn't working. Check it in the Nova app."
            } catch (e: Exception) {
                return@withContext null
            }
            steps++
            val calls = msg["tool_calls"]
            val keep = json.createObjectNode().put("role", "assistant")
            keep.set<JsonNode>("content", msg["content"] ?: json.nullNode())
            if (calls != null && calls.size() > 0) keep.set<JsonNode>("tool_calls", calls)
            msgs.add(keep)
            if (calls == null || calls.size() == 0) {
                val reply = msg["content"]?.asText().orEmpty().trim().ifEmpty { "Done." }
                history += userText to reply
                while (history.size > 6) history.removeAt(0)
                return@withContext reply
            }
            for (call in calls) {
                val fn = call["function"]
                val name = fn["name"].asText()
                @Suppress("UNCHECKED_CAST")
                val args = runCatching { json.readValue(fn["arguments"]?.asText() ?: "{}", Map::class.java) as Map<String, Any?> }
                    .getOrDefault(emptyMap())
                val out = if (name == "web_search") {
                    val q = args["query"]?.toString().orEmpty()
                    search(q) ?: fallbackSearch(q)
                } else {
                    run(name, args)
                }
                msgs.add(json.createObjectNode().put("role", "tool").put("tool_call_id", call["id"].asText()).put("content", out.take(6000)))
            }
        }
        "That took more steps than I allow myself, so I've paused there."
    }

    companion object {
        const val URL = "https://api.groq.com/openai/v1/chat/completions"
        val MODELS = listOf("openai/gpt-oss-120b", "llama-3.3-70b-versatile", "openai/gpt-oss-20b", "llama-3.1-8b-instant")
        val SEARCH_MODELS = listOf("openai/gpt-oss-20b", "openai/gpt-oss-120b")
        private val resting = HashMap<String, Long>()
        private val SEARCH = ClaudeBrain.ToolSpec(
            "web_search", "Search the web for current or factual information and get a summary with sources.",
            mapOf("query" to mapOf("type" to "string")), listOf("query"),
        )
    }
}
