package com.source.client.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    val icon: SilverInspectorEntityIcon,
)

enum class SilverInspectorEntityIcon { PERSON, LOCATION, ORGANIZATION, EVENT, DOCUMENT, GENERIC }

data class SilverInspectorClaimUi(
    val id: String,
    val subjectEntityId: String,
    val subjectName: String,
    val predicate: String,
    val objectDisplay: String,
    val objectEntityId: String?,
    val objectEntityIcon: SilverInspectorEntityIcon?,
    val state: String,
    val confidence: Double?,
    val competing: Boolean,
    val supportingObservationIds: List<String>,
    val producer: SilverInspectorProducerUi,
)

data class SilverBrowserEntityUi(
    val id: String,
    val name: String,
    val type: String,
    val icon: SilverInspectorEntityIcon,
    val claims: List<SilverInspectorClaimUi>,
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

    fun claimsFor(entityId: String): List<SilverInspectorClaimUi> =
        claims.filter { it.subjectEntityId == entityId }
}

data class KnowledgeUiState(
    val sources: List<SilverInspectorSourceUi> = emptyList(),
    val entities: List<SilverBrowserEntityUi> = emptyList(),
)

internal fun buildKnowledgeUiState(
    silver: SilverDataset,
    library: LibraryUiState,
): KnowledgeUiState {
    val libraryById = library.items.associateBy(LibraryUiItem::id)
    val sourceIds = (libraryById.keys + silver.evidence.map(SilverEvidence::bronzeSourceId)).toSortedSet()
    val entitiesById = silver.entities.associateBy(SilverEntity::id)
    val activeClaims = silver.claims.filter { it.state == SilverClaimState.ACTIVE }
    val entityNames = silver.entities.associate { entity ->
        entity.id to preferredClaimText(activeClaims, entity.id, SILVER_NAME_PREDICATE, "Unknown entity")
    }
    val entityTypes = silver.entities.associate { entity ->
        entity.id to preferredClaimText(activeClaims, entity.id, SILVER_ENTITY_TYPE_PREDICATE, "Unknown type")
    }
    val competingClaimIds = competingClaimIds(activeClaims)
    val observationConfidenceById = silver.observations.associate { it.id to it.confidence }
    // Historical Claims still prove that an Observation was resolved, even though only active
    // Claims belong in the current Facts projection.
    val claimsByObservationId = silver.claims.flatMap { claim ->
        claim.supportingObservationIds.map { observationId -> observationId to claim }
    }.groupBy({ it.first }, { it.second })

    val sources = sourceIds.map { sourceId ->
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
                    icon = entityIcon(entityTypes[entity.id]),
                )
            }.sortedWith(compareBy(SilverInspectorEntityUi::name, SilverInspectorEntityUi::type, SilverInspectorEntityUi::id)),
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
                        subjectEntityId = claim.subjectEntityId,
                        subjectName = entityNames[claim.subjectEntityId] ?: "Unknown entity",
                        predicate = claim.predicate,
                        objectDisplay = claim.objectEntityId?.let { entityNames[it] ?: "Unknown entity" }
                            ?: checkNotNull(claim.value).displayScalar(),
                        objectEntityId = claim.objectEntityId,
                        objectEntityIcon = claim.objectEntityId?.let { entityIcon(entityTypes[it]) },
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
                    )
                }
                .sortedWith(compareBy(SilverInspectorClaimUi::subjectName, SilverInspectorClaimUi::predicate)),
        )
    }
    val entities = silver.entities.map { entity ->
        SilverBrowserEntityUi(
            id = entity.id,
            name = checkNotNull(entityNames[entity.id]),
            type = checkNotNull(entityTypes[entity.id]),
            icon = entityIcon(entityTypes[entity.id]),
            claims = activeClaims
                .filter {
                    it.subjectEntityId == entity.id &&
                        it.predicate != SILVER_NAME_PREDICATE &&
                        it.predicate != SILVER_ENTITY_TYPE_PREDICATE
                }
                .groupBy { it.predicate to it.objectIdentity() }
                .values
                .map { matchingClaims ->
                    val claim = matchingClaims.maxWithOrNull(
                        compareBy<SilverClaim>({ it.confidence ?: -1.0 }, SilverClaim::id),
                    ) ?: error("A displayed Claim group cannot be empty")
                    SilverInspectorClaimUi(
                        id = claim.id,
                        subjectEntityId = claim.subjectEntityId,
                        subjectName = checkNotNull(entityNames[entity.id]),
                        predicate = claim.predicate,
                        objectDisplay = claim.objectEntityId?.let { entityNames[it] ?: "Unknown entity" }
                            ?: checkNotNull(claim.value).displayScalar(),
                        objectEntityId = claim.objectEntityId,
                        objectEntityIcon = claim.objectEntityId?.let { entityIcon(entityTypes[it]) },
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
                    )
                }
                .sortedWith(compareBy(SilverInspectorClaimUi::predicate, SilverInspectorClaimUi::id)),
        )
    }.sortedWith(compareBy<SilverBrowserEntityUi> { it.name.lowercase() }.thenBy { it.id })
    return KnowledgeUiState(sources, entities)
}

