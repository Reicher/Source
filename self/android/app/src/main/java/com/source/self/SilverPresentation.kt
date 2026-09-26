package com.source.self

import org.json.JSONObject

internal data class SilverFinding(
    val predicate: String,
    val value: String,
    val count: Int,
    val confidence: Double?,
    val sourceExcerpt: String?,
)

internal fun SilverKnowledge.unresolvedFindings(): List<SilverFinding> {
    val supportedObservationIds = claims.asSequence()
        .filter { it.state == "active" }
        .flatMap { it.supportingObservationIds.asSequence() }
        .toSet()
    val candidateLabels = observations.asSequence()
        .filter { it.kind == "entity-candidate" }
        .mapNotNull { observation ->
            val payload = observation.payload as? JSONObject ?: return@mapNotNull null
            val ref = payload.optString("ref").trim()
            val label = payload.optString("label").trim()
            if (ref.isEmpty() || label.isEmpty()) null else ref to label
        }
        .toMap()

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
                    val subject = candidateLabels[payload.optString("subject_ref")]
                    humanizeSilverValue(predicate) to listOfNotNull(subject, value).joinToString(" → ")
                }
                "relationship-candidate" -> {
                    val predicate = payload.optString("predicate").trim().takeIf(String::isNotEmpty)
                        ?: return@mapNotNull null
                    val subject = candidateLabels[payload.optString("subject_ref")] ?: return@mapNotNull null
                    val objectLabel = candidateLabels[payload.optString("object_ref")] ?: return@mapNotNull null
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
