package com.source.client.storage

import com.source.client.model.ChatConversation
import com.source.client.model.ChatConversations
import com.source.client.model.ChatConversationTombstone
import com.source.client.model.ChatMessage
import com.source.client.model.ChatRole
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

object ChatData : SourceData<ChatConversations> {
    override val descriptor = SourceDataDescriptor(
        id = "conversation",
        remoteAppId = "source-client",
        snapshotFormat = "source-client-conversation",
        formatVersion = 3,
    )
    override val supportedFormatVersions = setOf(1, 2, descriptor.formatVersion)
    override val emptyValue = ChatConversations()

    override fun encode(value: ChatConversations): ByteArray = JSONObject().apply {
        put("version", descriptor.formatVersion)
        value.activeConversationId?.let { put("activeConversationId", it) }
        put("conversations", JSONArray().apply {
            value.conversations.forEach { conversation ->
                put(encodeConversation(conversation))
            }
        })
        put("tombstones", JSONArray().apply {
            value.tombstones.forEach { tombstone ->
                put(JSONObject().apply {
                    put("conversationId", tombstone.conversationId)
                    put("deletedAtMillis", tombstone.deletedAtMillis)
                })
            }
        })
    }.toString().toByteArray(Charsets.UTF_8)

    /** Size of the conversation's existing JSON representation, without creating a separate file. */
    fun encodedConversationByteCount(conversation: ChatConversation): Long =
        encodeConversation(conversation).toString().toByteArray(Charsets.UTF_8).size.toLong()

    fun encodedConversation(conversation: ChatConversation): String =
        encodeConversation(conversation).toString(2)

    fun conversationContentSha256(conversation: ChatConversation): String = MessageDigest.getInstance("SHA-256")
        .digest(encodeConversation(conversation).toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    fun refinementText(conversation: ChatConversation): String = conversation.messages.joinToString("\n\n") { message ->
        "${message.role.apiValue.replaceFirstChar(Char::uppercaseChar)}: ${message.content}"
    }

    override fun decode(value: ByteArray): ChatConversations {
        val root = JSONObject(value.toString(Charsets.UTF_8))
        return when (val version = root.getInt("version")) {
            1 -> migrateMessages(decodeMessages(root.getJSONArray("messages")))
            2, descriptor.formatVersion -> {
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
                    tombstones = if (version >= 3) {
                        val tombstones = root.getJSONArray("tombstones")
                        List(tombstones.length()) { index ->
                            tombstones.getJSONObject(index).let {
                                ChatConversationTombstone(
                                    conversationId = it.getString("conversationId"),
                                    deletedAtMillis = it.getLong("deletedAtMillis"),
                                )
                            }
                        }
                    } else {
                        emptyList()
                    },
                )
            }
            else -> throw IllegalArgumentException("Unsupported chat data version $version")
        }
    }

    override fun version(value: ChatConversations) = SourceDataVersion(
        modifiedAtMillis = maxOf(
            value.conversations.maxOfOrNull { conversation ->
                maxOf(
                    conversation.createdAtMillis,
                    conversation.messages.maxOfOrNull(ChatMessage::createdAtMillis) ?: Long.MIN_VALUE,
                )
            } ?: Long.MIN_VALUE,
            value.tombstones.maxOfOrNull(ChatConversationTombstone::deletedAtMillis) ?: Long.MIN_VALUE,
        ),
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
            value.tombstones.sortedBy(ChatConversationTombstone::conversationId).forEach { tombstone ->
                append('\u0000').append(tombstone.conversationId)
                append('\u0000').append(tombstone.deletedAtMillis)
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

    private fun encodeConversation(conversation: ChatConversation) = JSONObject().apply {
        put("id", conversation.id)
        put("createdAtMillis", conversation.createdAtMillis)
        put("messages", encodeMessages(conversation.messages))
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
