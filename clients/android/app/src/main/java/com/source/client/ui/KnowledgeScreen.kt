package com.source.client.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.source.client.R
import com.source.client.knowledge.SILVER_ATTRIBUTE_CANDIDATE_KIND
import com.source.client.knowledge.SILVER_ENTITY_TYPE_PREDICATE
import com.source.client.knowledge.SILVER_NAME_PREDICATE
import com.source.client.knowledge.SILVER_RELATIONSHIP_CANDIDATE_KIND
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
    val summary: String,
    val confidence: Double?,
    val evidenceIds: List<String>,
    val producer: SilverInspectorProducerUi,
)

data class SilverInspectorEntityUi(
    val id: String,
    val name: String,
    val type: String,
    val colorIndex: Int,
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
    val subjectColorIndex: Int,
    val objectColorIndex: Int?,
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
    val entityColorIndices = silver.entities
        .sortedBy(SilverEntity::id)
        .mapIndexed { index, entity -> entity.id to index % ENTITY_COLORS.size }
        .toMap()
    val activeClaims = silver.claims.filter { it.state == SilverClaimState.ACTIVE }
    val entityNames = silver.entities.associate { entity ->
        entity.id to preferredClaimText(activeClaims, entity.id, SILVER_NAME_PREDICATE, "Unknown entity")
    }
    val entityTypes = silver.entities.associate { entity ->
        entity.id to preferredClaimText(activeClaims, entity.id, SILVER_ENTITY_TYPE_PREDICATE, "Unknown type")
    }
    val competingClaimIds = competingClaimIds(activeClaims)
    val observationConfidenceById = silver.observations.associate { it.id to it.confidence }
    val claimsByObservationId = activeClaims.flatMap { claim ->
        claim.supportingObservationIds.map { observationId -> observationId to claim }
    }.groupBy({ it.first }, { it.second })

    return KnowledgeUiState(sourceIds.map { sourceId ->
        val libraryItem = libraryById[sourceId]
        val evidence = silver.evidence.filter { it.bronzeSourceId == sourceId }.sortedBy(SilverEvidence::id)
        val evidenceIds = evidence.mapTo(mutableSetOf(), SilverEvidence::id)
        val observations = silver.observations.filter { observation ->
            observation.evidenceIds.any(evidenceIds::contains)
        }.sortedWith(compareBy(SilverObservation::createdAtMillis, SilverObservation::kind, SilverObservation::id))
        val observationIds = observations.mapTo(mutableSetOf(), SilverObservation::id)
        val sourceClaims = activeClaims.filter { claim ->
            claim.supportingObservationIds.any(observationIds::contains)
        }.sortedWith(compareBy(SilverClaim::subjectEntityId, SilverClaim::predicate, SilverClaim::id))
        val entityIds = buildSet {
            sourceClaims.forEach { claim ->
                add(claim.subjectEntityId)
                claim.objectEntityId?.let(::add)
            }
        }
        SilverInspectorSourceUi(
            id = sourceId,
            name = libraryItem?.filename ?: sourceId,
            sourceType = libraryItem?.sourceType ?: "unknown",
            canOpenSource = libraryItem?.previewKind != null,
            evidence = evidence.map(::evidenceUi),
            observations = observations.map { observation ->
                observationUi(observation, claimsByObservationId[observation.id].orEmpty())
            },
            entities = entityIds.mapNotNull(entitiesById::get).sortedBy(SilverEntity::id).map { entity ->
                SilverInspectorEntityUi(
                    id = entity.id,
                    name = checkNotNull(entityNames[entity.id]),
                    type = checkNotNull(entityTypes[entity.id]),
                    colorIndex = checkNotNull(entityColorIndices[entity.id]),
                )
            },
            claims = sourceClaims
                .filterNot { it.predicate == SILVER_NAME_PREDICATE || it.predicate == SILVER_ENTITY_TYPE_PREDICATE }
                .groupBy { Triple(it.subjectEntityId, it.predicate, it.objectIdentity()) }
                .values
                .map { matchingClaims ->
                    val claim = matchingClaims.maxWithOrNull(
                        compareBy<SilverClaim>({ it.confidence ?: -1.0 }, SilverClaim::id),
                    ) ?: error("A displayed Claim group cannot be empty")
                    SilverInspectorClaimUi(
                        id = claim.id,
                        subjectName = entityNames[claim.subjectEntityId] ?: "Unknown entity",
                        predicate = claim.predicate,
                        objectDisplay = claim.objectEntityId?.let { entityNames[it] ?: "Unknown entity" }
                            ?: checkNotNull(claim.value).displayScalar(),
                        state = claim.state.storageValue,
                        confidence = (
                            matchingClaims.mapNotNull(SilverClaim::confidence) +
                                matchingClaims.flatMap(SilverClaim::supportingObservationIds)
                                    .mapNotNull(observationConfidenceById::get)
                            ).maxOrNull(),
                        competing = matchingClaims.any { it.id in competingClaimIds },
                        supportingObservationIds = matchingClaims
                            .flatMap(SilverClaim::supportingObservationIds)
                            .distinct()
                            .sorted(),
                        producer = claim.producer.toUi(),
                        subjectColorIndex = checkNotNull(entityColorIndices[claim.subjectEntityId]),
                        objectColorIndex = claim.objectEntityId?.let(entityColorIndices::get),
                    )
                }
                .sortedWith(compareBy(SilverInspectorClaimUi::subjectName, SilverInspectorClaimUi::predicate)),
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
    Column(modifier.statusBarsPadding().fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
            }
            Column(Modifier.weight(1f)) {
                Text(source.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    listOf(
                        source.sourceType.displayLabel(),
                        "${source.claims.size} ${if (source.claims.size == 1) "fact" else "facts"}",
                        "${source.entities.size} ${if (source.entities.size == 1) "entity" else "entities"}",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = Ink.copy(alpha = .56f),
                )
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            item { InspectorSectionTitle("Facts · ${source.claims.size}") }
            if (source.claims.isEmpty()) {
                item { InspectorEmptyState("No facts have been identified in this source yet.") }
            } else {
                items(source.claims, key = SilverInspectorClaimUi::id) { claim ->
                    InspectorCard(claim.subjectName, claim.subjectColorIndex) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${claim.predicate.displayLabel()} ", style = MaterialTheme.typography.bodyMedium)
                            claim.objectColorIndex?.let {
                                EntityMarker(it)
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                claim.objectDisplay.withoutDecorativeQuotes(),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        val details = listOfNotNull(
                            "Conflicting information".takeIf { claim.competing },
                            claim.confidence?.let { "${(it * 100).toInt()}% confidence" },
                        )
                        if (details.isNotEmpty()) InspectorMetadata(details.joinToString(" · "))
                    }
                }
            }
            item { InspectorSectionTitle("Entities · ${source.entities.size}") }
            items(source.entities, key = SilverInspectorEntityUi::id) { entity ->
                InspectorCard(entity.name, entity.colorIndex) {
                    InspectorMetadata(entity.type.displayLabel())
                }
            }
            val needsReview = source.observations.filter {
                it.status == SilverInspectorObservationStatus.UNRESOLVED ||
                    it.status == SilverInspectorObservationStatus.PARTIAL
            }
            if (needsReview.isNotEmpty()) {
                item { InspectorSectionTitle("Needs review · ${needsReview.size}") }
                items(needsReview, key = SilverInspectorObservationUi::id) { observation ->
                    InspectorCard(observation.summary) {
                        Text(
                            when (observation.status) {
                                SilverInspectorObservationStatus.PARTIAL -> "Only part of this observation could be connected."
                                else -> "This observation could not be connected to an entity."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Ink.copy(alpha = .64f),
                        )
                        observation.confidence?.let { InspectorMetadata("${(it * 100).toInt()}% confidence") }
                    }
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
private fun InspectorCard(
    title: String,
    entityColorIndex: Int? = null,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            entityColorIndex?.let {
                EntityMarker(it)
                Spacer(Modifier.width(7.dp))
            }
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(4.dp))
        content()
    }
    HorizontalDivider(color = Ink.copy(alpha = .08f))
}

@Composable
private fun EntityMarker(colorIndex: Int) = Box(
    Modifier
        .size(10.dp)
        .clip(CircleShape)
        .background(ENTITY_COLORS[colorIndex % ENTITY_COLORS.size]),
)

@Composable
private fun InspectorMetadata(value: String) = Text(
    value,
    style = MaterialTheme.typography.labelSmall,
    color = Ink.copy(alpha = .58f),
)

@Composable
private fun InspectorEmptyState(message: String) = Text(
    message,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
    style = MaterialTheme.typography.bodyMedium,
    color = Ink.copy(alpha = .64f),
)

private fun observationUi(
    observation: SilverObservation,
    claims: List<SilverClaim>,
) = SilverInspectorObservationUi(
    id = observation.id,
    kind = observation.kind,
    status = observation.status(claims),
    payload = observation.payload.displayJson(),
    summary = observation.displaySummary(),
    confidence = observation.confidence,
    evidenceIds = observation.evidenceIds,
    producer = observation.producer.toUi(),
)

private fun SilverObservation.displaySummary(): String {
    val properties = (payload as? SilverJsonObject)?.properties ?: return kind.displayLabel()
    val subject = properties["subject"].mentionName() ?: return kind.displayLabel()
    val predicate = (properties["predicate"] as? SilverJsonString)?.value?.displayLabel()
        ?: return subject
    val target = properties["object"].mentionName()
        ?: properties["value"]?.displayScalar()?.withoutDecorativeQuotes()
        ?: return "$subject $predicate"
    return "$subject $predicate $target"
}

private fun SilverJsonValue?.mentionName(): String? =
    (((this as? SilverJsonObject)?.properties?.get("name")) as? SilverJsonString)?.value

private fun SilverObservation.status(
    claims: List<SilverClaim>,
): SilverInspectorObservationStatus {
    if (kind !in CANDIDATE_KINDS) return SilverInspectorObservationStatus.TRACE
    val predicate = payload.propertyString("predicate")
    return when {
        predicate != null && claims.any { it.predicate == predicate } -> SilverInspectorObservationStatus.RESOLVED
        claims.isNotEmpty() -> SilverInspectorObservationStatus.PARTIAL
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
    .sortedWith(compareBy<SilverClaim>({ if (it.state == SilverClaimState.ACTIVE) 0 else 1 }, SilverClaim::id))
    .mapNotNull { (it.value as? SilverJsonString)?.value }
    .firstOrNull() ?: fallback

private fun competingClaimIds(activeClaims: List<SilverClaim>): Set<String> = activeClaims
    .groupBy { it.subjectEntityId to it.predicate }
    .values
    .filter { claims -> claims.map(SilverClaim::objectIdentity).distinct().size > 1 }
    .flatten()
    .mapTo(mutableSetOf(), SilverClaim::id)

private fun SilverClaim.objectIdentity(): String = objectEntityId?.let { "entity:$it" }
    ?: "scalar:${checkNotNull(value).displayJson()}"

private fun SilverJsonValue.displayScalar(): String = when (this) {
    is SilverJsonString -> "“$value”"
    is SilverJsonNumber -> value.toString()
    is SilverJsonBoolean -> value.toString()
    else -> displayJson()
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

private fun SilverJsonValue.propertyString(name: String): String? =
    ((this as? SilverJsonObject)?.properties?.get(name) as? SilverJsonString)?.value

private fun SilverProducer.toUi() = SilverInspectorProducerUi(
    processorId,
    processorVersion,
    modelId,
    modelRevision,
)

internal fun String.displayLabel(): String = replace('-', ' ')
    .replace('_', ' ')
    .trim()
    .replaceFirstChar { it.titlecase() }

private fun String.withoutDecorativeQuotes(): String =
    if (length >= 2 && first() == '“' && last() == '”') substring(1, lastIndex) else this

private val CANDIDATE_KINDS = setOf(SILVER_ATTRIBUTE_CANDIDATE_KIND, SILVER_RELATIONSHIP_CANDIDATE_KIND)
private val ENTITY_COLORS = listOf(
    Color(0xFF2F6B57),
    Color(0xFF9A4D42),
    Color(0xFF4A6496),
    Color(0xFF8A5A90),
    Color(0xFF9A6A24),
    Color(0xFF2D728F),
    Color(0xFF7A5C3E),
    Color(0xFF6B6F3C),
)
