package com.source.client.ai

import android.content.Context
import android.content.res.AssetManager

internal data class NativeGenerationResult(
    val finishReason: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val droppedMessages: Int,
)

internal class LlamaCppNative(context: Context) {
    private val assetManager = context.assets
    private val handleLock = Any()
    private val cancellationLock = Any()
    private val cancelledRuns = mutableSetOf<String>()
    @Volatile private var handle = 0L

    fun generate(
        request: SourceAiRequest,
        contextTokens: Int,
        maximumOutputTokens: Int,
        threads: Int,
        onToken: (String) -> Unit,
    ): NativeGenerationResult {
        val roles = request.messages.map { message ->
            when (message.role) {
                SourceAiRole.USER -> 0
                SourceAiRole.ASSISTANT -> 1
            }
        }.toIntArray()
        val contents = request.messages.map { message ->
            message.content.joinToString(separator = "") { content ->
                when (content) {
                    is SourceAiContent.Text -> content.text
                    is SourceAiContent.Image -> error("The local text runtime does not support images")
                }
            }.toByteArray(Charsets.UTF_8)
        }.toTypedArray()
        val engineHandle = ensureHandle()
        if (synchronized(cancellationLock) { cancelledRuns.remove(request.runId) }) {
            return NativeGenerationResult("cancelled", 0, 0, 0)
        }
        return try {
            parseResult(
                generateNative(
                    engineHandle,
                    request.runId,
                    roles,
                    contents,
                    contextTokens,
                    maximumOutputTokens,
                    threads,
                    object : NativeTokenListener {
                        override fun onToken(text: String) = onToken.invoke(text)
                    },
                ),
            )
        } finally {
            synchronized(cancellationLock) { cancelledRuns.remove(request.runId) }
        }
    }

    fun cancel(runId: String) {
        synchronized(cancellationLock) { cancelledRuns += runId }
        val current = handle
        if (current != 0L) cancelNative(current, runId)
    }

    fun close() {
        val current = synchronized(handleLock) {
            val value = handle
            handle = 0L
            value
        }
        if (current != 0L) destroyNative(current)
    }

    private fun ensureHandle(): Long {
        handle.takeIf { it != 0L }?.let { return it }
        return synchronized(handleLock) {
            handle.takeIf { it != 0L } ?: createNative(assetManager, BundledModelAssets.partNames).also { created ->
                check(created != 0L) { "Could not create the local llama.cpp engine" }
                handle = created
            }
        }
    }

    private fun parseResult(raw: String): NativeGenerationResult {
        val values = raw.split(';').associate { field ->
            val separator = field.indexOf('=')
            check(separator > 0) { "Invalid native generation result" }
            field.substring(0, separator) to field.substring(separator + 1)
        }
        return NativeGenerationResult(
            finishReason = values.getValue("finishReason"),
            inputTokens = values.getValue("inputTokens").toInt(),
            outputTokens = values.getValue("outputTokens").toInt(),
            droppedMessages = values.getValue("droppedMessages").toInt(),
        )
    }

    private interface NativeTokenListener {
        fun onToken(text: String)
    }

    private external fun createNative(assetManager: AssetManager, partNames: Array<String>): Long

    private external fun generateNative(
        handle: Long,
        runId: String,
        roles: IntArray,
        contents: Array<ByteArray>,
        contextTokens: Int,
        maximumOutputTokens: Int,
        threads: Int,
        listener: NativeTokenListener,
    ): String

    private external fun cancelNative(handle: Long, runId: String)

    private external fun destroyNative(handle: Long)

    private companion object {
        init {
            System.loadLibrary("source-ai-pad")
        }
    }
}
