package com.source.client.ai

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.source.client.model.ChatMessage
import com.source.client.model.ChatRole
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LocalAiRuntime(context: Context) {
    private val applicationContext = context.applicationContext
    private val inferenceMutex = Mutex()
    private var engine: Engine? = null

    suspend fun chat(messages: List<ChatMessage>): ChatMessage = withContext(Dispatchers.IO) {
        require(messages.lastOrNull()?.role == ChatRole.USER) { "The final chat message must be from the user" }
        inferenceMutex.withLock {
            val prompt = messages.last().content
            require(prompt.isNotBlank()) { "The chat message cannot be empty" }
            require(prompt.length <= MAX_INPUT_CHARACTERS) { "The chat message is too long" }
            val contextMessages = conversationContext(messages.dropLast(1)).map { message ->
                when (message.role) {
                    ChatRole.USER -> Message.user(message.content)
                    ChatRole.ASSISTANT -> Message.model(message.content)
                }
            }
            val config = ConversationConfig(
                systemInstruction = Contents.of(SYSTEM_PROMPT),
                initialMessages = contextMessages,
                samplerConfig = SamplerConfig(topK = 40, topP = 0.9, temperature = 0.6),
                maxOutputToken = MAX_OUTPUT_TOKENS,
            )
            initializedEngine().createConversation(config).use { conversation ->
                val response = conversation.sendMessage(prompt)
                val content = response.contents.contents
                    .filterIsInstance<Content.Text>()
                    .joinToString(separator = "") { it.text }
                    .trim()
                check(content.isNotEmpty()) { "The local model returned an empty response" }
                ChatMessage(role = ChatRole.ASSISTANT, content = content)
            }
        }
    }

    private fun initializedEngine(): Engine {
        engine?.let { return it }
        return Engine(
            EngineConfig(
                modelPath = installedModel().absolutePath,
                backend = Backend.CPU(),
                cacheDir = applicationContext.cacheDir.absolutePath,
            ),
        ).also {
            try {
                it.initialize()
                engine = it
            } catch (error: Exception) {
                it.close()
                throw error
            }
        }
    }

    private fun installedModel(): File {
        val modelDirectory = File(applicationContext.filesDir, "models")
        check(modelDirectory.exists() || modelDirectory.mkdirs()) { "Could not create the local model directory" }
        val destination = File(modelDirectory, MODEL_ASSET_NAME)
        if (destination.isFile && destination.length() > 0) return destination
        val temporary = File(modelDirectory, ".$MODEL_ASSET_NAME.tmp")
        try {
            applicationContext.assets.open(MODEL_ASSET_NAME).use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            check(temporary.length() > 0) { "The packaged local model is empty" }
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                check(temporary.delete()) { "Could not remove the temporary local model" }
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
        return destination
    }

    private fun conversationContext(messages: List<ChatMessage>): List<ChatMessage> {
        val kept = ArrayDeque<ChatMessage>()
        var characters = 0
        for (message in messages.asReversed()) {
            if (kept.size >= MAX_CONTEXT_MESSAGES || characters + message.content.length > MAX_CONTEXT_CHARACTERS) break
            kept.addFirst(message)
            characters += message.content.length
        }
        while (kept.firstOrNull()?.role == ChatRole.ASSISTANT) kept.removeFirst()
        return kept.toList()
    }

    companion object {
        const val MODEL_ASSET_NAME = "source-client-model.litertlm"
        private const val MAX_CONTEXT_MESSAGES = 12
        private const val MAX_CONTEXT_CHARACTERS = 4_000
        private const val MAX_INPUT_CHARACTERS = 4_000
        private const val MAX_OUTPUT_TOKENS = 256
        private const val SYSTEM_PROMPT =
            "Du är en privat, lokalt körd assistent i Source. Svara på samma språk som användaren och var tydlig och kortfattad. " +
                "Du har ingen internetåtkomst och får inte låtsas att du har sökt på nätet."
    }
}
