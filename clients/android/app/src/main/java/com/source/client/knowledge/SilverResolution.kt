package com.source.client.knowledge

import com.source.client.storage.SilverClaim
import com.source.client.storage.SilverClaimState
import com.source.client.storage.SilverDataset
import com.source.client.storage.SilverEntity
import com.source.client.storage.SilverJsonBoolean
import com.source.client.storage.SilverJsonNull
import com.source.client.storage.SilverJsonNumber
import com.source.client.storage.SilverJsonObject
import com.source.client.storage.SilverJsonString
import com.source.client.storage.SilverJsonValue
import com.source.client.storage.SilverObservation
import com.source.client.storage.SilverProducer
import com.source.client.storage.SilverScalar
import java.util.Locale
import java.util.UUID

internal data class SilverResolutionResult(
    val observations: List<SilverObservation>,
    val entities: List<SilverEntity>,
    val claims: List<SilverClaim>,
)

internal interface SilverObservationResolver {
    val producer: SilverProducer

    fun resolve(
        dataset: SilverDataset,
        observations: Collection<SilverObservation>,
        createdAtMillis: Long,
    ): SilverResolutionResult
}

/**
 * Resolves only exact, typed name matches. A same-name conflict is left unresolved instead of
 * guessing, so later resolver versions or explicit corrections can reinterpret the Observation.
 */
