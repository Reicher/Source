package com.source.client.knowledge

import com.source.client.storage.SilverClaim
import com.source.client.storage.SilverClaimState
import com.source.client.storage.SilverData
import com.source.client.storage.SilverDataset
import com.source.client.storage.SilverEntity
import com.source.client.storage.SilverEvidence
import com.source.client.storage.SilverObservation
import com.source.client.storage.SilverProducer

internal data class SilverGenerationCommit(
    val dataset: SilverDataset,
    val supersededClaimIds: Set<String>,
)

/**
 * Builds and validates one complete Silver generation before the caller exposes or persists it.
 * Previous records remain in the snapshot; only replaceable active Claims change lifecycle state.
 */
internal fun replaceSilverGeneration(
    dataset: SilverDataset,
    source: BronzeTextSource,
    extracted: ExtractedSilver,
    resolver: SilverObservationResolver,
    committedAtMillis: Long,
): SilverGenerationCommit {
    require(committedAtMillis > dataset.modifiedAtMillis) {
        "A Silver generation must advance the dataset modification time"
    }
    SilverData.version(dataset)
    val extractionProducer = validateCompleteGeneration(source, extracted)

    val evidence = mergeEvidence(dataset.evidence, extracted.evidence)
    val observations = mergeObservations(dataset.observations, extracted.observations)
    val unresolved = dataset.copy(
        evidence = evidence,
        observations = observations,
        modifiedAtMillis = committedAtMillis,
        removedSourceIds = dataset.removedSourceIds - source.id,
    )
    val resolved = resolver.resolve(unresolved, extracted.observations, committedAtMillis)
    val freshClaimIds = resolved.claims.mapTo(mutableSetOf(), SilverClaim::id)
    val oldObservationsById = dataset.observations.associateBy(SilverObservation::id)
    val oldEvidenceById = dataset.evidence.associateBy(SilverEvidence::id)
    val supersededClaimIds = dataset.claims.asSequence()
        .filter { it.state == SilverClaimState.ACTIVE && it.id !in freshClaimIds }
        .filter { claim ->
            claim.isDerivedFrom(source.id, extractionProducer.processorId, oldObservationsById, oldEvidenceById)
        }
        .mapTo(mutableSetOf(), SilverClaim::id)
    val historicalClaims = dataset.claims.map { claim ->
        if (claim.id in supersededClaimIds) claim.copy(state = SilverClaimState.SUPERSEDED) else claim
    }
    val candidate = unresolved.copy(
        entities = mergeEntities(dataset.entities, resolved.entities),
        claims = mergeClaims(historicalClaims, resolved.claims),
    )
    val candidateVersion = SilverData.version(candidate)
    val currentVersion = SilverData.version(dataset)
    return if (candidateVersion.contentIdentity == currentVersion.contentIdentity) {
        SilverGenerationCommit(dataset, emptySet())
    } else {
        SilverGenerationCommit(candidate, supersededClaimIds)
    }
}

private fun validateCompleteGeneration(
    source: BronzeTextSource,
    extracted: ExtractedSilver,
): SilverProducer {
    require(extracted.evidence.isNotEmpty()) { "A Silver generation must contain Evidence" }
    require(extracted.observations.isNotEmpty()) { "A Silver generation must contain Observations" }
    require(extracted.evidence.all {
        it.bronzeSourceId == source.id && it.bronzeContentSha256 == source.contentSha256
    }) { "Silver generation Evidence does not match its Bronze revision" }
    val evidenceIds = extracted.evidence.mapTo(mutableSetOf(), SilverEvidence::id)
    require(extracted.evidence.distinctBy(SilverEvidence::id).size == extracted.evidence.size) {
        "Duplicate Silver generation Evidence"
    }
    require(extracted.observations.distinctBy(SilverObservation::id).size == extracted.observations.size) {
        "Duplicate Silver generation Observations"
    }
    require(extracted.observations.all { observation ->
        observation.evidenceIds.isNotEmpty() && evidenceIds.containsAll(observation.evidenceIds)
    }) { "Silver generation Observations must reference only their generation Evidence" }
    require(extracted.observations.flatMapTo(mutableSetOf(), SilverObservation::evidenceIds) == evidenceIds) {
        "Every Silver generation Evidence record must support an Observation"
    }
    val producers = extracted.observations.map(SilverObservation::producer).distinct()
    require(producers.size == 1) { "A Silver generation cannot mix extraction producers" }
    val producer = producers.single()
    require(producer.modelId == extracted.model.modelId) {
        "Silver generation model metadata does not match its Observations"
    }
    require(extracted.observations.any { it.kind == SILVER_EXTRACTION_COMPLETE_KIND }) {
        "An incomplete Silver generation cannot be committed"
    }
    return producer
}

