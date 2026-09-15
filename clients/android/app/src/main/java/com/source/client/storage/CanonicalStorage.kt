package com.source.client.storage

import com.source.client.security.SourceCrypto
import java.security.MessageDigest
import java.text.Normalizer
import java.util.UUID
import org.erdtman.jcs.JsonCanonicalizer
import org.json.JSONArray
import org.json.JSONObject

internal const val SOURCE_STORAGE_CONTRACT_VERSION = 1

data class StorageObjectKey(
    val profileId: String,
    val collection: String,
    val objectId: String,
)

data class StoragePayloadDescriptor(
    val format: String,
    val formatVersion: Int,
    val byteCount: Long,
    val plaintextSha256: String,
)

data class StorageRevision(
    val revisionId: String,
    val objectKey: StorageObjectKey,
    val kind: String,
    val parentRevisionIds: List<String>,
    val payload: StoragePayloadDescriptor?,
    val createdAtMillis: Long? = null,
)

data class StorageMutation(
    val operationId: String,
    val originEpoch: String,
    val originSequence: Long,
    val expectedAuthorityEpoch: String? = null,
    val revision: StorageRevision,
    val payloadBytes: ByteArray,
)

data class StorageCommitReceipt(
    val operationId: String,
    val revisionId: String,
    val authorityNodeId: String,
    val authorityEpoch: String,
    val commitSequence: Long,
)

data class StorageCursor(
    val authorityNodeId: String,
    val authorityEpoch: String,
    val commitSequence: Long,
)

data class StorageChange(
    val receipt: StorageCommitReceipt,
    val revision: StorageRevision,
)

data class StorageChanges(
    val cursor: StorageCursor,
    val requiresManifest: Boolean,
    val changes: List<StorageChange>,
    val heads: List<StorageRevision>,
)

data class CanonicalSyncState(
    val originEpoch: String = UUID.randomUUID().toString(),
    val nextOriginSequence: Long = 1,
    val cursor: StorageCursor? = null,
    val heads: List<String> = emptyList(),
    val pending: List<StorageMutation> = emptyList(),
    val receipts: List<StorageCommitReceipt> = emptyList(),
) {
    init {
        require(UUID_PATTERN.matches(originEpoch))
        require(nextOriginSequence > 0)
        require(heads == heads.distinct().sorted())
    }
}

internal fun canonicalObjectId(collection: String): String =
    UUID.nameUUIDFromBytes("source-storage-object\u0000$collection".toByteArray(Charsets.UTF_8)).toString()

internal fun contentRevision(
    profileId: String,
    descriptor: SourceDataDescriptor,
    parents: List<String>,
    payload: ByteArray,
    createdAtMillis: Long,
): StorageRevision {
    val collection = checkNotNull(descriptor.canonicalCollection)
    val withoutId = StorageRevision(
        revisionId = "",
        objectKey = StorageObjectKey(profileId, collection, canonicalObjectId(collection)),
        kind = "content",
        parentRevisionIds = parents.distinct().sorted(),
        payload = StoragePayloadDescriptor(
            format = descriptor.snapshotFormat,
            formatVersion = descriptor.formatVersion,
            byteCount = payload.size.toLong(),
            plaintextSha256 = sha256Hex(payload),
        ),
        createdAtMillis = createdAtMillis.coerceAtLeast(1),
    )
    return withoutId.copy(revisionId = storageRevisionId(withoutId))
}

internal fun storageRevisionId(revision: StorageRevision): String {
    validateStorageRevision(revision, requireId = false)
    val identity = buildString {
        append("{\"collection\":").append(jsonQuote(revision.objectKey.collection))
        append(",\"kind\":").append(jsonQuote(revision.kind))
        append(",\"objectId\":").append(jsonQuote(revision.objectKey.objectId))
        append(",\"parents\":[")
        append(revision.parentRevisionIds.joinToString(",", transform = ::jsonQuote))
        append("],\"payload\":")
        val payload = revision.payload
        if (payload == null) append("null") else {
            append("{\"byteCount\":").append(payload.byteCount)
            append(",\"format\":").append(jsonQuote(payload.format))
            append(",\"formatVersion\":").append(payload.formatVersion)
            append(",\"plaintextSha256\":").append(jsonQuote(payload.plaintextSha256)).append('}')
        }
        append(",\"profileId\":").append(jsonQuote(revision.objectKey.profileId)).append('}')
    }
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("source-storage-revision".toByteArray(Charsets.UTF_8))
    digest.update(0)
    digest.update(JsonCanonicalizer(identity).encodedUTF8)
    return digest.digest().toHex()
}

internal fun validateStorageRevision(revision: StorageRevision, requireId: Boolean = true) {
    require(UUID_PATTERN.matches(revision.objectKey.profileId))
    require(COLLECTION_PATTERN.matches(revision.objectKey.collection))
    require(UUID_PATTERN.matches(revision.objectKey.objectId) || SHA256_PATTERN.matches(revision.objectKey.objectId))
    require(revision.objectKey.objectId == nfc(revision.objectKey.objectId))
    require(revision.kind == "content" || revision.kind == "tombstone")
    require(revision.parentRevisionIds == revision.parentRevisionIds.distinct().sorted())
    require(revision.parentRevisionIds.all(SHA256_PATTERN::matches))
    require(!requireId || SHA256_PATTERN.matches(revision.revisionId))
    if (revision.kind == "content") {
        val payload = checkNotNull(revision.payload)
        require(payload.format.isNotBlank() && payload.format == nfc(payload.format))
        require(payload.formatVersion > 0 && payload.byteCount >= 0 && SHA256_PATTERN.matches(payload.plaintextSha256))
    } else {
        require(revision.payload == null)
    }
    revision.createdAtMillis?.let { require(it > 0) }
}