@Composable
internal fun KnowledgeScreen(
    source: SilverInspectorSourceUi,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedEntityId by rememberSaveable(source.id) { mutableStateOf<String?>(null) }
    val selectedEntity = source.entities.firstOrNull { it.id == selectedEntityId }
    val goBack = {
        if (selectedEntity == null) onBack() else selectedEntityId = null
    }
    BackHandler(onBack = goBack)
    Column(modifier.statusBarsPadding().fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = goBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
            }
            KnowledgeHeader(source, selectedEntity, Modifier.weight(1f))
        }
        if (selectedEntity == null) EntityList(source) { selectedEntityId = it.id }
        else EntityClaims(source, selectedEntity)
    }
}

@Composable
private fun KnowledgeHeader(
    source: SilverInspectorSourceUi,
    entity: SilverInspectorEntityUi?,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        if (entity == null) {
            Text(source.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "${source.sourceType.displayLabel()} · ${source.entities.size} " +
                    if (source.entities.size == 1) "entity" else "entities",
                style = MaterialTheme.typography.labelMedium,
                color = Ink.copy(alpha = .56f),
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EntityTypeIcon(entity.icon, entity.type)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(entity.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        entity.type.displayLabel(),
                        style = MaterialTheme.typography.labelMedium,
                        color = Ink.copy(alpha = .56f),
                    )
                }
            }
        }
    }
}

@Composable
private fun EntityList(source: SilverInspectorSourceUi, onSelect: (SilverInspectorEntityUi) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        item { InspectorSectionTitle("Entities · ${source.entities.size}") }
        if (source.entities.isEmpty()) {
            item { InspectorEmptyState("No entities have been identified in this source yet.") }
        } else {
            items(source.entities, key = SilverInspectorEntityUi::id) { entity ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(entity) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EntityTypeIcon(entity.icon, entity.type)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(entity.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(
                            entity.type.displayLabel(),
                            style = MaterialTheme.typography.labelSmall,
                            color = Ink.copy(alpha = .58f),
                        )
                    }
                }
                HorizontalDivider(color = Ink.copy(alpha = .08f))
            }
        }
        item { Spacer(Modifier.height(88.dp)) }
    }
}