internal class ConservativeSilverResolver(
    override val producer: SilverProducer = SilverProducer.create(
        SILVER_RESOLUTION_PROCESSOR_ID,
        SILVER_RESOLUTION_PROCESSOR_VERSION,
    ),
) : SilverObservationResolver {
    override fun resolve(
        dataset: SilverDataset,
        observations: Collection<SilverObservation>,
        createdAtMillis: Long,
    ): SilverResolutionResult {
        require(createdAtMillis > 0) { "Invalid Silver resolution time" }
        val storedObservationIds = dataset.observations.mapTo(mutableSetOf(), SilverObservation::id)
        require(observations.all { it.id in storedObservationIds }) {
            "Silver resolution inputs must be stored Observations"
        }

        val profiles = entityProfiles(dataset).toMutableList()
        val resolutionObservations = linkedMapOf<String, SilverObservation>()
        val createdEntities = linkedMapOf<String, SilverEntity>()
        val createdClaims = linkedMapOf<String, SilverClaim>()

        observations.sortedBy(SilverObservation::id).forEach { observation ->
            val candidate = parseCandidate(observation)
            if (candidate == null) {
                if (observation.kind in RESOLVABLE_OBSERVATION_KINDS) {
                    resolutionObservation(
                        observation,
                        role = "candidate",
                        mention = null,
                        entityId = null,
                        createdAtMillis = createdAtMillis,
                    ).let { resolutionObservations[it.id] = it }
                }
                return@forEach
            }

            val subject = resolveMention(
                input = observation,
                role = "subject",
                mention = candidate.subject,
                profiles = profiles,
                createdAtMillis = createdAtMillis,
            )
            resolutionObservations[subject.observation.id] = subject.observation
            subject.entity?.let { createdEntities[it.id] = it }
            subject.profile?.let(profiles::add)
            subject.entityId?.let { entityId ->
                metadataClaims(entityId, candidate.subject, observation, subject.observation, createdAtMillis)
                    .forEach { createdClaims[it.id] = it }
            }

            val objectResolution = candidate.objectMention?.let { mention ->
                resolveMention(
                    input = observation,
                    role = "object",
                    mention = mention,
                    profiles = profiles,
                    createdAtMillis = createdAtMillis,
                ).also { result ->
                    resolutionObservations[result.observation.id] = result.observation
                    result.entity?.let { createdEntities[it.id] = it }
                    result.profile?.let(profiles::add)
                    result.entityId?.let { entityId ->
                        metadataClaims(entityId, mention, observation, result.observation, createdAtMillis)
                            .forEach { createdClaims[it.id] = it }
                    }
                }
            }

            if (subject.entityId != null && (candidate.scalar != null || objectResolution?.entityId != null)) {
                val decisionIds = listOfNotNull(
                    subject.observation.id,
                    objectResolution?.observation?.id,
                )
                SilverClaim.create(
                    subjectEntityId = subject.entityId,
                    predicate = candidate.predicate,
                    objectEntityId = objectResolution?.entityId,
                    value = candidate.scalar,
                    supportingObservationIds = listOf(observation.id) + decisionIds,
                    producer = producer,
                    createdAtMillis = createdAtMillis,
                ).let { createdClaims[it.id] = it }
            }
        }

        return SilverResolutionResult(
            observations = resolutionObservations.values.toList(),
            entities = createdEntities.values.toList(),
            claims = createdClaims.values.toList(),
        )
    }

    private fun resolveMention(
        input: SilverObservation,
        role: String,
        mention: EntityMention,
        profiles: List<EntityProfile>,
        createdAtMillis: Long,
    ): MentionResolution {
        val key = mention.key()
        val sameName = profiles.filter { key.name in it.names }
        val exact = sameName.filter { key.type in it.types }
        val existingEntityId = exact.singleOrNull()?.entityId
        val createEntity = sameName.isEmpty()
        val prospectiveEntityId = existingEntityId ?: if (createEntity) UUID.randomUUID().toString() else null
        val decision = resolutionObservation(
            input = input,
            role = role,
            mention = mention,
            entityId = prospectiveEntityId,
            createdAtMillis = createdAtMillis,
        )
        if (prospectiveEntityId == null) return MentionResolution(decision, null, null, null)
        if (existingEntityId != null) return MentionResolution(decision, existingEntityId, null, null)

        val entity = SilverEntity(
            id = prospectiveEntityId,
            originObservationIds = listOf(decision.id),
            createdBy = producer,
            createdAtMillis = createdAtMillis,
        )
        return MentionResolution(
            observation = decision,
            entityId = entity.id,
            entity = entity,
            profile = EntityProfile(entity.id, setOf(key.name), setOf(key.type)),
        )
    }

    private fun resolutionObservation(
        input: SilverObservation,
        role: String,
        mention: EntityMention?,
        entityId: String?,
        createdAtMillis: Long,
    ) = SilverObservation.create(
        kind = SILVER_RESOLUTION_KIND,
        payload = SilverJsonObject(mapOf(
            "entityId" to (entityId?.let(::SilverJsonString) ?: SilverJsonNull),
            "inputObservationId" to SilverJsonString(input.id),
            "mentionName" to (mention?.name?.let(::SilverJsonString) ?: SilverJsonNull),
            "mentionRole" to SilverJsonString(role),
            "mentionType" to (mention?.type?.let(::SilverJsonString) ?: SilverJsonNull),
            "outcome" to SilverJsonString(if (entityId == null) "unresolved" else "resolved"),
        )),
        evidenceIds = input.evidenceIds,
        producer = producer,
        createdAtMillis = createdAtMillis,
    )

    private fun metadataClaims(
        entityId: String,
        mention: EntityMention,
        input: SilverObservation,
        decision: SilverObservation,
        createdAtMillis: Long,
    ): List<SilverClaim> = listOf(
        SilverClaim.create(
            subjectEntityId = entityId,
            predicate = SILVER_NAME_PREDICATE,
            value = SilverScalar.text(mention.name),
            supportingObservationIds = listOf(input.id, decision.id),
            producer = producer,
            createdAtMillis = createdAtMillis,
        ),
        SilverClaim.create(
            subjectEntityId = entityId,
            predicate = SILVER_ENTITY_TYPE_PREDICATE,
            value = SilverScalar.text(mention.type),
            supportingObservationIds = listOf(input.id, decision.id),
            producer = producer,
            createdAtMillis = createdAtMillis,
        ),
    )
}

