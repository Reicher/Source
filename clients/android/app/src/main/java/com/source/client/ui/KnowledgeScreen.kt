package com.source.client.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.source.client.R
import com.source.client.knowledge.SILVER_ATTRIBUTE_CANDIDATE_KIND
import com.source.client.knowledge.SILVER_ENTITY_TYPE_PREDICATE
import com.source.client.knowledge.SILVER_NAME_PREDICATE
import com.source.client.knowledge.SILVER_RELATIONSHIP_CANDIDATE_KIND
import com.source.client.knowledge.SILVER_RESOLUTION_KIND
import com.source.client.storage.SilverClaim
import com.source.client.storage.SilverClaimState
import com.source.client.storage.SilverDataset
import com.source.client.storage.SilverEntity
import com.source.client.storage.SilverEvidence
import com.source.client.storage.SilverJsonArray
import com.source.client.storage.SilverJsonBoolean
import com.source.client.storage.SilverJsonNull
import com.source.client.storage.SilverJsonNumber
import com.source.client.storage.SilverJsonObject
import com.source.client.storage.SilverJsonString
import com.source.client.storage.SilverJsonValue
import com.source.client.storage.SilverObservation
import com.source.client.storage.SilverProducer
import com.source.client.storage.SilverScalar

data class SilverInspectorProducerUi(
    val processorId: String,
    val processorVersion: String,
    val modelId: String?,
    val modelRevision: String?,
)

enum class SilverInspectorObservationStatus { RESOLVED, PARTIAL, UNRESOLVED, TRACE }

data class SilverInspectorEvidenceUi(
    val id: String,
    val contentSha256: String,
    val selector: String?,
    val excerpt: String?,
)

data class SilverInspectorObservationUi(
    val id: String,
    val kind: String,
    val status: SilverInspectorObservationStatus,
    val payload: String,
    val confidence: Double?,
    val evidenceIds: List<String>,
    val producer: SilverInspectorProducerUi,
)

data class SilverInspectorEntityUi(
    val id: String,
    val name: String,
    val type: String,
    val originObservationIds: List<String>,
    val producer: SilverInspectorProducerUi,
)

data class SilverInspectorClaimUi(
    val id: String,
    val subjectName: String,
    val predicate: String,
    val objectDisplay: String,
    val state: String,
    val confidence: Double?,
    val competing: Boolean,
    val supportingObservationIds: List<String>,
    val producer: SilverInspectorProducerUi,
)

data class SilverInspectorSourceUi(
    val id: String,
    val name: String,
    val sourceType: String,
    val canOpenSource: Boolean,
    val evidence: List<SilverInspectorEvidenceUi>,
    val observations: List<SilverInspectorObservationUi>,
    val entities: List<SilverInspectorEntityUi>,
    val claims: List<SilverInspectorClaimUi>,
) {
    val unresolvedCount: Int
        get() = observations.count {
            it.status == SilverInspectorObservationStatus.UNRESOLVED ||
                it.status == SilverInspectorObservationStatus.PARTIAL
        }
    val competingCount: Int get() = claims.count(SilverInspectorClaimUi::competing)
}

data class KnowledgeUiState(val sources: List<SilverInspectorSourceUi> = emptyList())