@Composable
private fun EntityClaims(source: SilverInspectorSourceUi, entity: SilverInspectorEntityUi) {
    val claims = source.claimsFor(entity.id)
    LazyColumn(Modifier.fillMaxSize()) {
        item { InspectorSectionTitle("Claims · ${claims.size}") }
        if (claims.isEmpty()) {
            item { InspectorEmptyState("No claims about this entity are supported by this source.") }
        } else {
            items(claims, key = SilverInspectorClaimUi::id) { claim ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        claim.predicate.displayLabel(),
                        modifier = Modifier.weight(.42f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink.copy(alpha = .7f),
                    )
                    Text("→", modifier = Modifier.padding(horizontal = 8.dp), color = Ink.copy(alpha = .42f))
                    claim.objectEntityIcon?.let {
                        EntityTypeIcon(it, claim.objectDisplay)
                        Spacer(Modifier.width(7.dp))
                    }
                    Text(
                        claim.objectDisplay.withoutDecorativeQuotes(),
                        modifier = Modifier.weight(.58f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                HorizontalDivider(color = Ink.copy(alpha = .08f))
            }
        }
        item { Spacer(Modifier.height(88.dp)) }
    }
}

@Composable
internal fun SilverBrowserScreen(
    entities: List<SilverBrowserEntityUi>,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var entityPath by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val entitiesById = entities.associateBy(SilverBrowserEntityUi::id)
    LaunchedEffect(entitiesById.keys) {
        val validPath = entityPath.takeWhile(entitiesById::containsKey)
        if (validPath != entityPath) entityPath = validPath
    }
    BackHandler(enabled = entityPath.isNotEmpty()) {
        entityPath = entityPath.dropLast(1)
    }
    val selectedEntity = entityPath.lastOrNull()?.let(entitiesById::get)
    if (selectedEntity != null) {
        SilverEntityDetail(
            entity = selectedEntity,
            onBack = { entityPath = entityPath.dropLast(1) },
            onOpenEntity = { entityId ->
                if (entityId in entitiesById) entityPath = entityPath + entityId
            },
            modifier = modifier,
        )
        return
    }

    val filteredEntities = filterSilverEntities(entities, query)
    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            placeholder = { Text(stringResource(R.string.knowledge_search_entities)) },
        )
        when {
            entities.isEmpty() -> SilverBrowserEmptyState(stringResource(R.string.knowledge_silver_empty))
            filteredEntities.isEmpty() -> SilverBrowserEmptyState(stringResource(R.string.knowledge_search_empty))
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(filteredEntities, key = SilverBrowserEntityUi::id) { entity ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { entityPath = entityPath + entity.id }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        EntityTypeIcon(entity.icon, entity.type, 24)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                entity.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                entity.type.displayLabel(),
                                style = MaterialTheme.typography.labelMedium,
                                color = Ink.copy(alpha = .58f),
                            )
                        }
                        Icon(
                            Icons.Outlined.ChevronRight,
                            contentDescription = null,
                            tint = Ink.copy(alpha = .42f),
                        )
                    }
                    HorizontalDivider(color = Ink.copy(alpha = .08f))
                }
                item { Spacer(Modifier.height(88.dp)) }
            }
        }
    }
}

internal fun filterSilverEntities(
    entities: List<SilverBrowserEntityUi>,
    query: String,
): List<SilverBrowserEntityUi> {
    val normalizedQuery = query.trim()
    return if (normalizedQuery.isEmpty()) entities else entities.filter {
        it.name.contains(normalizedQuery, ignoreCase = true)
    }
}

