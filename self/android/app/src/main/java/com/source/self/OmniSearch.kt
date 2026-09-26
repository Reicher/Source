package com.source.self

import java.text.Normalizer
import java.util.Locale

enum class OmniResultTier(val displayName: String, internal val preference: Int) {
    GOLD("Gold", 3),
    SILVER("Silver", 2),
    BRONZE("Bronze", 1),
}

data class OmniSearchCandidate(
    val tier: OmniResultTier,
    val id: String,
    val title: String,
    val searchTerms: List<String> = listOf(title),
)

private data class RankedOmniResult(
    val candidate: OmniSearchCandidate,
    val relevance: Int,
)

private val omniSeparators = Regex("[^\\p{L}\\p{N}]+")
private val omniWhitespace = Regex("\\s+")
private val omniDiacritics = Regex("\\p{M}+")

fun searchOmniBox(
    query: String,
    candidates: List<OmniSearchCandidate>,
    limit: Int = 8,
): List<OmniSearchCandidate> {
    val normalizedQuery = normalizeOmniText(query)
    if (normalizedQuery.isEmpty() || limit <= 0) return emptyList()

    return candidates.asSequence()
        .mapNotNull { candidate ->
            candidate.searchTerms.asSequence()
                .mapIndexedNotNull { index, term ->
                    omniRelevance(normalizedQuery, normalizeOmniText(term))?.minus(index * 10)
                }
                .maxOrNull()
                ?.let { RankedOmniResult(candidate, it) }
        }
        .sortedWith(
            compareByDescending<RankedOmniResult> { it.relevance }
                .thenByDescending { it.candidate.tier.preference }
                .thenBy { it.candidate.title.length }
                .thenBy { it.candidate.title.lowercase(Locale.ROOT) }
                .thenBy { it.candidate.id },
        )
        .take(limit)
        .map { it.candidate }
        .toList()
}

private fun normalizeOmniText(value: String): String = Normalizer
    .normalize(value, Normalizer.Form.NFD)
    .replace(omniDiacritics, "")
    .lowercase(Locale.ROOT)
    .replace(omniSeparators, " ")
    .trim()
    .replace(omniWhitespace, " ")

private fun omniRelevance(query: String, term: String): Int? {
    if (term.isEmpty()) return null
    if (term == query) return 10_000

    val lengthPenalty = (term.length - query.length).coerceAtLeast(0).coerceAtMost(500)
    if (term.startsWith(query)) return 9_000 - lengthPenalty

    val queryWords = query.split(' ')
    val termWords = term.split(' ')
    if (termWords.any { it == query }) return 8_500 - lengthPenalty
    if (termWords.any { it.startsWith(query) }) return 8_000 - lengthPenalty
    if (queryWords.size > 1 && queryWords.all { queryWord ->
            termWords.any { termWord -> termWord.startsWith(queryWord) }
        }) return 7_500 - lengthPenalty

    val containedAt = term.indexOf(query)
    if (containedAt >= 0) return 7_000 - containedAt.coerceAtMost(500) - lengthPenalty
    return null
}
