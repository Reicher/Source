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
import com.source.client.storage.SilverClaim
import com.source.client.storage.SilverEntity
import com.source.client.storage.SilverScalarValue
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject

data class BronzeTextSource(
    val id: String,
    val name: String,
    val sourceType: String,
    val contentSha256: String,
    val text: String,
)

data class ExtractedSilver(
    val entities: List<SilverEntity>,
    val claims: List<SilverClaim>,
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
            "entities=${extracted.entities.size} claims=${extracted.claims.size}",
    )
    return extracted
}

internal suspend fun extractSilverBatch(
    runtime: SourceAiRuntime,
    source: BronzeTextSource,
    chunk: String,
    batchIndex: Int,
    totalBatches: Int,
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
        parseExtraction(response.text, chunk, source.id)
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
            "rawEntities=${parsed.rawEntityCount} entities=${parsed.entities.size} " +
            "rawClaims=${parsed.rawClaimCount} claims=${parsed.claims.size}",
    )
    return ExtractedSilver(
        parsed.entities,
        parsed.claims,
        response.model ?: error("The AI runtime did not identify the model used"),
    )
}

internal fun combineSilverBatches(batches: List<ExtractedSilver>): ExtractedSilver {
    require(batches.isNotEmpty())
    val model = batches.first().model
    require(batches.all { it.model == model }) { "The AI runtime changed while refining one Bronze item" }
    val entities = linkedMapOf<String, SilverEntity>()
    val claims = linkedMapOf<String, SilverClaim>()
    batches.forEach { batch ->
        batch.entities.forEach { entities.putIfAbsent(it.id, it) }
        batch.claims.forEach { claim -> claims.putIfAbsent(claimIdentity(claim), claim) }
    }
    return ExtractedSilver(entities.values.toList(), claims.values.toList(), model)
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

internal fun entityId(type: String, name: String): String {
    val normalizedType = normalizeEntityType(type)
    val normalizedName = normalizeEntityName(name).lowercase(Locale.ROOT)
    return sha256Hex("source-entity-v1\u0000$normalizedType\u0000$normalizedName")
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

private data class ParsedExtraction(
    val entities: List<SilverEntity>,
    val claims: List<SilverClaim>,
    val rawEntityCount: Int,
    val rawClaimCount: Int,
)

private fun parseExtraction(raw: String, bronzeChunk: String, bronzeSourceId: String): ParsedExtraction {
    val json = raw.substring(raw.indexOf('{').takeIf { it >= 0 } ?: error("Silver extraction was not JSON"),
        (raw.lastIndexOf('}').takeIf { it >= 0 } ?: error("Silver extraction was not JSON")) + 1)
    val root = JSONObject(json)
    val rawEntities = root.optJSONArray("entities") ?: JSONArray()
    val entitiesByKey = linkedMapOf<String, SilverEntity>()
    repeat(rawEntities.length()) { index ->
        val value = rawEntities.optJSONObject(index) ?: return@repeat
        val key = value.optString("key").trim().take(40)
        val name = normalizeEntityName(value.optString("name"))
        val type = normalizeEntityType(value.optString("type"))
        if (key.isEmpty() || name.isEmpty()) return@repeat
        entitiesByKey[key] = SilverEntity(entityId(type, name), name, type)
    }
    val claims = mutableListOf<SilverClaim>()
    val rawClaims = root.optJSONArray("claims") ?: JSONArray()
    repeat(rawClaims.length()) { index ->
        val value = rawClaims.optJSONObject(index) ?: return@repeat
        val subject = entitiesByKey[value.optString("subjectKey")] ?: return@repeat
        val predicate = cleanInline(value.optString("predicate"), 100)
        if (predicate.isEmpty()) return@repeat
        val rawScalar = value.opt("value").takeUnless { it == null || it == JSONObject.NULL }
        val scalarEntityKey = (rawScalar as? String)?.trim()?.takeIf(entitiesByKey::containsKey)
        val requestedObjectKey = value.optString("objectKey").trim().takeIf(String::isNotEmpty)
        val objectEntity = (requestedObjectKey ?: scalarEntityKey)?.let(entitiesByKey::get)
        if (requestedObjectKey != null && objectEntity == null) return@repeat
        if (objectEntity == null && rawScalar is String && LOCAL_ENTITY_KEY_PATTERN.matches(rawScalar.trim())) {
            return@repeat
        }
        val scalar = if (objectEntity == null) {
            parseScalar(rawScalar)
        } else {
            null
        }
        if ((objectEntity == null) == (scalar == null)) return@repeat
        val confidence = value.optDouble("confidence", Double.NaN)
        if (!confidence.isFinite() || confidence !in 0.0..1.0) return@repeat
        val excerpt = cleanInline(value.optString("evidenceExcerpt"), 240).takeIf { candidate ->
            candidate.isNotEmpty() && bronzeChunk.contains(candidate, ignoreCase = true)
        }
        claims += SilverClaim(
            subjectEntityId = subject.id,
            predicate = predicate,
            objectEntityId = objectEntity?.id,
            value = scalar,
            confidence = confidence,
            bronzeSourceId = bronzeSourceId,
            evidenceExcerpt = excerpt,
        )
    }
    val referencedIds = claims.flatMapTo(mutableSetOf()) { claim ->
        listOfNotNull(claim.subjectEntityId, claim.objectEntityId)
    }
    return ParsedExtraction(
        entities = entitiesByKey.values.filter { it.id in referencedIds }.distinctBy(SilverEntity::id),
        claims = claims,
        rawEntityCount = rawEntities.length(),
        rawClaimCount = rawClaims.length(),
    )
}

private fun parseScalar(value: Any?): SilverScalarValue? = when (value) {
    is String -> cleanInline(value, 500).takeIf(String::isNotEmpty)?.let(SilverScalarValue::Text)
    is Number -> value.toDouble().takeIf(Double::isFinite)?.let(SilverScalarValue::Number)
    is Boolean -> SilverScalarValue.BooleanValue(value)
    else -> null
}

private fun extractionPrompt(chunk: String): String = """
    Extract entities and factual claims from the Bronze text below. Return compact JSON only, with this shape:
    {"entities":[{"key":"e1","name":"Robin","type":"person"},{"key":"e2","name":"Source","type":"project"}],"claims":[{"subjectKey":"e1","predicate":"created","objectKey":"e2","confidence":0.95,"evidenceExcerpt":"Robin created Source"},{"subjectKey":"e2","predicate":"status","value":"active","confidence":0.8,"evidenceExcerpt":"Source is active"}]}
    Entity keys are local references, never literal claim values. Every subjectKey and objectKey must match an entity declared in the same response. Use objectKey for relationships between named people, places, organizations, projects, technologies, and other entities. Use value only for actual scalar text, numbers, or booleans, never for strings like "e1" or "e2". Every claim must have exactly one of objectKey or value.
    Capture each useful explicit fact and relationship once; do not stop after only a few claims when the text contains more. Include explicit family, location, work, education/background, interests, ownership/creation, and project-purpose relationships when present. Do not infer unstated facts or turn suggestions, questions, possibilities, or general observations into facts. Types and predicates should be short lowercase labels. Keep evidence excerpts verbatim, short, and under 240 characters when practical. If nothing useful exists, return empty arrays.

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

private fun claimIdentity(claim: SilverClaim): String = listOf(
    claim.subjectEntityId,
    claim.predicate.lowercase(Locale.ROOT),
    claim.objectEntityId.orEmpty(),
    claim.value.toString(),
    claim.evidenceExcerpt.orEmpty(),
).joinToString("\u0000")

private const val MAXIMUM_CHUNK_UTF8_BYTES = 2_400
private const val SILVER_LOG_TAG = "SourceSilver"
private val LOCAL_ENTITY_KEY_PATTERN = Regex("(?i)^e\\d{1,4}$")
