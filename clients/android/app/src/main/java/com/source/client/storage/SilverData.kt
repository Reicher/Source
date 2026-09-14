package com.source.client.storage

import org.json.JSONArray
import org.json.JSONObject

data class SilverDataset(
    val evidence: List<SilverEvidence> = emptyList(),
    val observations: List<SilverObservation> = emptyList(),
    val modifiedAtMillis: Long = 0,
    val removedSourceIds: Map<String, Long> = emptyMap(),
)

object SilverData : SourceData<SilverDataset> {
    override val descriptor = SourceDataDescriptor(
        id = "silver",
        remoteAppId = "source-silver",
        snapshotFormat = "source-silver",
        formatVersion = 2,
    )
    override val supportedFormatVersions = setOf(1, descriptor.formatVersion)
    override val emptyValue = SilverDataset()

    override fun encode(value: SilverDataset): ByteArray {
        validate(value)
        return JSONObject().apply {
            put("version", descriptor.formatVersion)
            put("modifiedAtMillis", value.modifiedAtMillis)
            put("evidence", JSONArray().apply {
                value.evidence.sortedBy(SilverEvidence::id).forEach { put(encodeEvidence(it)) }
            })
            put("observations", JSONArray().apply {
                value.observations.sortedBy(SilverObservation::id).forEach { put(encodeObservation(it)) }
            })
            put("removedSources", JSONArray().apply {
                value.removedSourceIds.toSortedMap().forEach { (sourceId, removedAtMillis) ->
                    put(JSONObject().put("bronzeSourceId", sourceId).put("removedAtMillis", removedAtMillis))
                }
            })
        }.toString().toByteArray(Charsets.UTF_8)
    }

