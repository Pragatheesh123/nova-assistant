package com.nova.assistant.brain

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** The on-device language model (MediaPipe LLM Inference). Loaded once and kept warm. */
object Llm {
    private var engine: LlmInference? = null
    private val lock = Mutex()

    fun file(ctx: Context) = File(ctx.getExternalFilesDir(null), "model.task")

    fun installed(ctx: Context) = file(ctx).length() > 50_000_000

    val loaded get() = engine != null

    private fun engine(ctx: Context): LlmInference = engine ?: LlmInference.createFromOptions(
        ctx.applicationContext,
        LlmInference.LlmInferenceOptions.builder()
            .setModelPath(file(ctx).absolutePath)
            .setMaxTokens(1024)
            .build(),
    ).also { engine = it }

    /** Load the model in the background so the first answer is quicker. */
    suspend fun warmUp(ctx: Context) {
        if (!installed(ctx)) return
        withContext(Dispatchers.Default) { lock.withLock { runCatching { engine(ctx) } } }
    }

    suspend fun generate(ctx: Context, prompt: String): String = withContext(Dispatchers.Default) {
        lock.withLock { engine(ctx).generateResponse(prompt) }
    }

    fun release() {
        engine?.close()
        engine = null
    }
}
