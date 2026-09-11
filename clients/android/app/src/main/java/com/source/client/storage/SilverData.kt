package com.source.client.storage

import org.json.JSONArray
import org.json.JSONObject

data class SilverEntity(
    val id: String,
    val name: String,
    val type: String,
) {
    init {
        require(ENTITY_ID_PATTERN.matches(id)) { "Invalid Silver entity identifier" }
        require(name.isNotBlank() && name.length <= 200 && name.none(Char::isISOControl)) {
            "Invalid Silver entity name"
        }
        require(ENTITY_TYPE_PATTERN.matches(type)) { "Invalid Silver entity type" }
    }
}

sealed interface SilverScalarValue {
    data class Text(val text: String) : SilverScalarValue {
        init {
            require(text.isNotBlank() && text.length <= 500 && text.none(Char::isISOControl)) {
                "Invalid Silver text value"
            }
        }
    }

    data class Number(val number: Double) : SilverScalarValue {
        init {
            require(number.isFinite()) { "Invalid Silver numeric value" }
        }
    }

    data class BooleanValue(val boolean: Boolean) : SilverScalarValue
}

data class SilverClaim(
    val subjectEntityId: String,
    val predicate: String,
    val objectEntityId: String? = null,
    val value: SilverScalarValue? = null,
    val confidence: Double,
    val bronzeSourceId: String,
    val evidenceExcerpt: String? = null,
) {
    init {
        require(ENTITY_ID_PATTERN.matches(subjectEntityId)) { "Invalid Silver claim subject" }
        require((objectEntityId == null) != (value == null)) { "A Silver claim must have exactly one object or value" }
        objectEntityId?.let { require(ENTITY_ID_PATTERN.matches(it)) { "Invalid Silver claim object" } }
        require(predicate.isNotBlank() && predicate.length <= 100 && predicate.none(Char::isISOControl)) {
            "Invalid Silver predicate"
        }
        require(confidence.isFinite() && confidence in 0.0..1.0) { "Invalid Silver confidence" }
        require(bronzeSourceId.isNotBlank() && bronzeSourceId.length <= 240) { "Invalid Bronze source reference" }
        require(evidenceExcerpt == null ||
            (evidenceExcerpt.isNotBlank() && evidenceExcerpt.length <= 240 && evidenceExcerpt.none(Char::isISOControl))) {
            "Invalid Silver evidence excerpt"
        }
    }
}

data class SilverResult(
    val bronzeSourceId: String,
    val bronzeContentSha256: String,
    val entities: List<SilverEntity>,
    val claims: List<SilverClaim>,
    val modelId: String,
    val parameterCount: Long,
    val processorVersion: Int,
    val processedAtMillis: Long,
) {
    init {
        require(bronzeSourceId.isNotBlank() && bronzeSourceId.length <= 240) { "Invalid Bronze source reference" }
        require(SILVER_SHA256_PATTERN.matches(bronzeContentSha256)) { "Invalid Bronze content identity" }
        require(modelId.isNotBlank() && modelId.length <= 200) { "Invalid Silver model identifier" }
        require(parameterCount > 0) { "Invalid Silver model parameter count" }
        require(processorVersion > 0) { "Invalid Silver processor version" }
        require(processedAtMillis > 0) { "Invalid Silver processing time" }
        require(entities.distinctBy(SilverEntity::id).size == entities.size) { "Duplicate Silver entities" }
        val entityIds = entities.mapTo(mutableSetOf(), SilverEntity::id)
        require(claims.all { claim ->
            claim.bronzeSourceId == bronzeSourceId && claim.subjectEntityId in entityIds &&
                (claim.objectEntityId == null || claim.objectEntityId in entityIds)
        }) { "Silver claims must reference entities and their containing Bronze source" }
    }
}

data class SilverDataset(
    val results: List<SilverResult> = emptyList(),
    val modifiedAtMillis: Long = 0,
    val removedSourceIds: Map<String, Long> = emptyMap(),
)

object SilverData : SourceData<SilverDataset> {
    override val descriptor = SourceDataDescriptor(
        id = "silver",
        remoteAppId = "source-silver",
        snapshotFormat = "source-silver",
        formatVersion = 1,
    )
    override val emptyValue = SilverDataset()

    override fun encode(value: SilverDataset): ByteArray {
        validate(value)
        return JSONObject().apply {
            put("version", descriptor.formatVersion)
            put("modifiedAtMillis", value.modifiedAtMillis)
            put("results", JSONArray().apply { value.results.forEach { put(encodeResult(it)) } })
            put("removedSources", JSONArray().apply {
                value.removedSourceIds.toSortedMap().forEach { (sourceId, removedAtMillis) ->
                    put(JSONObject().put("bronzeSourceId", sourceId).put("removedAtMillis", removedAtMillis))
                }
            })
        }.toString().toByteArray(Charsets.UTF_8)
    }

