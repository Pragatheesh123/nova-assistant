package com.nova.assistant.brain

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.beta.messages.BetaTool
import com.anthropic.models.beta.messages.BetaToolResultBlockParam
import com.anthropic.models.beta.messages.BetaWebSearchTool20260209
import com.anthropic.models.beta.messages.MessageCreateParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration

/**
 * Nova's cloud brain: Claude with the phone's tools and web search.
 * Pure Kotlin + the Anthropic Java SDK (no Android types), so the tool runner is passed in.
 */
class ClaudeBrain(apiKey: String, baseUrl: String? = null) {
    data class ToolSpec(
        val name: String,
        val description: String,
        val props: Map<String, Map<String, Any>> = emptyMap(),
        val required: List<String> = emptyList(),
    )

    /** Thrown by a tool when the user must decide before Nova continues (e.g. the leak guard). */
    class Interrupt(val payload: Any) : RuntimeException()

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .apply { if (baseUrl != null) baseUrl(baseUrl) }
        .timeout(Duration.ofSeconds(60))
        .maxRetries(2)
        .build()

    private val history = ArrayList<Pair<String, String>>() // past turns, text only

    private fun BetaToolOf(t: ToolSpec): BetaTool {
        val props = BetaTool.InputSchema.Properties.builder()
        t.props.forEach { (k, v) -> props.putAdditionalProperty(k, JsonValue.from(v)) }
        return BetaTool.builder()
            .name(t.name)
            .description(t.description)
            .inputSchema(BetaTool.InputSchema.builder().properties(props.build()).required(t.required).build())
            .build()
    }

    /**
     * One conversation turn. [run] executes a tool and returns its result text.
     * Returns Nova's spoken reply. May throw [Interrupt] from [run].
     */
    suspend fun think(
        system: String,
        userText: String,
        tools: List<ToolSpec>,
        thorough: Boolean,
        run: suspend (String, Map<String, Any?>) -> String,
    ): String = withContext(Dispatchers.IO) {
        val p = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(4096L)
            .systemOfBetaTextBlockParams(
                listOf(BetaTextBlockParam.builder().text(system).cacheControl(BetaCacheControlEphemeral.builder().build()).build()),
            )
            .outputConfig(
                BetaOutputConfig.builder()
                    .effort(if (thorough) BetaOutputConfig.Effort.MEDIUM else BetaOutputConfig.Effort.LOW)
                    .build(),
            )
            .addBeta("server-side-fallback-2026-07-01")
            .fallbacksDefault()
        tools.forEach { p.addTool(BetaToolOf(it)) }
        p.addTool(BetaWebSearchTool20260209.builder().maxUses(3L).build())
        for ((u, a) in history) {
            p.addUserMessage(u)
            p.addAssistantMessage(a)
        }
        p.addUserMessage(userText)

        try {
            for (step in 0 until 14) {
                val resp = client.beta().messages().create(p.build())
                val stop = resp.stopReason().orElse(null)
                if (stop == BetaStopReason.REFUSAL) return@withContext "I'm afraid I can't help with that one."
                p.addMessage(resp) // keep the full assistant turn (thinking, tool calls) unchanged
                if (stop == BetaStopReason.PAUSE_TURN) continue

                val calls = resp.content().mapNotNull { it.toolUse().orElse(null) }
                if (calls.isEmpty()) {
                    val reply = resp.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString(" ").trim()
                        .ifEmpty { "Done." }
                    remember(userText, reply)
                    return@withContext reply
                }
                val results = calls.map { call ->
                    @Suppress("UNCHECKED_CAST")
                    val args = runCatching { call._input().convert(Map::class.java) as Map<String, Any?> }.getOrDefault(emptyMap())
                    val out = try {
                        run(call.name(), args)
                    } catch (i: Interrupt) {
                        throw i
                    } catch (e: Exception) {
                        "That failed: ${e.message}"
                    }
                    BetaContentBlockParam.ofToolResult(
                        BetaToolResultBlockParam.builder().toolUseId(call.id()).content(out.take(8000)).build(),
                    )
                }
                p.addUserMessageOfBetaContentBlockParams(results)
            }
            "That took more steps than I allow myself, so I've paused there."
        } catch (e: UnauthorizedException) {
            "My Claude API key isn't working. Check it in the Nova app."
        } catch (e: RateLimitException) {
            "Claude is rate limiting me for a moment. Try again shortly."
        } catch (e: AnthropicIoException) {
            "I can't reach my cloud brain. Check your internet connection."
        } catch (e: AnthropicServiceException) {
            "My cloud brain hit a problem. Try again in a moment."
        }
    }

    private fun remember(u: String, a: String) {
        history += u to a
        while (history.size > 10) history.removeAt(0)
    }

    companion object {
        const val MODEL = "claude-opus-5-5"
    }
}