    override fun decode(value: ByteArray): SilverDataset {
        val root = JSONObject(value.toString(Charsets.UTF_8))
        val version = root.getInt("version")
        if (version == 1) return emptyValue
        require(version == descriptor.formatVersion) { "Unsupported Silver data version" }
        val evidence = root.getJSONArray("evidence")
        val observations = root.getJSONArray("observations")
        val removedSources = root.optJSONArray("removedSources") ?: JSONArray()
        return SilverDataset(
            evidence = List(evidence.length()) { decodeEvidence(evidence.getJSONObject(it)) },
            observations = List(observations.length()) { decodeObservation(observations.getJSONObject(it)) },
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
                value.evidence.sortedBy(SilverEvidence::id).joinToString("\u0000", transform = ::evidenceContent),
                value.observations.sortedBy(SilverObservation::id)
                    .joinToString("\u0000", transform = ::observationContent),
                value.removedSourceIds.toSortedMap().entries.joinToString("\u0000") { "${it.key}\u0002${it.value}" },
            ).joinToString("\u0004"),
        )
    }

    override fun merge(local: SilverDataset, remote: SilverDataset): SilverDataset {
        validate(local)
        validate(remote)
        val removed = (local.removedSourceIds.keys + remote.removedSourceIds.keys).associateWith { sourceId ->
            maxOf(local.removedSourceIds[sourceId] ?: 0, remote.removedSourceIds[sourceId] ?: 0)
        }.filterValues { it > 0 }.toMutableMap()
        val evidence = mergeEvidence(local.evidence, remote.evidence)
        val evidenceById = evidence.associateBy(SilverEvidence::id)
        val observations = mergeObservations(local.observations, remote.observations).filter { observation ->
            observation.evidenceIds.all { evidenceId ->
                val sourceId = checkNotNull(evidenceById[evidenceId]).bronzeSourceId
                observation.createdAtMillis > (removed[sourceId] ?: 0)
            }
        }
        val activeEvidenceIds = observations.flatMapTo(mutableSetOf(), SilverObservation::evidenceIds)
        val activeEvidence = evidence.filter { it.id in activeEvidenceIds }
        activeEvidence.map(SilverEvidence::bronzeSourceId).toSet().forEach { activeSourceId ->
            val newestObservation = observations.filter { observation ->
                observation.evidenceIds.any { evidenceById[it]?.bronzeSourceId == activeSourceId }
            }.maxOfOrNull(SilverObservation::createdAtMillis) ?: 0
            removed[activeSourceId]?.let { removedAtMillis ->
                if (newestObservation > removedAtMillis) removed.remove(activeSourceId)
            }
        }
        val candidate = SilverDataset(
            evidence = activeEvidence,
            observations = observations,
            modifiedAtMillis = maxOf(local.modifiedAtMillis, remote.modifiedAtMillis) + 1,
            removedSourceIds = removed,
        )
        return when {
            sameContent(candidate, local) -> local
            sameContent(candidate, remote) -> remote
            else -> candidate
        }
    }

    internal fun encodeEvidence(evidence: SilverEvidence) = JSONObject().apply {
        put("id", evidence.id)
        put("bronzeSourceId", evidence.bronzeSourceId)
        put("bronzeContentSha256", evidence.bronzeContentSha256)
        put("selector", evidence.selector?.let(::encodeSilverJson) ?: JSONObject.NULL)
        evidence.excerpt?.let { put("excerpt", it) }
    }

    internal fun decodeEvidence(value: JSONObject) = SilverEvidence(
        id = value.getString("id"),
        bronzeSourceId = value.getString("bronzeSourceId"),
        bronzeContentSha256 = value.getString("bronzeContentSha256"),
        selector = value.opt("selector").takeUnless { it == null || it == JSONObject.NULL }?.let(::decodeSilverJson),
        excerpt = value.optString("excerpt").takeIf(String::isNotBlank),
    )

    internal fun encodeObservation(observation: SilverObservation) = JSONObject().apply {
        put("id", observation.id)
        put("kind", observation.kind)
        put("payload", encodeSilverJson(observation.payload))
        put("evidenceIds", JSONArray(observation.evidenceIds))
        put("confidence", observation.confidence ?: JSONObject.NULL)
        put("producer", JSONObject().apply {
            put("processorId", observation.producer.processorId)
            put("processorVersion", observation.producer.processorVersion)
            put("modelId", observation.producer.modelId ?: JSONObject.NULL)
            put("modelRevision", observation.producer.modelRevision ?: JSONObject.NULL)
        })
        put("createdAtMillis", observation.createdAtMillis)
    }

    internal fun decodeObservation(value: JSONObject): SilverObservation {
        val producer = value.getJSONObject("producer")
        val evidenceIds = value.getJSONArray("evidenceIds")
        return SilverObservation(
            id = value.getString("id"),
            kind = value.getString("kind"),
            payload = decodeSilverJson(value.get("payload")),
            evidenceIds = List(evidenceIds.length()) { evidenceIds.getString(it) },
            confidence = value.opt("confidence").takeUnless { it == null || it == JSONObject.NULL }?.let {
                (it as Number).toDouble()
            },
            producer = SilverProducer(
                processorId = producer.getString("processorId"),
                processorVersion = producer.getString("processorVersion"),
                modelId = producer.optString("modelId").takeIf(String::isNotBlank),
                modelRevision = producer.optString("modelRevision").takeIf(String::isNotBlank),
            ),
            createdAtMillis = value.getLong("createdAtMillis"),
        )
    }

    private fun validate(value: SilverDataset) {
        require(value.modifiedAtMillis >= 0) { "Invalid Silver modification time" }
        require(value.evidence.distinctBy(SilverEvidence::id).size == value.evidence.size) {
            "Duplicate Silver Evidence"
        }
        require(value.observations.distinctBy(SilverObservation::id).size == value.observations.size) {
            "Duplicate Silver Observations"
        }
        val evidenceIds = value.evidence.mapTo(mutableSetOf(), SilverEvidence::id)
        require(value.observations.all { evidenceIds.containsAll(it.evidenceIds) }) {
            "Silver Observations must reference stored Evidence"
        }
        val referencedEvidenceIds = value.observations.flatMapTo(mutableSetOf(), SilverObservation::evidenceIds)
        require(referencedEvidenceIds == evidenceIds) { "Silver Evidence must support an Observation" }
        require(value.removedSourceIds.keys.none { removed ->
            value.evidence.any { it.bronzeSourceId == removed }
        }) { "A Silver source cannot be both active and removed" }
        require(value.removedSourceIds.all { (sourceId, removedAtMillis) ->
            sourceId.isNotBlank() && sourceId.length <= 240 && removedAtMillis > 0
        }) { "Invalid removed Silver source" }
    }

    private fun mergeEvidence(first: List<SilverEvidence>, second: List<SilverEvidence>): List<SilverEvidence> =
        (first + second).groupBy(SilverEvidence::id).values.map { candidates ->
            candidates.reduce { selected, candidate ->
                require(evidenceIdentity(selected) == evidenceIdentity(candidate)) { "Silver Evidence ID collision" }
                when {
                    selected.excerpt == null -> candidate
                    candidate.excerpt == null -> selected
                    candidate.excerpt < selected.excerpt -> candidate
                    else -> selected
                }
            }
        }

    private fun mergeObservations(
        first: List<SilverObservation>,
        second: List<SilverObservation>,
    ): List<SilverObservation> = (first + second).groupBy(SilverObservation::id).values.map { candidates ->
        candidates.reduce { selected, candidate ->
            require(observationIdentity(selected) == observationIdentity(candidate)) { "Silver Observation ID collision" }
            if (candidate.createdAtMillis < selected.createdAtMillis) candidate else selected
        }
    }

    private fun sameContent(first: SilverDataset, second: SilverDataset): Boolean =
        first.evidence.toSet() == second.evidence.toSet() &&
            first.observations.toSet() == second.observations.toSet() &&
            first.removedSourceIds == second.removedSourceIds

    private fun evidenceIdentity(evidence: SilverEvidence) = listOf(
        evidence.id,
        evidence.bronzeSourceId,
        evidence.bronzeContentSha256,
        evidence.selector,
    )

    private fun observationIdentity(observation: SilverObservation) = listOf(
        observation.id,
        observation.kind,
        observation.payload,
        observation.evidenceIds,
        observation.confidence,
        observation.producer,
    )

    private fun evidenceContent(evidence: SilverEvidence): String = canonicalSilverJson(SilverJsonObject(mapOf(
        "bronzeContentSha256" to SilverJsonString(evidence.bronzeContentSha256),
        "bronzeSourceId" to SilverJsonString(evidence.bronzeSourceId),
        "excerpt" to (evidence.excerpt?.let(::SilverJsonString) ?: SilverJsonNull),
        "id" to SilverJsonString(evidence.id),
        "selector" to (evidence.selector ?: SilverJsonNull),
    ))).toString(Charsets.UTF_8)

    private fun observationContent(observation: SilverObservation): String = canonicalSilverJson(
        SilverJsonObject(mapOf(
            "confidence" to (observation.confidence?.let(::SilverJsonNumber) ?: SilverJsonNull),
            "createdAtMillis" to SilverJsonString(observation.createdAtMillis.toString()),
            "evidenceIds" to SilverJsonArray(observation.evidenceIds.map(::SilverJsonString)),
            "id" to SilverJsonString(observation.id),
            "kind" to SilverJsonString(observation.kind),
            "payload" to observation.payload,
            "producer" to producerIdentity(observation.producer),
        )),
    ).toString(Charsets.UTF_8)
}