internal fun buildKnowledgeUiState(
    silver: SilverDataset,
    library: LibraryUiState,
): KnowledgeUiState {
    val libraryById = library.items.associateBy(LibraryUiItem::id)
    val sourceIds = (libraryById.keys + silver.evidence.map(SilverEvidence::bronzeSourceId)).toSortedSet()
    val entitiesById = silver.entities.associateBy(SilverEntity::id)
    val activeClaims = silver.claims.filter { it.state == SilverClaimState.ACTIVE }
    val entityNames = silver.entities.associate { entity ->
        entity.id to preferredClaimText(activeClaims, entity.id, SILVER_NAME_PREDICATE, shortId(entity.id))
    }
    val entityTypes = silver.entities.associate { entity ->
        entity.id to preferredClaimText(activeClaims, entity.id, SILVER_ENTITY_TYPE_PREDICATE, "unknown")
    }
    val competingClaimIds = competingClaimIds(activeClaims)
    val resolutionByInput = silver.observations.filter { it.kind == SILVER_RESOLUTION_KIND }
        .mapNotNull { decision -> decision.inputObservationId()?.let { it to decision } }
        .groupBy({ it.first }, { it.second })

    return KnowledgeUiState(sourceIds.map { sourceId ->
        val libraryItem = libraryById[sourceId]
        val evidence = silver.evidence.filter { it.bronzeSourceId == sourceId }.sortedBy(SilverEvidence::id)
        val evidenceIds = evidence.mapTo(mutableSetOf(), SilverEvidence::id)
        val observations = silver.observations.filter { observation ->
            observation.evidenceIds.any(evidenceIds::contains)
        }.sortedWith(compareBy(SilverObservation::createdAtMillis, SilverObservation::kind, SilverObservation::id))
        val observationIds = observations.mapTo(mutableSetOf(), SilverObservation::id)
        val claims = silver.claims.filter { claim ->
            claim.supportingObservationIds.any(observationIds::contains)
        }.sortedWith(compareBy(SilverClaim::subjectEntityId, SilverClaim::predicate, SilverClaim::id))
        val entityIds = buildSet {
            claims.forEach { claim ->
                add(claim.subjectEntityId)
                claim.objectEntityId?.let(::add)
            }
            silver.entities.filter { entity -> entity.originObservationIds.any(observationIds::contains) }
                .mapTo(this, SilverEntity::id)
            observations.filter { it.kind == SILVER_RESOLUTION_KIND }
                .mapNotNullTo(this) { it.resolvedEntityId() }
        }
        SilverInspectorSourceUi(
            id = sourceId,
            name = libraryItem?.filename ?: sourceId,
            sourceType = libraryItem?.sourceType ?: "unknown",
            canOpenSource = libraryItem?.previewKind != null,
            evidence = evidence.map(::evidenceUi),
            observations = observations.map { observation -> observationUi(observation, resolutionByInput) },
            entities = entityIds.mapNotNull(entitiesById::get).sortedBy(SilverEntity::id).map { entity ->
                SilverInspectorEntityUi(
                    id = entity.id,
                    name = checkNotNull(entityNames[entity.id]),
                    type = checkNotNull(entityTypes[entity.id]),
                    originObservationIds = entity.originObservationIds,
                    producer = entity.createdBy.toUi(),
                )
            },
            claims = claims.map { claim ->
                SilverInspectorClaimUi(
                    id = claim.id,
                    subjectName = entityNames[claim.subjectEntityId] ?: shortId(claim.subjectEntityId),
                    predicate = claim.predicate,
                    objectDisplay = claim.objectEntityId?.let { entityNames[it] ?: shortId(it) }
                        ?: checkNotNull(claim.value).display(),
                    state = claim.state.storageValue,
                    confidence = claim.confidence,
                    competing = claim.id in competingClaimIds,
                    supportingObservationIds = claim.supportingObservationIds,
                    producer = claim.producer.toUi(),
                )
            },
        )
    })
}

@Composable
internal fun KnowledgeScreen(
    source: SilverInspectorSourceUi,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
            }
            Column(Modifier.weight(1f)) {
                Text(source.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(source.id, style = MaterialTheme.typography.labelSmall, color = Ink.copy(alpha = .56f))
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                InspectorSectionTitle("Bronze")
                InspectorRecord("Source type", source.sourceType)
                InspectorRecord("Source ID", source.id)
            }
            item { InspectorSectionTitle("Evidence · ${source.evidence.size}") }
            items(source.evidence, key = SilverInspectorEvidenceUi::id) { evidence ->
                InspectorCard("Evidence ${shortId(evidence.id)}") {
                    InspectorMetadata("content", evidence.contentSha256)
                    evidence.selector?.let { InspectorMetadata("selector", it) }
                    evidence.excerpt?.let { Text("“$it”", style = MaterialTheme.typography.bodySmall) }
                }
            }
            item { InspectorSectionTitle("Observations · ${source.observations.size}") }
            items(source.observations, key = SilverInspectorObservationUi::id) { observation ->
                InspectorCard("${observation.kind} · ${observation.status.display()}") {
                    InspectorMetadata("id", observation.id)
                    Text(observation.payload, style = MaterialTheme.typography.bodySmall, maxLines = 5)
                    observation.confidence?.let { InspectorMetadata("confidence", "${(it * 100).toInt()}%") }
                    InspectorProducer(observation.producer)
                    InspectorMetadata("evidence", observation.evidenceIds.joinToString(transform = ::shortId))
                }
            }
            item { InspectorSectionTitle("Entities · ${source.entities.size}") }
            items(source.entities, key = SilverInspectorEntityUi::id) { entity ->
                InspectorCard("${entity.name} · ${entity.type}") {
                    InspectorMetadata("id", entity.id)
                    InspectorProducer(entity.producer)
                    InspectorMetadata("origin", entity.originObservationIds.joinToString(transform = ::shortId))
                }
            }
            item { InspectorSectionTitle("Claims · ${source.claims.size}") }
            items(source.claims, key = SilverInspectorClaimUi::id) { claim ->
                InspectorCard("${claim.subjectName} ${claim.predicate} ${claim.objectDisplay}") {
                    val flags = listOfNotNull(
                        claim.state,
                        "competing".takeIf { claim.competing },
                        claim.confidence?.let { "${(it * 100).toInt()}% confidence" },
                    )
                    InspectorMetadata("status", flags.joinToString(" · "))
                    InspectorMetadata("id", claim.id)
                    InspectorProducer(claim.producer)
                    InspectorMetadata(
                        "observations",
                        claim.supportingObservationIds.joinToString(transform = ::shortId),
                    )
                }
            }
            item { Spacer(Modifier.height(88.dp)) }
        }
    }
}

@Composable
private fun InspectorSectionTitle(title: String) = Text(
    title,
    modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp, start = 12.dp, end = 12.dp),
    style = MaterialTheme.typography.titleMedium,
    fontWeight = FontWeight.SemiBold,
)

@Composable
private fun InspectorRecord(label: String, value: String) = InspectorCard(label) {
    Text(value, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun InspectorCard(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        content()
    }
    HorizontalDivider(color = Ink.copy(alpha = .08f))
}

@Composable
private fun InspectorMetadata(label: String, value: String) = Text(
    "$label: $value",
    style = MaterialTheme.typography.labelSmall,
    color = Ink.copy(alpha = .58f),
)

@Composable
private fun InspectorProducer(producer: SilverInspectorProducerUi) {
    InspectorMetadata("processor", "${producer.processorId} @ ${producer.processorVersion}")
    producer.modelId?.let { model ->
        InspectorMetadata("model", listOfNotNull(model, producer.modelRevision).joinToString(" @ "))
    }
}

private fun observationUi(
    observation: SilverObservation,
    resolutionByInput: Map<String, List<SilverObservation>>,
) = SilverInspectorObservationUi(
    id = observation.id,
    kind = observation.kind,
    status = observation.status(resolutionByInput),
    payload = observation.payload.displayJson(),
    confidence = observation.confidence,
    evidenceIds = observation.evidenceIds,
    producer = observation.producer.toUi(),
)

private fun SilverObservation.status(
    resolutionByInput: Map<String, List<SilverObservation>>,
): SilverInspectorObservationStatus {
    if (kind == SILVER_RESOLUTION_KIND) {
        return if (resolutionOutcome() == "resolved") {
            SilverInspectorObservationStatus.RESOLVED
        } else {
            SilverInspectorObservationStatus.UNRESOLVED
        }
    }
    if (kind !in CANDIDATE_KINDS) return SilverInspectorObservationStatus.TRACE
    val outcomes = resolutionByInput[id].orEmpty().mapNotNull(SilverObservation::resolutionOutcome).toSet()
    return when {
        "resolved" in outcomes && "unresolved" in outcomes -> SilverInspectorObservationStatus.PARTIAL
        "resolved" in outcomes -> SilverInspectorObservationStatus.RESOLVED
        else -> SilverInspectorObservationStatus.UNRESOLVED
    }
}

private fun evidenceUi(evidence: SilverEvidence) = SilverInspectorEvidenceUi(
    id = evidence.id,
    contentSha256 = evidence.bronzeContentSha256,
    selector = evidence.selector?.displayJson(),
    excerpt = evidence.excerpt,
)

private fun preferredClaimText(
    claims: List<SilverClaim>,
    entityId: String,
    predicate: String,
    fallback: String,
): String = claims.asSequence()
    .filter { it.subjectEntityId == entityId && it.predicate == predicate }
    .sortedBy(SilverClaim::id)
    .mapNotNull { (it.value?.value as? SilverJsonString)?.value }
    .firstOrNull() ?: fallback

private fun competingClaimIds(activeClaims: List<SilverClaim>): Set<String> = activeClaims
    .groupBy { it.subjectEntityId to it.predicate }
    .values
    .filter { claims -> claims.map(SilverClaim::objectIdentity).distinct().size > 1 }
    .flatten()
    .mapTo(mutableSetOf(), SilverClaim::id)

private fun SilverClaim.objectIdentity(): String = objectEntityId?.let { "entity:$it" }
    ?: "scalar:${checkNotNull(value).type.storageValue}:${checkNotNull(value).value.displayJson()}"

private fun SilverScalar.display(): String = when (val scalar = value) {
    is SilverJsonString -> "“${scalar.value}”"
    is SilverJsonNumber -> scalar.value.toString()
    is SilverJsonBoolean -> scalar.value.toString()
    else -> scalar.displayJson()
}

private fun SilverJsonValue.displayJson(): String = when (this) {
    SilverJsonNull -> "null"
    is SilverJsonBoolean -> value.toString()
    is SilverJsonNumber -> value.toString()
    is SilverJsonString -> value.quotedJson()
    is SilverJsonArray -> values.joinToString(prefix = "[", postfix = "]") { it.displayJson() }
    is SilverJsonObject -> properties.toSortedMap().entries.joinToString(prefix = "{", postfix = "}") {
        "${it.key.quotedJson()}:${it.value.displayJson()}"
    }
}

private fun String.quotedJson(): String = buildString(length + 2) {
    append('"')
    this@quotedJson.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) {
                append("\\u")
                append(character.code.toString(16).padStart(4, '0'))
            } else {
                append(character)
            }
        }
    }
    append('"')
}

private fun SilverObservation.inputObservationId(): String? = payload.propertyString("inputObservationId")
private fun SilverObservation.resolvedEntityId(): String? = payload.propertyString("entityId")
private fun SilverObservation.resolutionOutcome(): String? = payload.propertyString("outcome")

private fun SilverJsonValue.propertyString(name: String): String? =
    ((this as? SilverJsonObject)?.properties?.get(name) as? SilverJsonString)?.value

private fun SilverProducer.toUi() = SilverInspectorProducerUi(
    processorId,
    processorVersion,
    modelId,
    modelRevision,
)

private fun SilverInspectorObservationStatus.display(): String = name.lowercase()
private fun shortId(id: String): String = id.take(8)
private val CANDIDATE_KINDS = setOf(SILVER_ATTRIBUTE_CANDIDATE_KIND, SILVER_RELATIONSHIP_CANDIDATE_KIND)