private data class EntityMention(val name: String, val type: String) {
    fun key() = MentionKey(normalizeMatchText(name), normalizeMatchText(type))
}

private data class MentionKey(val name: String, val type: String)

private data class Candidate(
    val subject: EntityMention,
    val predicate: String,
    val objectMention: EntityMention? = null,
    val scalar: SilverScalar? = null,
)

private data class EntityProfile(
    val entityId: String,
    val names: Set<String>,
    val types: Set<String>,
)

private data class MentionResolution(
    val observation: SilverObservation,
    val entityId: String?,
    val entity: SilverEntity?,
    val profile: EntityProfile?,
)

private fun entityProfiles(dataset: SilverDataset): List<EntityProfile> {
    val activeClaims = dataset.claims.filter { it.state == SilverClaimState.ACTIVE }
    return dataset.entities.map { entity ->
        val claims = activeClaims.filter { it.subjectEntityId == entity.id }
        EntityProfile(
            entityId = entity.id,
            names = claims.textValues(SILVER_NAME_PREDICATE).mapTo(mutableSetOf(), ::normalizeMatchText),
            types = claims.textValues(SILVER_ENTITY_TYPE_PREDICATE).mapTo(mutableSetOf(), ::normalizeMatchText),
        )
    }
}

private fun List<SilverClaim>.textValues(predicate: String): List<String> = mapNotNull { claim ->
    if (claim.predicate != predicate) return@mapNotNull null
    (claim.value?.value as? SilverJsonString)?.value
}

private fun parseCandidate(observation: SilverObservation): Candidate? {
    val payload = (observation.payload as? SilverJsonObject)?.properties ?: return null
    val subject = parseMention(payload["subject"]) ?: return null
    val predicate = (payload["predicate"] as? SilverJsonString)?.value?.takeIf(String::isNotBlank) ?: return null
    return when (observation.kind) {
        SILVER_ATTRIBUTE_CANDIDATE_KIND -> Candidate(
            subject = subject,
            predicate = predicate,
            scalar = parseScalar(payload["value"]) ?: return null,
        )
        SILVER_RELATIONSHIP_CANDIDATE_KIND -> Candidate(
            subject = subject,
            predicate = predicate,
            objectMention = parseMention(payload["object"]) ?: return null,
        )
        else -> null
    }
}

private fun parseMention(value: SilverJsonValue?): EntityMention? {
    val properties = (value as? SilverJsonObject)?.properties ?: return null
    val name = (properties["name"] as? SilverJsonString)?.value?.takeIf(String::isNotBlank) ?: return null
    val type = (properties["type"] as? SilverJsonString)?.value?.takeIf(String::isNotBlank) ?: return null
    return EntityMention(name, type)
}

private fun parseScalar(value: SilverJsonValue?): SilverScalar? = when (value) {
    is SilverJsonString -> SilverScalar.text(value.value)
    is SilverJsonNumber -> SilverScalar.number(value.value)
    is SilverJsonBoolean -> SilverScalar.boolean(value.value)
    else -> null
}

private fun normalizeMatchText(value: String): String = value
    .trim()
    .replace(Regex("\\s+"), " ")
    .lowercase(Locale.ROOT)

internal const val SILVER_RESOLUTION_PROCESSOR_ID = "source.android.silver-resolution"
internal const val SILVER_RESOLUTION_PROCESSOR_VERSION = "1"
internal const val SILVER_RESOLUTION_KIND = "entity-resolution"
internal const val SILVER_NAME_PREDICATE = "name"
internal const val SILVER_ENTITY_TYPE_PREDICATE = "entity-type"
private val RESOLVABLE_OBSERVATION_KINDS = setOf(
    SILVER_ATTRIBUTE_CANDIDATE_KIND,
    SILVER_RELATIONSHIP_CANDIDATE_KIND,
)
