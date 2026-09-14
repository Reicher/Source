package com.source.client.storage

data class SilverProducer(
    val processorId: String,
    val processorVersion: String,
    val modelId: String? = null,
    val modelRevision: String? = null,
) {
    init {
        requireValidSilverOpenIdentifier(processorId, "processor identifier")
        requireValidSilverOpenIdentifier(processorVersion, "processor version")
        modelId?.let { requireValidSilverOpenIdentifier(it, "model identifier") }
        modelRevision?.let { requireValidSilverOpenIdentifier(it, "model revision") }
        require(processorId == normalizeSilverText(processorId)) { "Silver processor identifier must use NFC" }
        require(processorVersion == normalizeSilverText(processorVersion)) { "Silver processor version must use NFC" }
        require(modelId == null || modelId == normalizeSilverText(modelId)) { "Silver model identifier must use NFC" }
        require(modelRevision == null || modelRevision == normalizeSilverText(modelRevision)) {
            "Silver model revision must use NFC"
        }
    }

    companion object {
        fun create(
            processorId: String,
            processorVersion: String,
            modelId: String? = null,
            modelRevision: String? = null,
        ) = SilverProducer(
            normalizeSilverText(processorId),
            normalizeSilverText(processorVersion),
            modelId?.let(::normalizeSilverText),
            modelRevision?.let(::normalizeSilverText),
        )
    }
}

data class SilverEvidence(
    val id: String,
    val bronzeSourceId: String,
    val bronzeContentSha256: String,
    val selector: SilverJsonValue? = null,
    val excerpt: String? = null,
) {
    init {
        require(SILVER_RECORD_ID_PATTERN.matches(id)) { "Invalid Silver Evidence identifier" }
        requireValidSourceId(bronzeSourceId)
        require(SILVER_RECORD_ID_PATTERN.matches(bronzeContentSha256)) { "Invalid Bronze content identity" }
        require(bronzeSourceId == normalizeSilverText(bronzeSourceId)) { "Bronze source identifier must use NFC" }
        selector?.let {
            require(it == normalizeSilverJson(it)) { "Silver Evidence selector must be normalized" }
        }
        excerpt?.let {
            require(it.isNotBlank() && it.length <= 240 && it.none(Char::isISOControl)) {
                "Invalid Silver Evidence excerpt"
            }
            require(it == normalizeSilverText(it)) { "Silver Evidence excerpt must use NFC" }
        }
        require(id == evidenceId(bronzeSourceId, bronzeContentSha256, selector)) {
            "Silver Evidence identifier does not match its identity fields"
        }
    }

    companion object {
        fun create(
            bronzeSourceId: String,
            bronzeContentSha256: String,
            selector: SilverJsonValue? = null,
            excerpt: String? = null,
        ): SilverEvidence {
            val normalizedSourceId = normalizeSilverText(bronzeSourceId)
            val normalizedSelector = selector?.let(::normalizeSilverJson)
            val normalizedExcerpt = excerpt?.let(::normalizeSilverText)
            return SilverEvidence(
                evidenceId(normalizedSourceId, bronzeContentSha256, normalizedSelector),
                normalizedSourceId,
                bronzeContentSha256,
                normalizedSelector,
                normalizedExcerpt,
            )
        }
    }
}

data class SilverObservation(
    val id: String,
    val kind: String,
    val payload: SilverJsonValue,
    val evidenceIds: List<String>,
    val confidence: Double? = null,
    val producer: SilverProducer,
    val createdAtMillis: Long,
) {
    init {
        require(SILVER_RECORD_ID_PATTERN.matches(id)) { "Invalid Silver Observation identifier" }
        requireValidSilverOpenIdentifier(kind, "observation kind")
        require(kind == normalizeSilverText(kind)) { "Silver Observation kind must use NFC" }
        require(payload == normalizeSilverJson(payload)) { "Silver Observation payload must be normalized" }
        require(evidenceIds.isNotEmpty()) { "A Silver Observation must reference Evidence" }
        require(evidenceIds.all(SILVER_RECORD_ID_PATTERN::matches)) { "Invalid Silver Evidence reference" }
        require(evidenceIds == evidenceIds.distinct().sorted()) {
            "Silver Evidence references must be sorted and unique"
        }
        confidence?.let {
            require(it.isFinite() && it in 0.0..1.0) { "Invalid Silver Observation confidence" }
            require(it != 0.0 || it.toRawBits() == 0.0.toRawBits()) {
                "Silver Observation confidence must use canonical zero"
            }
        }
        require(createdAtMillis > 0) { "Invalid Silver Observation creation time" }
        require(id == observationId(kind, payload, evidenceIds, confidence, producer)) {
            "Silver Observation identifier does not match its identity fields"
        }
    }

    companion object {
        fun create(
            kind: String,
            payload: SilverJsonValue,
            evidenceIds: Collection<String>,
            confidence: Double? = null,
            producer: SilverProducer,
            createdAtMillis: Long,
        ): SilverObservation {
            val normalizedKind = normalizeSilverText(kind)
            val normalizedPayload = normalizeSilverJson(payload)
            val normalizedEvidenceIds = evidenceIds.distinct().sorted()
            val normalizedConfidence = if (confidence == 0.0) 0.0 else confidence
            return SilverObservation(
                observationId(normalizedKind, normalizedPayload, normalizedEvidenceIds, normalizedConfidence, producer),
                normalizedKind,
                normalizedPayload,
                normalizedEvidenceIds,
                normalizedConfidence,
                producer,
                createdAtMillis,
            )
        }
    }
}

internal fun evidenceId(
    bronzeSourceId: String,
    bronzeContentSha256: String,
    selector: SilverJsonValue?,
): String = silverRecordId(
    SILVER_EVIDENCE_ID_PREFIX,
    SilverJsonObject(mapOf(
        "bronzeContentSha256" to SilverJsonString(bronzeContentSha256),
        "bronzeSourceId" to SilverJsonString(bronzeSourceId),
        "selector" to (selector ?: SilverJsonNull),
    )),
)

internal fun observationId(
    kind: String,
    payload: SilverJsonValue,
    evidenceIds: Collection<String>,
    confidence: Double?,
    producer: SilverProducer,
): String = silverRecordId(
    SILVER_OBSERVATION_ID_PREFIX,
    SilverJsonObject(mapOf(
        "confidence" to (confidence?.let(::SilverJsonNumber) ?: SilverJsonNull),
        "evidenceIds" to SilverJsonArray(evidenceIds.distinct().sorted().map(::SilverJsonString)),
        "kind" to SilverJsonString(kind),
        "payload" to payload,
        "producer" to producerIdentity(producer),
    )),
)

internal fun producerIdentity(producer: SilverProducer) = SilverJsonObject(mapOf(
    "modelId" to (producer.modelId?.let(::SilverJsonString) ?: SilverJsonNull),
    "modelRevision" to (producer.modelRevision?.let(::SilverJsonString) ?: SilverJsonNull),
    "processorId" to SilverJsonString(producer.processorId),
    "processorVersion" to SilverJsonString(producer.processorVersion),
))

internal fun requireValidSilverOpenIdentifier(value: String, description: String) {
    require(value.isNotBlank() && value.length <= 200 && value.none(Char::isISOControl)) {
        "Invalid Silver $description"
    }
}

private fun requireValidSourceId(value: String) {
    require(value.isNotBlank() && value.length <= 240 && value.none(Char::isISOControl)) {
        "Invalid Bronze source reference"
    }
}

internal const val SILVER_EVIDENCE_ID_PREFIX = "source-silver-evidence"
internal const val SILVER_OBSERVATION_ID_PREFIX = "source-silver-observation"
internal const val SILVER_CLAIM_ID_PREFIX = "source-silver-claim"
internal val SILVER_RECORD_ID_PATTERN = Regex("^[0-9a-f]{64}$")
