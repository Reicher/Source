package com.source.client.ui

import com.source.client.model.ChatMessage
import com.source.client.model.ChatRole

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
