package com.source.client.storage

import org.json.JSONArray
import org.json.JSONObject

data class SilverDataset(
    val evidence: List<SilverEvidence> = emptyList(),
    val observations: List<SilverObservation> = emptyList(),
    val modifiedAtMillis: Long = 0,
    val removedSourceIds: Map<String, Long> = emptyMap(),
    val entities: List<SilverEntity> = emptyList(),
    val claims: List<SilverClaim> = emptyList(),
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
            put("evidence", JSONArray().apply {
                value.evidence.sortedBy(SilverEvidence::id).forEach { put(encodeEvidence(it)) }
            })
            put("observations", JSONArray().apply {
                value.observations.sortedBy(SilverObservation::id).forEach { put(encodeObservation(it)) }
            })
            put("entities", JSONArray().apply {
                value.entities.sortedBy(SilverEntity::id).forEach { put(encodeEntity(it)) }
            })
            put("claims", JSONArray().apply {
                value.claims.sortedBy(SilverClaim::id).forEach { put(encodeClaim(it)) }
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
        require(version == descriptor.formatVersion) { "Unsupported Silver data version" }
        val evidence = root.getJSONArray("evidence")
        val observations = root.getJSONArray("observations")
        val entities = root.getJSONArray("entities")
        val claims = root.getJSONArray("claims")
        val removedSources = root.getJSONArray("removedSources")
        return SilverDataset(
            evidence = List(evidence.length()) { decodeEvidence(evidence.getJSONObject(it)) },
            observations = List(observations.length()) { decodeObservation(observations.getJSONObject(it)) },
            entities = List(entities.length()) { decodeEntity(entities.getJSONObject(it)) },
            claims = List(claims.length()) { decodeClaim(claims.getJSONObject(it)) },
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
                value.entities.sortedBy(SilverEntity::id).joinToString("\u0000", transform = ::entityContent),
                value.claims.sortedBy(SilverClaim::id).joinToString("\u0000", transform = ::claimContent),
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
        val observationIds = observations.mapTo(mutableSetOf(), SilverObservation::id)
        val entities = mergeEntities(local.entities, remote.entities).filter { entity ->
            observationIds.containsAll(entity.originObservationIds)
        }
        val entityIds = entities.mapTo(mutableSetOf(), SilverEntity::id)
        val claims = mergeClaims(local.claims, remote.claims).filter { claim ->
            observationIds.containsAll(claim.supportingObservationIds) &&
                claim.subjectEntityId in entityIds &&
                (claim.objectEntityId == null || claim.objectEntityId in entityIds)
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
            entities = entities,
            claims = claims,
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
        selector = value.get("selector").takeUnless { it == JSONObject.NULL }?.let(::decodeSilverJson),
        excerpt = value.optString("excerpt").takeIf(String::isNotBlank),
    )

    internal fun encodeObservation(observation: SilverObservation) = JSONObject().apply {
        put("id", observation.id)
        put("kind", observation.kind)
        put("payload", encodeSilverJson(observation.payload))
        put("evidenceIds", JSONArray(observation.evidenceIds))
        put("confidence", observation.confidence ?: JSONObject.NULL)
        put("producer", encodeProducer(observation.producer))
        put("createdAtMillis", observation.createdAtMillis)
    }

    internal fun decodeObservation(value: JSONObject): SilverObservation {
        val evidenceIds = value.getJSONArray("evidenceIds")
        return SilverObservation(
            id = value.getString("id"),
            kind = value.getString("kind"),
            payload = decodeSilverJson(value.get("payload")),
            evidenceIds = List(evidenceIds.length()) { evidenceIds.getString(it) },
            confidence = value.get("confidence").takeUnless { it == JSONObject.NULL }?.let {
                (it as Number).toDouble()
            },
            producer = decodeProducer(value.getJSONObject("producer")),
            createdAtMillis = value.getLong("createdAtMillis"),
        )
    }

    internal fun encodeEntity(entity: SilverEntity) = JSONObject().apply {
        put("id", entity.id)
        put("originObservationIds", JSONArray(entity.originObservationIds))
        put("createdBy", encodeProducer(entity.createdBy))
        put("createdAtMillis", entity.createdAtMillis)
    }

    internal fun decodeEntity(value: JSONObject): SilverEntity {
        val originObservationIds = value.getJSONArray("originObservationIds")
        return SilverEntity(
            id = value.getString("id"),
            originObservationIds = List(originObservationIds.length()) { originObservationIds.getString(it) },
            createdBy = decodeProducer(value.getJSONObject("createdBy")),
            createdAtMillis = value.getLong("createdAtMillis"),
        )
    }

    internal fun encodeClaim(claim: SilverClaim) = JSONObject().apply {
        put("id", claim.id)
        put("subjectEntityId", claim.subjectEntityId)
        put("predicate", claim.predicate)
        put("objectEntityId", claim.objectEntityId ?: JSONObject.NULL)
        put("value", claim.value?.let(::encodeScalar) ?: JSONObject.NULL)
        put("supportingObservationIds", JSONArray(claim.supportingObservationIds))
        put("confidence", claim.confidence ?: JSONObject.NULL)
        put("producer", encodeProducer(claim.producer))
        put("state", claim.state.storageValue)
        put("createdAtMillis", claim.createdAtMillis)
    }

    internal fun decodeClaim(value: JSONObject): SilverClaim {
        val supportingObservationIds = value.getJSONArray("supportingObservationIds")
        return SilverClaim(
            id = value.getString("id"),
            subjectEntityId = value.getString("subjectEntityId"),
            predicate = value.getString("predicate"),
            objectEntityId = value.get("objectEntityId").takeUnless { it == JSONObject.NULL } as? String,
            value = value.get("value").takeUnless { it == JSONObject.NULL }
                ?.let { decodeScalar(it as JSONObject) },
            supportingObservationIds = List(supportingObservationIds.length()) {
                supportingObservationIds.getString(it)
            },
            confidence = value.get("confidence").takeUnless { it == JSONObject.NULL }
                ?.let { (it as Number).toDouble() },
            producer = decodeProducer(value.getJSONObject("producer")),
            state = SilverClaimState.fromStorageValue(value.getString("state")),
            createdAtMillis = value.getLong("createdAtMillis"),
        )
    }

    private fun encodeScalar(scalar: SilverScalar) = JSONObject().apply {
        put("type", scalar.type.storageValue)
        put("value", encodeSilverJson(scalar.value))
    }

    private fun decodeScalar(value: JSONObject) = SilverScalar(
        type = SilverScalarType.fromStorageValue(value.getString("type")),
        value = decodeSilverJson(value.get("value")),
    )

    private fun encodeProducer(producer: SilverProducer) = JSONObject().apply {
        put("processorId", producer.processorId)
        put("processorVersion", producer.processorVersion)
        put("modelId", producer.modelId ?: JSONObject.NULL)
        put("modelRevision", producer.modelRevision ?: JSONObject.NULL)
    }

    private fun decodeProducer(producer: JSONObject) = SilverProducer(
        processorId = producer.getString("processorId"),
        processorVersion = producer.getString("processorVersion"),
        modelId = producer.get("modelId").takeUnless { it == JSONObject.NULL } as? String,
        modelRevision = producer.get("modelRevision").takeUnless { it == JSONObject.NULL } as? String,
    )

    private fun validate(value: SilverDataset) {
        require(value.modifiedAtMillis >= 0) { "Invalid Silver modification time" }
        require(value.evidence.distinctBy(SilverEvidence::id).size == value.evidence.size) {
            "Duplicate Silver Evidence"
        }
        require(value.observations.distinctBy(SilverObservation::id).size == value.observations.size) {
            "Duplicate Silver Observations"
        }
        require(value.entities.distinctBy(SilverEntity::id).size == value.entities.size) {
            "Duplicate Silver Entities"
        }
        require(value.claims.distinctBy(SilverClaim::id).size == value.claims.size) {
            "Duplicate Silver Claims"
        }
        val evidenceIds = value.evidence.mapTo(mutableSetOf(), SilverEvidence::id)
        require(value.observations.all { evidenceIds.containsAll(it.evidenceIds) }) {
            "Silver Observations must reference stored Evidence"
        }
        val referencedEvidenceIds = value.observations.flatMapTo(mutableSetOf(), SilverObservation::evidenceIds)
        require(referencedEvidenceIds == evidenceIds) { "Silver Evidence must support an Observation" }
        val observationIds = value.observations.mapTo(mutableSetOf(), SilverObservation::id)
        require(value.entities.all { observationIds.containsAll(it.originObservationIds) }) {
            "Silver Entities must reference stored origin Observations"
        }
        val entityIds = value.entities.mapTo(mutableSetOf(), SilverEntity::id)
        require(value.claims.all { claim ->
            observationIds.containsAll(claim.supportingObservationIds) &&
                claim.subjectEntityId in entityIds &&
                (claim.objectEntityId == null || claim.objectEntityId in entityIds)
        }) { "Silver Claims must reference stored Entities and supporting Observations" }
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

    private fun mergeEntities(first: List<SilverEntity>, second: List<SilverEntity>): List<SilverEntity> =
        (first + second).groupBy(SilverEntity::id).values.map { candidates ->
            candidates.reduce { selected, candidate ->
                require(selected == candidate) { "Silver Entity ID collision" }
                selected
            }
        }

    private fun mergeClaims(first: List<SilverClaim>, second: List<SilverClaim>): List<SilverClaim> =
        (first + second).groupBy(SilverClaim::id).values.map { candidates ->
            candidates.reduce { selected, candidate ->
                require(claimIdentity(selected) == claimIdentity(candidate)) { "Silver Claim ID collision" }
                val selectedRank = claimStateRank(selected.state)
                val candidateRank = claimStateRank(candidate.state)
                selected.copy(
                    state = if (candidateRank > selectedRank) candidate.state else selected.state,
                    createdAtMillis = minOf(selected.createdAtMillis, candidate.createdAtMillis),
                )
            }
        }

    private fun sameContent(first: SilverDataset, second: SilverDataset): Boolean =
        first.evidence.toSet() == second.evidence.toSet() &&
            first.observations.toSet() == second.observations.toSet() &&
            first.entities.toSet() == second.entities.toSet() &&
            first.claims.toSet() == second.claims.toSet() &&
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

    private fun claimIdentity(claim: SilverClaim) = listOf(
        claim.id,
        claim.subjectEntityId,
        claim.predicate,
        claim.objectEntityId,
        claim.value,
        claim.supportingObservationIds,
        claim.confidence,
        claim.producer,
    )

    private fun claimStateRank(state: SilverClaimState): Int = when (state) {
        SilverClaimState.ACTIVE -> 0
        SilverClaimState.SUPERSEDED -> 1
        SilverClaimState.RETRACTED -> 2
    }

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

    private fun entityContent(entity: SilverEntity): String = canonicalSilverJson(SilverJsonObject(mapOf(
        "createdAtMillis" to SilverJsonString(entity.createdAtMillis.toString()),
        "createdBy" to producerIdentity(entity.createdBy),
        "id" to SilverJsonString(entity.id),
        "originObservationIds" to SilverJsonArray(entity.originObservationIds.map(::SilverJsonString)),
    ))).toString(Charsets.UTF_8)

    private fun claimContent(claim: SilverClaim): String = canonicalSilverJson(SilverJsonObject(mapOf(
        "confidence" to (claim.confidence?.let(::SilverJsonNumber) ?: SilverJsonNull),
        "createdAtMillis" to SilverJsonString(claim.createdAtMillis.toString()),
        "id" to SilverJsonString(claim.id),
        "object" to claimObjectIdentity(claim.objectEntityId, claim.value),
        "predicate" to SilverJsonString(claim.predicate),
        "producer" to producerIdentity(claim.producer),
        "state" to SilverJsonString(claim.state.storageValue),
        "subjectEntityId" to SilverJsonString(claim.subjectEntityId),
        "supportingObservationIds" to SilverJsonArray(claim.supportingObservationIds.map(::SilverJsonString)),
    ))).toString(Charsets.UTF_8)
}
