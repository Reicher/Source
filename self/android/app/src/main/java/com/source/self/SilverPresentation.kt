package com.source.self

import org.json.JSONObject

internal data class SilverFinding(
    val predicate: String,
    val value: String,
    val count: Int,
    val confidence: Double?,
    val sourceExcerpt: String?,
)

internal fun SilverProcessing.presentation(defaultState: String = "Processing"): String {
    val progress = if (totalBatches > 0) " · $completedBatches/$totalBatches" else ""
    if (state != "failed") return "$defaultState$progress"
    val detail = error?.trim()?.takeIf(String::isNotEmpty) ?: "Unknown processing error"
    return if (retryable) "Processing failed; retry scheduled · $detail"
    else "Processing stopped · $detail"
}

internal fun SilverSource.coveragePresentation(): String? {
    val current = coverage ?: return null
    if (current.extractionState == "completed" && current.semanticState == "completed") return null
    val reason = when (current.semanticSkipReason) {
        "unsupported_content" -> "This content type is not supported for knowledge extraction."
        "source_too_large" -> "This source is too large for the current extractor."
        "model_unavailable" -> "The semantic model was unavailable."
        "fragment_exceeds_model_limit" -> "Some content exceeds the semantic model's current input limit."
        else -> "Some semantic work was not completed."
    }
    return when (current.semanticState) {
        "partial" -> "Semantic processing is partial. $reason"
        "skipped" -> if (current.extractionState == "skipped") {
            "Deterministic extraction and semantic processing were skipped. $reason"
        } else {
            "Semantic processing was skipped. $reason"
        }
        else -> if (current.extractionState == "skipped") "Deterministic extraction was skipped. $reason" else null
    }
}

internal fun SilverKnowledge.unresolvedFindings(): List<SilverFinding> {
    val supportedObservationIds = claims.asSequence()
        .filter { it.state == "active" }
        .flatMap { it.supportingObservationIds.asSequence() }
        .toSet()
    data class EvidenceScopedRef(val evidenceId: String, val ref: String)

    val candidateLabels = observations.asSequence()
        .filter { it.kind == "entity-candidate" }
        .flatMap { observation ->
            val payload = observation.payload as? JSONObject ?: return@flatMap emptySequence()
            val ref = payload.optString("ref").trim()
            val label = payload.optString("label").trim()
            if (ref.isEmpty() || label.isEmpty()) emptySequence()
            else observation.evidenceIds.asSequence().map { EvidenceScopedRef(it, ref) to label }
        }
        .toMap()

    fun candidateLabel(observation: SilverObservation, ref: String): String? = observation.evidenceIds
        .mapNotNull { evidenceId -> candidateLabels[EvidenceScopedRef(evidenceId, ref)] }
        .distinct()
        .singleOrNull()

    data class Candidate(
        val predicate: String,
        val value: String,
        val confidence: Double?,
        val sourceExcerpt: String?,
    )

    return observations.asSequence()
        .filter { it.id !in supportedObservationIds }
        .mapNotNull { observation ->
            val payload = observation.payload as? JSONObject ?: return@mapNotNull null
            val presentation = when (observation.kind) {
                "entity-candidate" -> {
                    val name = payload.optString("label").trim().takeIf(String::isNotEmpty)
                        ?: return@mapNotNull null
                    val type = payload.optString("type").trim().takeIf(String::isNotEmpty)
                    "Possible ${type?.let(::humanizeSilverValue) ?: "entity"}" to name
                }
                "attribute-candidate" -> {
                    val predicate = payload.optString("predicate").trim().takeIf(String::isNotEmpty)
                        ?: return@mapNotNull null
                    val value = humanSilverValue(payload.opt("value")) ?: return@mapNotNull null
                    val subject = candidateLabel(observation, payload.optString("subject_ref"))
                    humanizeSilverValue(predicate) to listOfNotNull(subject, value).joinToString(" → ")
                }
                "relationship-candidate" -> {
                    val predicate = payload.optString("predicate").trim().takeIf(String::isNotEmpty)
                        ?: return@mapNotNull null
                    val subject = candidateLabel(observation, payload.optString("subject_ref"))
                        ?: return@mapNotNull null
                    val objectLabel = candidateLabel(observation, payload.optString("object_ref"))
                        ?: return@mapNotNull null
                    humanizeSilverValue(predicate) to "$subject → $objectLabel"
                }
                else -> return@mapNotNull null
            }
            val excerpt = evidence.asSequence()
                .filter { it.id in observation.evidenceIds }
                .map { it.excerpt.trim() }
                .firstOrNull(String::isNotEmpty)
                ?.let(::compactSilverExcerpt)
                ?.takeUnless { it == presentation.second }
            Candidate(presentation.first, presentation.second, observation.confidence, excerpt)
        }
        .groupBy { it.predicate to it.value }
        .values
        .map { matching ->
            SilverFinding(
                matching.first().predicate,
                matching.first().value,
                matching.size,
                matching.mapNotNull { it.confidence }.maxOrNull(),
                matching.mapNotNull { it.sourceExcerpt }.firstOrNull(),
            )
        }
        .sortedWith(
            compareByDescending<SilverFinding> { it.confidence != null }
                .thenByDescending { it.confidence ?: 0.0 }
                .thenBy { it.predicate }
                .thenBy { it.value },
        )
        .toList()
}

private fun humanSilverValue(value: Any?): String? = when (value) {
    is String -> value.trim().takeIf(String::isNotEmpty)
    is Number, is Boolean -> value.toString()
    else -> null
}

private fun humanizeSilverValue(value: String): String =
    value.replace('_', ' ').replace('-', ' ')

private fun compactSilverExcerpt(value: String, limit: Int = 160): String {
    val compact = value.replace(Regex("\\s+"), " ").trim()
    return if (compact.length <= limit) compact else compact.take(limit).trimEnd() + "…"
}