    override fun decode(value: ByteArray): SilverDataset {
        val root = JSONObject(value.toString(Charsets.UTF_8))
        require(root.getInt("version") == descriptor.formatVersion) { "Unsupported Silver data version" }
        val results = root.getJSONArray("results")
        val removedSources = root.optJSONArray("removedSources") ?: JSONArray()
        return SilverDataset(
            results = List(results.length()) { decodeResult(results.getJSONObject(it)) },
            modifiedAtMillis = root.getLong("modifiedAtMillis"),
            removedSourceIds = buildMap {
                repeat(removedSources.length()) {
                    val removed = removedSources.getJSONObject(it)
                    put(removed.getString("bronzeSourceId"), removed.getLong("removedAtMillis"))
                }
            },
        ).also(::validate)
    }

    override fun version(value: SilverDataset): SourceDataVersion {
        validate(value)
        return SourceDataVersion(
            modifiedAtMillis = value.modifiedAtMillis,
            contentIdentity = listOf(
                value.results.sortedBy(SilverResult::bronzeSourceId).joinToString("\u0000") { result ->
                    listOf(
                        result.bronzeSourceId,
                        result.bronzeContentSha256,
                        result.modelId,
                        result.parameterCount.toString(),
                        result.processorVersion.toString(),
                        result.processedAtMillis.toString(),
                        result.entities.joinToString("\u0001") { "${it.id}\u0002${it.name}\u0002${it.type}" },
                        result.claims.joinToString("\u0001", transform = ::claimIdentity),
                    ).joinToString("\u0003")
                },
                value.removedSourceIds.toSortedMap().entries.joinToString("\u0000") { "${it.key}\u0002${it.value}" },
            ).joinToString("\u0004"),
        )
    }

    override fun merge(local: SilverDataset, remote: SilverDataset): SilverDataset {
        validate(local)
        validate(remote)
        val localBySource = local.results.associateBy(SilverResult::bronzeSourceId)
        val remoteBySource = remote.results.associateBy(SilverResult::bronzeSourceId)
        val mergedResults = mutableListOf<SilverResult>()
        val mergedRemoved = mutableMapOf<String, Long>()
        val sourceIds = localBySource.keys + remoteBySource.keys +
            local.removedSourceIds.keys + remote.removedSourceIds.keys
        sourceIds.sorted().forEach { sourceId ->
            val localResult = localBySource[sourceId]
            val remoteResult = remoteBySource[sourceId]
            val result = when {
                localResult == null -> remoteResult
                remoteResult == null -> localResult
                else -> betterResult(localResult, remoteResult)
            }
            val removedAt = maxOf(
                local.removedSourceIds[sourceId] ?: 0,
                remote.removedSourceIds[sourceId] ?: 0,
            )
            if (result != null && result.processedAtMillis > removedAt) {
                mergedResults += result
            } else if (removedAt > 0) {
                mergedRemoved[sourceId] = removedAt
            }
        }
        val mergedBySource = mergedResults.associateBy(SilverResult::bronzeSourceId)
        return when {
            mergedBySource == localBySource && mergedRemoved == local.removedSourceIds -> local
            mergedBySource == remoteBySource && mergedRemoved == remote.removedSourceIds -> remote
            else -> SilverDataset(
                mergedResults,
                maxOf(local.modifiedAtMillis, remote.modifiedAtMillis) + 1,
                mergedRemoved,
            )
        }
    }

    private fun encodeResult(result: SilverResult) = JSONObject().apply {
        put("bronzeSourceId", result.bronzeSourceId)
        put("bronzeContentSha256", result.bronzeContentSha256)
        put("modelId", result.modelId)
        put("parameterCount", result.parameterCount)
        put("processorVersion", result.processorVersion)
        put("processedAtMillis", result.processedAtMillis)
        put("entities", JSONArray().apply {
            result.entities.forEach { entity ->
                put(JSONObject().put("id", entity.id).put("name", entity.name).put("type", entity.type))
            }
        })
        put("claims", JSONArray().apply { result.claims.forEach { put(encodeClaim(it)) } })
    }

    private fun encodeClaim(claim: SilverClaim) = JSONObject().apply {
        put("subjectEntityId", claim.subjectEntityId)
        put("predicate", claim.predicate)
        claim.objectEntityId?.let { put("objectEntityId", it) }
        claim.value?.let { scalar ->
            put("value", JSONObject().apply {
                when (scalar) {
                    is SilverScalarValue.Text -> put("type", "text").put("value", scalar.text)
                    is SilverScalarValue.Number -> put("type", "number").put("value", scalar.number)
                    is SilverScalarValue.BooleanValue -> put("type", "boolean").put("value", scalar.boolean)
                }
            })
        }
        put("confidence", claim.confidence)
        put("bronzeSourceId", claim.bronzeSourceId)
        claim.evidenceExcerpt?.let { put("evidenceExcerpt", it) }
    }

    private fun decodeResult(value: JSONObject): SilverResult {
        val entities = value.getJSONArray("entities")
        val claims = value.getJSONArray("claims")
        return SilverResult(
            bronzeSourceId = value.getString("bronzeSourceId"),
            bronzeContentSha256 = value.getString("bronzeContentSha256"),
            entities = List(entities.length()) { index ->
                entities.getJSONObject(index).let {
                    SilverEntity(it.getString("id"), it.getString("name"), it.getString("type"))
                }
            },
            claims = List(claims.length()) { decodeClaim(claims.getJSONObject(it)) },
            modelId = value.getString("modelId"),
            parameterCount = value.getLong("parameterCount"),
            processorVersion = value.getInt("processorVersion"),
            processedAtMillis = value.getLong("processedAtMillis"),
        )
    }

    private fun decodeClaim(value: JSONObject): SilverClaim = SilverClaim(
        subjectEntityId = value.getString("subjectEntityId"),
        predicate = value.getString("predicate"),
        objectEntityId = value.optString("objectEntityId").takeIf(String::isNotBlank),
        value = value.optJSONObject("value")?.let(::decodeScalar),
        confidence = value.getDouble("confidence"),
        bronzeSourceId = value.getString("bronzeSourceId"),
        evidenceExcerpt = value.optString("evidenceExcerpt").takeIf(String::isNotBlank),
    )

    private fun decodeScalar(value: JSONObject): SilverScalarValue = when (value.getString("type")) {
        "text" -> SilverScalarValue.Text(value.getString("value"))
        "number" -> SilverScalarValue.Number(value.getDouble("value"))
        "boolean" -> SilverScalarValue.BooleanValue(value.getBoolean("value"))
        else -> throw IllegalArgumentException("Unsupported Silver scalar value")
    }

    private fun validate(value: SilverDataset) {
        require(value.modifiedAtMillis >= 0) { "Invalid Silver modification time" }
        require(value.results.distinctBy(SilverResult::bronzeSourceId).size == value.results.size) {
            "Only one current Silver result may exist for a Bronze source"
        }
        require(value.removedSourceIds.keys.none { removed -> value.results.any { it.bronzeSourceId == removed } }) {
            "A Silver source cannot be both current and removed"
        }
        require(value.removedSourceIds.all { (sourceId, removedAtMillis) ->
            sourceId.isNotBlank() && sourceId.length <= 240 && removedAtMillis > 0
        }) { "Invalid removed Silver source" }
    }

    private fun betterResult(first: SilverResult, second: SilverResult): SilverResult = when {
        first.bronzeContentSha256 != second.bronzeContentSha256 ->
            if (first.processedAtMillis >= second.processedAtMillis) first else second
        first.processorVersion != second.processorVersion ->
            if (first.processorVersion > second.processorVersion) first else second
        first.parameterCount != second.parameterCount ->
            if (first.parameterCount > second.parameterCount) first else second
        else -> if (first.processedAtMillis >= second.processedAtMillis) first else second
    }

    private fun claimIdentity(claim: SilverClaim): String = listOf(
        claim.subjectEntityId,
        claim.predicate,
        claim.objectEntityId.orEmpty(),
        scalarIdentity(claim.value),
        claim.confidence.toString(),
        claim.bronzeSourceId,
        claim.evidenceExcerpt.orEmpty(),
    ).joinToString("\u0002")

    private fun scalarIdentity(value: SilverScalarValue?): String = when (value) {
        null -> ""
        is SilverScalarValue.Text -> "text:${value.text}"
        is SilverScalarValue.Number -> "number:${value.number}"
        is SilverScalarValue.BooleanValue -> "boolean:${value.boolean}"
    }
}

private val ENTITY_ID_PATTERN = Regex("^[0-9a-f]{64}$")
private val ENTITY_TYPE_PATTERN = Regex("^[a-z][a-z0-9-]{0,63}$")
private val SILVER_SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
