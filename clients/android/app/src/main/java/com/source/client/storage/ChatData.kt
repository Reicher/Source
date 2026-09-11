package com.source.client.storage

import com.source.client.model.ChatMessage
import com.source.client.model.ChatRole
import org.json.JSONArray
import org.json.JSONObject

object ChatData : SourceData<List<ChatMessage>> {
    override val descriptor = SourceDataDescriptor(
        id = "conversation",
        remoteAppId = "source-client",
        snapshotFormat = "source-client-conversation",
        formatVersion = 1,
    )
    override val emptyValue = emptyList<ChatMessage>()

    override fun encode(value: List<ChatMessage>): ByteArray = JSONObject().apply {
        put("version", descriptor.formatVersion)
        put("messages", JSONArray().apply {
            value.forEach { message ->
                put(JSONObject().apply {
                    put("id", message.id)
                    put("role", message.role.apiValue)
                    put("content", message.content)
                    put("createdAtMillis", message.createdAtMillis)
                })
            }
        })
    }.toString().toByteArray(Charsets.UTF_8)

    override fun decode(value: ByteArray): List<ChatMessage> {
        val root = JSONObject(value.toString(Charsets.UTF_8))
        require(root.getInt("version") == descriptor.formatVersion)
        val messages = root.getJSONArray("messages")
        return List(messages.length()) { index ->
            messages.getJSONObject(index).let {
                ChatMessage(
                    id = it.getString("id"),
                    role = ChatRole.fromApiValue(it.getString("role")),
                    content = it.getString("content"),
                    createdAtMillis = it.getLong("createdAtMillis"),
                )
            }
        }
    }

    override fun version(value: List<ChatMessage>) = SourceDataVersion(
        modifiedAtMillis = value.maxOfOrNull(ChatMessage::createdAtMillis) ?: Long.MIN_VALUE,
        contentIdentity = value.joinToString(separator = "\u0000") { it.id },
    )
}
