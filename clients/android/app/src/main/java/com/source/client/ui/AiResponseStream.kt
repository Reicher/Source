package com.source.client.ui

import com.source.client.ai.SourceAiEvent
import com.source.client.ai.SourceAiRequest
import com.source.client.ai.SourceAiRuntime

internal class SourceAiFailureException(
    val code: String,
    val retryable: Boolean,
) : IllegalStateException("AI runtime failed: $code")

internal suspend fun collectAiResponse(
    runtime: SourceAiRuntime,
    request: SourceAiRequest,
    onDelta: (String) -> Unit,
): String {
    val content = StringBuilder()
    var completed = false
    runtime.stream(request).collect { event ->
        check(event.runId == request.runId) { "The AI runtime returned the wrong run identifier" }
        when (event) {
            is SourceAiEvent.Started -> Unit
            is SourceAiEvent.Delta -> {
                content.append(event.text)
                onDelta(content.toString())
            }
            is SourceAiEvent.Completed -> completed = true
            is SourceAiEvent.Failed -> throw SourceAiFailureException(event.code, event.retryable)
        }
    }
    check(completed && content.isNotBlank()) { "The AI runtime returned an incomplete response" }
    return content.toString().trim()
}
