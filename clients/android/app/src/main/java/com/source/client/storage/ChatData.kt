package com.source.client.storage

import com.source.client.model.ChatConversation
import com.source.client.model.ChatConversations
import com.source.client.model.ChatMessage
import com.source.client.model.ChatRole
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object ChatData : SourceData<ChatConversations> {
    override val descriptor = SourceDataDescriptor(
        id = "conversation",
        remoteAppId = "source-client",
        snapshotFormat = "source-client-conversation",
        formatVersion = 2,
    )
    override val supportedFormatVersions = setOf(1, descriptor.formatVersion)
    override val emptyValue = ChatConversations()

    override fun encode(value: ChatConversations): ByteArray = JSONObject().apply {
        put("version", descriptor.formatVersion)
        value.activeConversationId?.let { put("activeConversationId", it) }
        put("conversations", JSONArray().apply {
            value.conversations.forEach { conversation ->
                put(JSONObject().apply {
                    put("id", conversation.id)
                    put("createdAtMillis", conversation.createdAtMillis)
                    put("messages", encodeMessages(conversation.messages))
                })
            }
        })
    }.toString().toByteArray(Charsets.UTF_8)

    override fun decode(value: ByteArray): ChatConversations {
        val root = JSONObject(value.toString(Charsets.UTF_8))
        return when (val version = root.getInt("version")) {
            1 -> migrateMessages(decodeMessages(root.getJSONArray("messages")))
            descriptor.formatVersion -> {
                val conversations = root.getJSONArray("conversations")
                ChatConversations(
                    conversations = List(conversations.length()) { index ->
                        conversations.getJSONObject(index).let {
                            ChatConversation(
                                id = it.getString("id"),
                                createdAtMillis = it.getLong("createdAtMillis"),
                                messages = decodeMessages(it.getJSONArray("messages")),
                            )
                        }
                    },
                    activeConversationId = root.optString("activeConversationId").takeIf(String::isNotBlank),
                )
            }
            else -> throw IllegalArgumentException("Unsupported chat data version $version")
        }
    }

    override fun version(value: ChatConversations) = SourceDataVersion(
        modifiedAtMillis = value.conversations.maxOfOrNull { conversation ->
            maxOf(
                conversation.createdAtMillis,
                conversation.messages.maxOfOrNull(ChatMessage::createdAtMillis) ?: Long.MIN_VALUE,
            )
        } ?: Long.MIN_VALUE,
        contentIdentity = buildString {
            append(value.activeConversationId)
            value.conversations.forEach { conversation ->
                append('\u0000').append(conversation.id)
                append('\u0000').append(conversation.createdAtMillis)
                conversation.messages.forEach { message ->
                    append('\u0000').append(message.id)
                    append('\u0000').append(message.role.apiValue)
                    append('\u0000').append(message.createdAtMillis)
                    append('\u0000').append(message.content)
                }
            }
        },
    )

    private fun encodeMessages(messages: List<ChatMessage>) = JSONArray().apply {
        messages.forEach { message ->
            put(JSONObject().apply {
                put("id", message.id)
                put("role", message.role.apiValue)
                put("content", message.content)
                put("createdAtMillis", message.createdAtMillis)
            })
        }
    }

    private fun decodeMessages(messages: JSONArray) = List(messages.length()) { index ->
        messages.getJSONObject(index).let {
            ChatMessage(
                id = it.getString("id"),
                role = ChatRole.fromApiValue(it.getString("role")),
                content = it.getString("content"),
                createdAtMillis = it.getLong("createdAtMillis"),
            )
        }
    }

    private fun migrateMessages(messages: List<ChatMessage>): ChatConversations {
        if (messages.isEmpty()) return emptyValue
        val idSeed = messages.joinToString(separator = "\u0000", transform = ChatMessage::id)
        val conversation = ChatConversation(
            id = UUID.nameUUIDFromBytes(idSeed.toByteArray(Charsets.UTF_8)).toString(),
            createdAtMillis = messages.minOf(ChatMessage::createdAtMillis),
            messages = messages,
        )
        return ChatConversations(listOf(conversation), conversation.id)
    }
}