@Composable
private fun SilverEntityDetail(
    entity: SilverBrowserEntityUi,
    onBack: () -> Unit,
    onOpenEntity: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
            }
            EntityTypeIcon(entity.icon, entity.type, 28)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entity.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    entity.type.displayLabel(),
                    style = MaterialTheme.typography.labelMedium,
                    color = Ink.copy(alpha = .58f),
                )
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            item { InspectorSectionTitle("Claims · ${entity.claims.size}") }
            if (entity.claims.isEmpty()) {
                item { InspectorEmptyState(stringResource(R.string.knowledge_claims_empty)) }
            } else {
                items(entity.claims, key = SilverInspectorClaimUi::id) { claim ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
                        Text(
                            claim.predicate.displayLabel(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Ink.copy(alpha = .7f),
                        )
                        val relatedEntityId = claim.objectEntityId
                        if (relatedEntityId == null) {
                            Text(
                                claim.objectDisplay.withoutDecorativeQuotes(),
                                modifier = Modifier.padding(top = 6.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        } else {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onOpenEntity(relatedEntityId) }
                                    .padding(top = 6.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                claim.objectEntityIcon?.let {
                                    EntityTypeIcon(it, claim.objectDisplay, 20)
                                    Spacer(Modifier.width(7.dp))
                                }
                                Text(
                                    claim.objectDisplay,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                Icon(
                                    Icons.Outlined.ChevronRight,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = Ink.copy(alpha = .42f),
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = Ink.copy(alpha = .08f))
                }
            }
            item { Spacer(Modifier.height(88.dp)) }
        }
    }
}

@Composable
private fun SilverBrowserEmptyState(message: String) = Box(
    Modifier.fillMaxSize().padding(24.dp),
    contentAlignment = Alignment.Center,
) {
    Text(message, style = MaterialTheme.typography.bodyMedium, color = Ink.copy(alpha = .64f))
}

@Composable
private fun InspectorSectionTitle(title: String) = Text(
    title,
    modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp, start = 12.dp, end = 12.dp),
    style = MaterialTheme.typography.titleMedium,
    fontWeight = FontWeight.SemiBold,
)

@Composable
private fun EntityTypeIcon(
    icon: SilverInspectorEntityIcon,
    description: String,
    size: Int = 18,
) = Icon(
    imageVector = icon.imageVector(),
    contentDescription = description.displayLabel(),
    modifier = Modifier.size(size.dp),
    tint = Ink.copy(alpha = .68f),
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

internal fun entityIcon(type: String?): SilverInspectorEntityIcon {
    val normalized = type.orEmpty().lowercase().replace('-', ' ').replace('_', ' ')
    val words = normalized.split(' ').filter(String::isNotBlank).toSet()
    return when {
        words.any { it in PERSON_TYPES } -> SilverInspectorEntityIcon.PERSON
        words.any { it in LOCATION_TYPES } -> SilverInspectorEntityIcon.LOCATION
        words.any { it in ORGANIZATION_TYPES } -> SilverInspectorEntityIcon.ORGANIZATION
        words.any { it in EVENT_TYPES } -> SilverInspectorEntityIcon.EVENT
        words.any { it in DOCUMENT_TYPES } -> SilverInspectorEntityIcon.DOCUMENT
        else -> SilverInspectorEntityIcon.GENERIC
    }
}

private fun SilverInspectorEntityIcon.imageVector(): ImageVector = when (this) {
    SilverInspectorEntityIcon.PERSON -> Icons.Outlined.Person
    SilverInspectorEntityIcon.LOCATION -> Icons.Outlined.LocationOn
    SilverInspectorEntityIcon.ORGANIZATION -> Icons.Outlined.Business
    SilverInspectorEntityIcon.EVENT -> Icons.Outlined.Event
    SilverInspectorEntityIcon.DOCUMENT -> Icons.Outlined.Description
    SilverInspectorEntityIcon.GENERIC -> Icons.AutoMirrored.Outlined.Label
}

private val CANDIDATE_KINDS = setOf(SILVER_ATTRIBUTE_CANDIDATE_KIND, SILVER_RELATIONSHIP_CANDIDATE_KIND)
private val PERSON_TYPES = setOf("person", "people", "human", "user", "contact", "author", "artist")
private val LOCATION_TYPES = setOf("location", "place", "city", "country", "region", "address", "venue")
private val ORGANIZATION_TYPES = setOf("organization", "organisation", "company", "business", "team", "group", "institution")
private val EVENT_TYPES = setOf("event", "meeting", "conference")
private val DOCUMENT_TYPES = setOf("document", "book", "article", "file", "note")
