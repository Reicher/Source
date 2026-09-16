package com.source.client.knowledge

import android.os.SystemClock
import android.util.Log
import com.source.client.ai.SourceAiContent
import com.source.client.ai.SourceAiEvent
import com.source.client.ai.SourceAiMessage
import com.source.client.ai.SourceAiRequest
import com.source.client.ai.SourceAiRole
import com.source.client.ai.SourceAiRuntime
import com.source.client.ai.SourceAiWorkload
import com.source.client.model.AiModelMetadata
import com.source.client.storage.SilverEvidence
import com.source.client.storage.SilverJsonBoolean
import com.source.client.storage.SilverJsonNumber
import com.source.client.storage.SilverJsonObject
import com.source.client.storage.SilverJsonString
import com.source.client.storage.SilverJsonValue
import com.source.client.storage.SilverObservation
import com.source.client.storage.SilverProducer
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject

data class BronzeTextSource(
    val id: String,
    val name: String,
    val sourceType: String,
    val contentSha256: String,
    val text: String,
    val authoredBySelf: Boolean = false,
)

data class ExtractedSilver(
    val evidence: List<SilverEvidence>,
    val observations: List<SilverObservation>,
    val model: AiModelMetadata,
)

internal suspend fun extractSilver(
    runtime: SourceAiRuntime,
    source: BronzeTextSource,
): ExtractedSilver {
    val refinementStartedAt = SystemClock.elapsedRealtime()
    val chunks = deterministicTextChunks(source.text)
    Log.i(
        SILVER_LOG_TAG,
        "refinement started sourceType=${source.sourceType} bytes=${source.text.toByteArray().size} chunks=${chunks.size}",
    )
    val extracted = chunks.mapIndexed { index, chunk ->
        extractSilverBatch(runtime, source, chunk, index, chunks.size)
    }.let(::combineSilverBatches)
    Log.i(
        SILVER_LOG_TAG,
        "refinement finished durationMs=${SystemClock.elapsedRealtime() - refinementStartedAt} " +
            "evidence=${extracted.evidence.size} observations=${extracted.observations.size}",
    )
    return extracted
}

internal suspend fun extractSilverBatch(
    runtime: SourceAiRuntime,
    source: BronzeTextSource,
    chunk: String,
    batchIndex: Int,
    totalBatches: Int,
    processorVersion: String = SILVER_EXTRACTION_PROCESSOR_VERSION,
    createdAtMillis: Long = System.currentTimeMillis().coerceAtLeast(1),
): ExtractedSilver {
    require(batchIndex in 0 until totalBatches)
    Log.i(
        SILVER_LOG_TAG,
        "AI request started chunk=${batchIndex + 1}/$totalBatches bytes=${chunk.toByteArray().size}",
    )
    val response = collectExtractionResponse(
        runtime,
        SourceAiRequest(
            conversationId = "silver:${source.id}:$batchIndex",
            messages = listOf(
                SourceAiMessage(
                    SourceAiRole.USER,
                    listOf(SourceAiContent.Text(extractionPrompt(chunk))),
                ),
            ),
            workload = SourceAiWorkload.BACKGROUND,
        ),
    )
    Log.i(
        SILVER_LOG_TAG,
        "AI response received chunk=${batchIndex + 1}/$totalBatches durationMs=${response.durationMillis} " +
            "firstTokenMs=${response.firstTokenMillis ?: -1} inputTokens=${response.inputTokens ?: -1} " +
            "outputTokens=${response.outputTokens ?: -1} outputChars=${response.text.length} " +
            "reasoningBytes=${response.reasoningBytes ?: -1} finishReason=${response.finishReason}",
    )
    val parseStartedAt = SystemClock.elapsedRealtime()
    val parsed = try {
        parseExtraction(response.text, chunk)
    } catch (error: Exception) {
        Log.w(
            SILVER_LOG_TAG,
            "response parse failed chunk=${batchIndex + 1}/$totalBatches " +
                "durationMs=${SystemClock.elapsedRealtime() - parseStartedAt} " +
                "outputChars=${response.text.length} error=${error.javaClass.simpleName}",
        )
        throw error
    }
    Log.i(
        SILVER_LOG_TAG,
        "response parsed chunk=${batchIndex + 1}/$totalBatches " +
            "durationMs=${SystemClock.elapsedRealtime() - parseStartedAt} " +
            "rawEntities=${parsed.rawEntityCount} entities=${parsed.entityCount} " +
            "rawClaims=${parsed.rawClaimCount} claims=${parsed.claimCount}",
    )
    val model = response.model ?: error("The AI runtime did not identify the model used")
    val evidence = SilverEvidence.create(
        bronzeSourceId = source.id,
        bronzeContentSha256 = source.contentSha256,
    )
    val producer = SilverProducer.create(
        processorId = SILVER_EXTRACTION_PROCESSOR_ID,
        processorVersion = processorVersion,
        modelId = model.modelId,
    )
    val observations = parsed.findings.map { finding ->
        SilverObservation.create(
            kind = finding.kind,
            payload = finding.payload,
            evidenceIds = listOf(evidence.id),
            confidence = finding.confidence,
            producer = producer,
            createdAtMillis = createdAtMillis,
        )
    } + SilverObservation.create(
        kind = SILVER_EXTRACTION_COMPLETE_KIND,
        payload = SilverJsonObject(emptyMap()),
        evidenceIds = listOf(evidence.id),
        producer = producer,
        createdAtMillis = createdAtMillis,
    )
    return ExtractedSilver(listOf(evidence), observations, model)
}