internal fun encodeCanonicalSyncState(state: CanonicalSyncState): ByteArray = JSONObject().apply {
    put("version", SOURCE_STORAGE_CONTRACT_VERSION)
    put("originEpoch", state.originEpoch)
    put("nextOriginSequence", state.nextOriginSequence)
    put("cursor", state.cursor?.toJson() ?: JSONObject.NULL)
    put("heads", JSONArray(state.heads))
    put("pending", JSONArray().apply { state.pending.forEach { put(it.toJson()) } })
    put("receipts", JSONArray().apply { state.receipts.forEach { put(it.toJson()) } })
}.toString().toByteArray(Charsets.UTF_8)

internal fun decodeCanonicalSyncState(raw: ByteArray): CanonicalSyncState {
    val root = JSONObject(raw.toString(Charsets.UTF_8))
    require(root.getInt("version") == SOURCE_STORAGE_CONTRACT_VERSION)
    val heads = root.getJSONArray("heads")
    val pending = root.getJSONArray("pending")
    val receipts = root.optJSONArray("receipts") ?: JSONArray()
    return CanonicalSyncState(
        originEpoch = root.getString("originEpoch"),
        nextOriginSequence = root.getLong("nextOriginSequence"),
        cursor = root.optJSONObject("cursor")?.toStorageCursor(),
        heads = List(heads.length(), heads::getString),
        pending = List(pending.length()) { pending.getJSONObject(it).toStorageMutation() },
        receipts = List(receipts.length()) { receipts.getJSONObject(it).toStorageReceipt() },
    )
}

internal fun StorageRevision.toJson() = JSONObject().apply {
    put("revisionId", revisionId)
    put("objectKey", JSONObject().apply {
        put("profileId", objectKey.profileId)
        put("collection", objectKey.collection)
        put("objectId", objectKey.objectId)
    })
    put("kind", kind)
    put("parentRevisionIds", JSONArray(parentRevisionIds))
    put("payload", payload?.toJson() ?: JSONObject.NULL)
    createdAtMillis?.let { put("createdAtMillis", it) }
}

internal fun JSONObject.toStorageRevision(): StorageRevision {
    val key = getJSONObject("objectKey")
    val parents = getJSONArray("parentRevisionIds")
    return StorageRevision(
        revisionId = getString("revisionId"),
        objectKey = StorageObjectKey(key.getString("profileId"), key.getString("collection"), key.getString("objectId")),
        kind = getString("kind"),
        parentRevisionIds = List(parents.length(), parents::getString),
        payload = optJSONObject("payload")?.toStoragePayload(),
        createdAtMillis = if (has("createdAtMillis")) getLong("createdAtMillis") else null,
    ).also {
        validateStorageRevision(it)
        require(storageRevisionId(it) == it.revisionId)
    }
}

private fun StoragePayloadDescriptor.toJson() = JSONObject().apply {
    put("format", format)
    put("formatVersion", formatVersion)
    put("byteCount", byteCount)
    put("plaintextSha256", plaintextSha256)
}

private fun JSONObject.toStoragePayload() = StoragePayloadDescriptor(
    format = getString("format"), formatVersion = getInt("formatVersion"),
    byteCount = getLong("byteCount"), plaintextSha256 = getString("plaintextSha256"),
)

internal fun StorageCursor.toJson() = JSONObject().apply {
    put("authorityNodeId", authorityNodeId)
    put("authorityEpoch", authorityEpoch)
    put("commitSequence", commitSequence)
}

internal fun JSONObject.toStorageCursor() = StorageCursor(
    authorityNodeId = getString("authorityNodeId"),
    authorityEpoch = getString("authorityEpoch"),
    commitSequence = getLong("commitSequence"),
)

internal fun StorageCommitReceipt.toJson() = JSONObject().apply {
    put("operationId", operationId)
    put("revisionId", revisionId)
    put("authorityNodeId", authorityNodeId)
    put("authorityEpoch", authorityEpoch)
    put("commitSequence", commitSequence)
}

internal fun JSONObject.toStorageReceipt() = StorageCommitReceipt(
    operationId = getString("operationId"), revisionId = getString("revisionId"),
    authorityNodeId = getString("authorityNodeId"), authorityEpoch = getString("authorityEpoch"),
    commitSequence = getLong("commitSequence"),
)

private fun StorageMutation.toJson() = JSONObject().apply {
    put("operationId", operationId)
    put("originEpoch", originEpoch)
    put("originSequence", originSequence)
    put("expectedAuthorityEpoch", expectedAuthorityEpoch ?: JSONObject.NULL)
    put("revision", revision.toJson())
    put("payload", SourceCrypto.base64Url(payloadBytes))
}

private fun JSONObject.toStorageMutation() = StorageMutation(
    operationId = getString("operationId"), originEpoch = getString("originEpoch"),
    originSequence = getLong("originSequence"),
    expectedAuthorityEpoch = if (isNull("expectedAuthorityEpoch")) null else getString("expectedAuthorityEpoch"),
    revision = getJSONObject("revision").toStorageRevision(),
    payloadBytes = SourceCrypto.base64UrlDecode(getString("payload")),
)

internal fun sha256Hex(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value).toHex()

private fun ByteArray.toHex() = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

private fun nfc(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)

private fun jsonQuote(value: String): String = buildString {
    append('"')
    value.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\t' -> append("\\t")
            '\n' -> append("\\n")
            '\u000c' -> append("\\f")
            '\r' -> append("\\r")
            else -> if (character.code < 0x20) {
                append("\\u").append(character.code.toString(16).padStart(4, '0'))
            } else append(character)
        }
    }
    append('"')
}

private val UUID_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
private val COLLECTION_PATTERN = Regex("^[a-z][a-z0-9-]{1,63}$")
private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
