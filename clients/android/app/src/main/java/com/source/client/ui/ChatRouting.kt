package com.source.client.ui

import com.source.client.model.AiSelection
import com.source.client.model.ChatMessage
import com.source.client.model.ChatRole
import com.source.client.protocol.SourceApiException

internal enum class AiRuntimeTarget { THIS_DEVICE, NODE }

internal fun resolveAiRuntime(selection: AiSelection, nodeConnected: Boolean): AiRuntimeTarget = when (selection) {
    AiSelection.AUTO -> if (nodeConnected) AiRuntimeTarget.NODE else AiRuntimeTarget.THIS_DEVICE
    AiSelection.THIS_DEVICE -> AiRuntimeTarget.THIS_DEVICE
    AiSelection.NODE -> if (nodeConnected) AiRuntimeTarget.NODE else AiRuntimeTarget.THIS_DEVICE
}

internal fun canFallbackFromNode(error: Exception): Boolean =
    error !is SourceApiException || error.code in setOf("model_unavailable", "http_502", "http_503", "http_504")

/** Keeps the newest complete suffix accepted by both the Source API and the local model. */
internal fun boundedChatContext(messages: List<ChatMessage>): List<ChatMessage> {
    val selected = ArrayDeque<ChatMessage>()
    var characters = 0
    for (message in messages.asReversed()) {
        if (selected.size == MAX_CONTEXT_MESSAGES || characters + message.content.length > MAX_CONTEXT_CHARACTERS) break
        selected.addFirst(message)
        characters += message.content.length
    }
    while (selected.firstOrNull()?.role == ChatRole.ASSISTANT) selected.removeFirst()
    return selected.toList()
}

private const val MAX_CONTEXT_MESSAGES = 20
private const val MAX_CONTEXT_CHARACTERS = 16_000