internal fun combineSilverBatches(batches: List<ExtractedSilver>): ExtractedSilver {
    require(batches.isNotEmpty())
    val model = batches.first().model
    require(batches.all { it.model == model }) { "The AI runtime changed while refining one Bronze item" }
    val evidence = linkedMapOf<String, SilverEvidence>()
    val observations = linkedMapOf<String, SilverObservation>()
    batches.forEach { batch ->
        batch.evidence.forEach { evidence.putIfAbsent(it.id, it) }
        batch.observations.forEach { observation ->
            val existing = observations[observation.id]
            if (existing == null || observation.createdAtMillis < existing.createdAtMillis) {
                observations[observation.id] = observation
            }
        }
    }
    return ExtractedSilver(evidence.values.toList(), observations.values.toList(), model)
}

internal fun deterministicTextChunks(text: String, maximumUtf8Bytes: Int = MAXIMUM_CHUNK_UTF8_BYTES): List<String> {
    require(maximumUtf8Bytes >= 4)
    if (text.isBlank()) return emptyList()
    val chunks = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        var low = start + 1
        var high = minOf(start + maximumUtf8Bytes, text.length)
        var end = low
        while (low <= high) {
            val candidate = (low + high) ushr 1
            if (text.substring(start, candidate).toByteArray(Charsets.UTF_8).size <= maximumUtf8Bytes) {
                end = candidate
                low = candidate + 1
            } else {
                high = candidate - 1
            }
        }
        if (end < text.length && end > start + 1 && text[end - 1].isHighSurrogate()) end -= 1
        if (end < text.length) {
            val preferred = (end downTo start + (end - start) / 2).firstOrNull { index ->
                text[index - 1] == '\n' || text[index - 1].isWhitespace()
            }
            if (preferred != null) end = preferred
        }
        text.substring(start, end).trim().takeIf(String::isNotEmpty)?.let(chunks::add)
        start = end
        while (start < text.length && text[start].isWhitespace()) start += 1
    }
    return chunks
}

internal fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

private data class ExtractionResponse(
    val text: String,
    val model: AiModelMetadata?,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val reasoningBytes: Int?,
    val finishReason: String,
    val durationMillis: Long,
    val firstTokenMillis: Long?,
)

private suspend fun collectExtractionResponse(
    runtime: SourceAiRuntime,
    request: SourceAiRequest,
): ExtractionResponse {
    val requestStartedAt = SystemClock.elapsedRealtime()
    val output = StringBuilder()
    var completed = false
    var model: AiModelMetadata? = null
    var inputTokens: Int? = null
    var outputTokens: Int? = null
    var reasoningBytes: Int? = null
    var finishReason = "unknown"
    var firstTokenAt: Long? = null
    runtime.stream(request).collect { event ->
        check(event.runId == request.runId) { "The AI runtime returned the wrong run identifier" }
        when (event) {
            is SourceAiEvent.Started -> model = event.model
            is SourceAiEvent.Delta -> {
                if (firstTokenAt == null) firstTokenAt = SystemClock.elapsedRealtime()
                output.append(event.text)
            }
            is SourceAiEvent.Completed -> {
                completed = true
                inputTokens = event.inputTokens
                outputTokens = event.outputTokens
                reasoningBytes = event.reasoningBytes
                finishReason = event.finishReason
            }
            is SourceAiEvent.Failed -> error("Silver extraction failed: ${event.code}")
        }
    }
    check(completed && output.isNotBlank()) { "The AI runtime returned an incomplete Silver extraction" }
    return ExtractionResponse(
        text = output.toString().trim(),
        model = model,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        reasoningBytes = reasoningBytes,
        finishReason = finishReason,
        durationMillis = SystemClock.elapsedRealtime() - requestStartedAt,
        firstTokenMillis = firstTokenAt?.minus(requestStartedAt),
    )
}

private data class CandidateEntity(val key: String, val name: String, val type: String)

private data class CandidateClaim(
    val subjectKey: String,
    val predicate: String,
    val objectKey: String?,
    val value: SilverJsonValue?,
    val confidence: Double,
    val evidenceExcerpt: String?,
)

private data class ParsedExtraction(
    val findings: List<CandidateFinding>,
    val rawEntityCount: Int,
    val rawClaimCount: Int,
    val entityCount: Int,
    val claimCount: Int,
)

private data class CandidateFinding(
    val kind: String,
    val payload: SilverJsonObject,
    val confidence: Double,
)

private fun parseExtraction(raw: String, bronzeChunk: String): ParsedExtraction {
    val json = raw.substring(raw.indexOf('{').takeIf { it >= 0 } ?: error("Silver extraction was not JSON"),
        (raw.lastIndexOf('}').takeIf { it >= 0 } ?: error("Silver extraction was not JSON")) + 1)
    val root = JSONObject(json)
    val rawEntities = root.optJSONArray("entities") ?: JSONArray()
    val entitiesByKey = linkedMapOf<String, CandidateEntity>()
    repeat(rawEntities.length()) { index ->
        val candidate = rawEntities.optJSONObject(index) ?: return@repeat
        val key = candidate.optString("key").trim().take(40)
        val name = normalizeEntityName(candidate.optString("name"))
        val type = normalizeEntityType(candidate.optString("type"))
        if (key.isEmpty() || name.isEmpty()) return@repeat
        entitiesByKey[key] = CandidateEntity(key, name, type)
    }
    val claims = mutableListOf<CandidateClaim>()
    val rawClaims = root.optJSONArray("claims") ?: JSONArray()
    repeat(rawClaims.length()) { index ->
        val candidate = rawClaims.optJSONObject(index) ?: return@repeat
        val subjectKey = candidate.optString("subjectKey").takeIf(entitiesByKey::containsKey) ?: return@repeat
        val predicate = cleanInline(candidate.optString("predicate"), 100)
        if (predicate.isEmpty()) return@repeat
        val rawScalar = candidate.opt("value").takeUnless { it == null || it == JSONObject.NULL }
        val scalarEntityKey = (rawScalar as? String)?.trim()?.takeIf(entitiesByKey::containsKey)
        val requestedObjectKey = candidate.optString("objectKey").trim().takeIf(String::isNotEmpty)
        val objectKey = (requestedObjectKey ?: scalarEntityKey)?.takeIf(entitiesByKey::containsKey)
        if (requestedObjectKey != null && objectKey == null) return@repeat
        if (objectKey == null && rawScalar is String && LOCAL_ENTITY_KEY_PATTERN.matches(rawScalar.trim())) {
            return@repeat
        }
        val scalar = if (objectKey == null) parseScalar(rawScalar) else null
        if ((objectKey == null) == (scalar == null)) return@repeat
        val confidence = candidate.optDouble("confidence", Double.NaN)
        if (!confidence.isFinite() || confidence !in 0.0..1.0) return@repeat
        val excerpt = cleanInline(candidate.optString("evidenceExcerpt"), 240).takeIf { excerptCandidate ->
            excerptCandidate.isNotEmpty() && bronzeChunk.contains(excerptCandidate, ignoreCase = true)
        }
        claims += CandidateClaim(subjectKey, predicate, objectKey, scalar, confidence, excerpt)
    }
    val referencedKeys = claims.flatMapTo(mutableSetOf()) { listOfNotNull(it.subjectKey, it.objectKey) }
    val entities = entitiesByKey.values.filter { it.key in referencedKeys }
    val findings = claims.map { claim -> candidateFinding(claim, entitiesByKey) }
    return ParsedExtraction(findings, rawEntities.length(), rawClaims.length(), entities.size, claims.size)
}

private fun entityJson(entity: CandidateEntity) = SilverJsonObject(mapOf(
    "name" to SilverJsonString(entity.name),
    "type" to SilverJsonString(entity.type),
))

private fun candidateFinding(
    claim: CandidateClaim,
    entities: Map<String, CandidateEntity>,
): CandidateFinding {
    val objectEntity = claim.objectKey?.let { checkNotNull(entities[it]) }
    return CandidateFinding(
        kind = if (objectEntity == null) SILVER_ATTRIBUTE_CANDIDATE_KIND else SILVER_RELATIONSHIP_CANDIDATE_KIND,
        payload = SilverJsonObject(buildMap {
            put("subject", entityJson(checkNotNull(entities[claim.subjectKey])))
            put("predicate", SilverJsonString(claim.predicate))
            objectEntity?.let { put("object", entityJson(it)) }
            claim.value?.let { put("value", it) }
            claim.evidenceExcerpt?.let { put("evidenceExcerpt", SilverJsonString(it)) }
        }),
        confidence = claim.confidence,
    )
}

private fun parseScalar(value: Any?): SilverJsonValue? = when (value) {
    is String -> cleanInline(value, 500).takeIf(String::isNotEmpty)?.let(::SilverJsonString)
    is Number -> value.toDouble().takeIf(Double::isFinite)?.let(::SilverJsonNumber)
    is Boolean -> SilverJsonBoolean(value)
    else -> null
}

private fun extractionPrompt(chunk: String): String = """
    Extract entity mentions and factual relationship or attribute candidates from the Bronze text below. Return compact JSON only, with this shape:
    {"entities":[{"key":"e1","name":"Robin","type":"person"},{"key":"e2","name":"Source","type":"project"}],"claims":[{"subjectKey":"e1","predicate":"created","objectKey":"e2","confidence":0.95,"evidenceExcerpt":"Robin created Source"},{"subjectKey":"e2","predicate":"status","value":"active","confidence":0.8,"evidenceExcerpt":"Source is active"}]}
    Entity keys are local references within this processor result, not global Source identities and never literal claim values. Every subjectKey and objectKey must match an entity declared in the same response. Use objectKey for relationships between named people, places, organizations, projects, technologies, and other entities. Use value only for actual scalar text, numbers, or booleans, never for strings like "e1" or "e2". Every claim must have exactly one of objectKey or value.
    Capture each useful explicit fact and relationship once; do not stop after only a few candidates when the text contains more. Include explicit family, location, work, education/background, interests, ownership/creation, and project-purpose relationships when present. Do not infer unstated facts or turn suggestions, questions, possibilities, or general observations into facts. Types and predicates should be short lowercase labels. Keep evidence excerpts verbatim, short, and under 240 characters when practical. If nothing useful exists, return empty arrays.

    Bronze text:
    $chunk
""".trimIndent()

private fun normalizeEntityName(value: String): String = cleanInline(value, 200)

private fun normalizeEntityType(value: String): String {
    val normalized = value.trim().lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .take(64)
    return normalized.takeIf { it.firstOrNull()?.isLetter() == true } ?: "concept"
}

private fun cleanInline(value: String, maximumLength: Int): String = value
    .replace(Regex("\\s+"), " ")
    .trim()
    .filterNot(Char::isISOControl)
    .take(maximumLength)

internal const val SILVER_EXTRACTION_PROCESSOR_ID = "source.android.silver-extraction"
internal const val SILVER_EXTRACTION_PROCESSOR_VERSION = "3"
internal const val SILVER_ATTRIBUTE_CANDIDATE_KIND = "attribute-candidate"
internal const val SILVER_RELATIONSHIP_CANDIDATE_KIND = "relationship-candidate"
internal const val SILVER_EXTRACTION_COMPLETE_KIND = "knowledge-extraction-complete"
private const val MAXIMUM_CHUNK_UTF8_BYTES = 2_400
private const val SILVER_LOG_TAG = "SourceSilver"
private val LOCAL_ENTITY_KEY_PATTERN = Regex("(?i)^e\\d{1,4}$")