private fun SilverClaim.isDerivedFrom(
    sourceId: String,
    extractionProcessorId: String,
    observationsById: Map<String, SilverObservation>,
    evidenceById: Map<String, SilverEvidence>,
): Boolean = supportingObservationIds.any { observationId ->
    val observation = observationsById[observationId] ?: return@any false
    observation.producer.processorId == extractionProcessorId && observation.evidenceIds.any { evidenceId ->
        evidenceById[evidenceId]?.bronzeSourceId == sourceId
    }
}

private fun mergeEvidence(
    stored: List<SilverEvidence>,
    fresh: List<SilverEvidence>,
): List<SilverEvidence> = (stored + fresh).groupBy(SilverEvidence::id).values.map { candidates ->
    candidates.reduce { selected, candidate ->
        require(
            selected.bronzeSourceId == candidate.bronzeSourceId &&
                selected.bronzeContentSha256 == candidate.bronzeContentSha256 &&
                selected.selector == candidate.selector,
        ) { "Silver Evidence ID collision while committing a generation" }
        when {
            selected.excerpt == null -> candidate
            candidate.excerpt == null -> selected
            candidate.excerpt < selected.excerpt -> candidate
            else -> selected
        }
    }
}.sortedBy(SilverEvidence::id)

private fun mergeObservations(
    stored: List<SilverObservation>,
    fresh: List<SilverObservation>,
): List<SilverObservation> = (stored + fresh).groupBy(SilverObservation::id).values.map { candidates ->
    candidates.reduce { selected, candidate ->
        require(
            selected.kind == candidate.kind &&
                selected.payload == candidate.payload &&
                selected.evidenceIds == candidate.evidenceIds &&
                selected.confidence == candidate.confidence &&
                selected.producer == candidate.producer,
        ) { "Silver Observation ID collision while committing a generation" }
        if (candidate.createdAtMillis < selected.createdAtMillis) candidate else selected
    }
}.sortedBy(SilverObservation::id)

private fun mergeEntities(
    stored: List<SilverEntity>,
    fresh: List<SilverEntity>,
): List<SilverEntity> = (stored + fresh).groupBy(SilverEntity::id).values.map { candidates ->
    candidates.reduce { selected, candidate ->
        require(selected == candidate) { "Silver Entity ID collision while committing a generation" }
        selected
    }
}.sortedBy(SilverEntity::id)

private fun mergeClaims(
    stored: List<SilverClaim>,
    fresh: List<SilverClaim>,
): List<SilverClaim> = (stored + fresh).groupBy(SilverClaim::id).values.map { candidates ->
    candidates.reduce { selected, candidate ->
        require(
            selected.subjectEntityId == candidate.subjectEntityId &&
                selected.predicate == candidate.predicate &&
                selected.objectEntityId == candidate.objectEntityId &&
                selected.value == candidate.value &&
                selected.supportingObservationIds == candidate.supportingObservationIds &&
                selected.confidence == candidate.confidence &&
                selected.producer == candidate.producer,
        ) { "Silver Claim ID collision while committing a generation" }
        val state = if (selected.state.rank() >= candidate.state.rank()) selected.state else candidate.state
        selected.copy(state = state, createdAtMillis = minOf(selected.createdAtMillis, candidate.createdAtMillis))
    }
}.sortedBy(SilverClaim::id)

private fun SilverClaimState.rank(): Int = when (this) {
    SilverClaimState.ACTIVE -> 0
    SilverClaimState.SUPERSEDED -> 1
    SilverClaimState.RETRACTED -> 2
}
