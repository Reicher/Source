package com.source.client.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.source.client.R
import com.source.client.storage.SilverClaim
import com.source.client.storage.SilverDataset
import com.source.client.storage.SilverEntity
import com.source.client.storage.SilverScalarValue
import java.util.Locale

data class KnowledgeSourceUi(
    val id: String,
    val name: String,
    val evidenceExcerpt: String?,
)

data class KnowledgeClaimUi(
    val subjectName: String,
    val predicate: String,
    val objectDisplay: String,
    val confidence: Double,
    val sources: List<KnowledgeSourceUi>,
)

data class KnowledgeEntityUi(
    val id: String,
    val name: String,
    val type: String,
    val claims: List<KnowledgeClaimUi>,
)

data class KnowledgeUiState(val entities: List<KnowledgeEntityUi> = emptyList())

internal fun buildKnowledgeUiState(
    silver: SilverDataset,
    library: LibraryUiState,
): KnowledgeUiState {
    val sourceNames = library.items.associate { it.id to it.filename }
    val currentResults = silver.results.filter { it.bronzeSourceId in sourceNames }
    val entities = currentResults.flatMap { it.entities }.associateBy(SilverEntity::id)
    val claims = currentResults.flatMap { it.claims }
    return KnowledgeUiState(
        entities.values.map { entity ->
            val related = claims.filter { it.subjectEntityId == entity.id || it.objectEntityId == entity.id }
            val grouped = related.groupBy { claim ->
                listOf(
                    claim.subjectEntityId,
                    claim.predicate.lowercase(Locale.ROOT),
                    claim.objectEntityId.orEmpty(),
                    scalarDisplay(claim.value),
                ).joinToString("\u0000")
            }
            KnowledgeEntityUi(
                id = entity.id,
                name = entity.name,
                type = entity.type,
                claims = grouped.values.map { supportingClaims ->
                    val claim = supportingClaims.first()
                    KnowledgeClaimUi(
                        subjectName = entities[claim.subjectEntityId]?.name ?: claim.subjectEntityId,
                        predicate = claim.predicate,
                        objectDisplay = claim.objectEntityId?.let { entities[it]?.name ?: it }
                            ?: scalarDisplay(claim.value),
                        confidence = supportingClaims.maxOf(SilverClaim::confidence),
                        sources = supportingClaims.map { support ->
                            KnowledgeSourceUi(
                                support.bronzeSourceId,
                                sourceNames[support.bronzeSourceId] ?: support.bronzeSourceId,
                                support.evidenceExcerpt,
                            )
                        }.distinctBy(KnowledgeSourceUi::id),
                    )
                }.sortedWith(compareBy(KnowledgeClaimUi::predicate, KnowledgeClaimUi::objectDisplay)),
            )
        }.filter { it.claims.isNotEmpty() }.sortedBy { it.name.lowercase(Locale.ROOT) },
    )
}

@Composable
internal fun KnowledgeScreen(
    state: KnowledgeUiState,
    onOpenSource: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedEntityId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = state.entities.firstOrNull { it.id == selectedEntityId }
    LaunchedEffect(selectedEntityId, state.entities) {
        if (selectedEntityId != null && selected == null) selectedEntityId = null
    }
    if (selected == null) {
        if (state.entities.isEmpty()) {
            Column(
                modifier.fillMaxSize().padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.knowledge_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.knowledge_empty_description),
                    color = Ink.copy(alpha = .58f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(modifier.fillMaxSize()) {
                items(state.entities, key = KnowledgeEntityUi::id) { entity ->
                    Row(
                        Modifier.fillMaxWidth().clickable { selectedEntityId = entity.id }
                            .padding(horizontal = 12.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                entity.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                stringResource(
                                    R.string.knowledge_entity_summary,
                                    displayType(entity.type),
                                    entity.claims.size,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = Ink.copy(alpha = .56f),
                            )
                        }
                    }
                    HorizontalDivider(color = Ink.copy(alpha = .08f))
                }
                item { Spacer(Modifier.height(88.dp)) }
            }
        }
        return
    }

    BackHandler { selectedEntityId = null }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { selectedEntityId = null }) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
            }
            Column(Modifier.weight(1f)) {
                Text(selected.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(displayType(selected.type), style = MaterialTheme.typography.bodySmall, color = Ink.copy(alpha = .56f))
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(selected.claims) { claim ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp)) {
                    Text(
                        "${claim.subjectName} ${claim.predicate} ${claim.objectDisplay}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        stringResource(R.string.knowledge_confidence, (claim.confidence * 100).toInt()),
                        style = MaterialTheme.typography.labelSmall,
                        color = Ink.copy(alpha = .48f),
                    )
                    claim.sources.forEach { source ->
                        TextButton(onClick = { onOpenSource(source.id) }) { Text(source.name) }
                        source.evidenceExcerpt?.let { excerpt ->
                            Text(
                                "“$excerpt”",
                                modifier = Modifier.padding(horizontal = 12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = Ink.copy(alpha = .62f),
                            )
                        }
                    }
                }
                HorizontalDivider(color = Ink.copy(alpha = .08f))
            }
        }
    }
}

private fun scalarDisplay(value: SilverScalarValue?): String = when (value) {
    null -> ""
    is SilverScalarValue.Text -> value.text
    is SilverScalarValue.Number -> value.number.toString()
    is SilverScalarValue.BooleanValue -> value.boolean.toString()
}

private fun displayType(type: String): String = type.replace('-', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }
