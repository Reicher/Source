package com.source.client.storage

import java.util.UUID

data class SilverEntity(
    val id: String,
) {
    init {
        require(SILVER_ENTITY_ID_PATTERN.matches(id)) { "Invalid Silver Entity identifier" }
    }

    companion object {
        fun create(): SilverEntity = SilverEntity(UUID.randomUUID().toString())
    }
}

enum class SilverClaimState(val storageValue: String) {
    ACTIVE("active"),
    SUPERSEDED("superseded"),
    RETRACTED("retracted");

    companion object {
        fun fromStorageValue(value: String): SilverClaimState = entries.firstOrNull { it.storageValue == value }
            ?: throw IllegalArgumentException("Unsupported Silver Claim state")
    }
}

data class SilverClaim(
    val id: String,
    val subjectEntityId: String,
    val predicate: String,
    val objectEntityId: String? = null,
    val value: SilverJsonValue? = null,
    val supportingObservationIds: List<String>,
    val confidence: Double? = null,
    val producer: SilverProducer,
    val state: SilverClaimState = SilverClaimState.ACTIVE,
    val createdAtMillis: Long,
) {
    init {
        require(SILVER_RECORD_ID_PATTERN.matches(id)) { "Invalid Silver Claim identifier" }
        require(SILVER_ENTITY_ID_PATTERN.matches(subjectEntityId)) { "Invalid Silver Claim subject Entity reference" }
        requireValidSilverOpenIdentifier(predicate, "claim predicate")
        require(predicate == normalizeSilverText(predicate)) { "Silver Claim predicate must use NFC" }
        require((objectEntityId == null) != (value == null)) {
            "A Silver Claim must contain exactly one Entity object or scalar value"
        }
        objectEntityId?.let {
            require(SILVER_ENTITY_ID_PATTERN.matches(it)) { "Invalid Silver Claim object Entity reference" }
        }
        value?.let {
            require(it is SilverJsonString || it is SilverJsonNumber || it is SilverJsonBoolean) {
                "A Silver Claim scalar must be text, number, or boolean"
            }
            require(it == normalizeSilverJson(it)) { "Silver Claim scalar must be normalized" }
        }
        require(supportingObservationIds.isNotEmpty()) { "A Silver Claim must reference supporting Observations" }
        require(supportingObservationIds.all(SILVER_RECORD_ID_PATTERN::matches)) {
            "Invalid Silver Claim supporting Observation reference"
        }
        require(supportingObservationIds == supportingObservationIds.distinct().sorted()) {
            "Silver Claim supporting Observation references must be sorted and unique"
        }
        confidence?.let {
            require(it.isFinite() && it in 0.0..1.0) { "Invalid Silver Claim confidence" }
            require(it != 0.0 || it.toRawBits() == 0.0.toRawBits()) {
                "Silver Claim confidence must use canonical zero"
            }
        }
        require(createdAtMillis > 0) { "Invalid Silver Claim creation time" }
        require(
            id == claimId(
                subjectEntityId,
                predicate,
                objectEntityId,
                value,
                supportingObservationIds,
                confidence,
                producer,
            ),
        ) { "Silver Claim identifier does not match its identity fields" }
    }

    companion object {
        fun create(
            subjectEntityId: String,
            predicate: String,
            objectEntityId: String? = null,
            value: SilverJsonValue? = null,
            supportingObservationIds: Collection<String>,
            confidence: Double? = null,
            producer: SilverProducer,
            state: SilverClaimState = SilverClaimState.ACTIVE,
            createdAtMillis: Long,
        ): SilverClaim {
            val normalizedPredicate = normalizeSilverText(predicate)
            val normalizedObservationIds = supportingObservationIds.distinct().sorted()
            val normalizedConfidence = if (confidence == 0.0) 0.0 else confidence
            val normalizedValue = value?.let(::normalizeSilverJson)
            return SilverClaim(
                id = claimId(
                    subjectEntityId,
                    normalizedPredicate,
                    objectEntityId,
                    normalizedValue,
                    normalizedObservationIds,
                    normalizedConfidence,
                    producer,
                ),
                subjectEntityId = subjectEntityId,
                predicate = normalizedPredicate,
                objectEntityId = objectEntityId,
                value = normalizedValue,
                supportingObservationIds = normalizedObservationIds,
                confidence = normalizedConfidence,
                producer = producer,
                state = state,
                createdAtMillis = createdAtMillis,
            )
        }
    }
}

internal fun claimId(
    subjectEntityId: String,
    predicate: String,
    objectEntityId: String?,
    value: SilverJsonValue?,
    supportingObservationIds: Collection<String>,
    confidence: Double?,
    producer: SilverProducer,
): String = silverRecordId(
    SILVER_CLAIM_ID_PREFIX,
    SilverJsonObject(mapOf(
        "confidence" to (confidence?.let(::SilverJsonNumber) ?: SilverJsonNull),
        "object" to claimObjectIdentity(objectEntityId, value),
        "predicate" to SilverJsonString(predicate),
        "producer" to producerIdentity(producer),
        "subjectEntityId" to SilverJsonString(subjectEntityId),
        "supportingObservationIds" to SilverJsonArray(
            supportingObservationIds.distinct().sorted().map(::SilverJsonString),
        ),
    )),
)

internal fun claimObjectIdentity(objectEntityId: String?, value: SilverJsonValue?): SilverJsonObject = when {
    objectEntityId != null && value == null -> SilverJsonObject(mapOf(
        "entityId" to SilverJsonString(objectEntityId),
    ))
    objectEntityId == null && value != null -> SilverJsonObject(mapOf("scalar" to value))
    else -> throw IllegalArgumentException("A Silver Claim must contain exactly one Entity object or scalar value")
}

internal val SILVER_ENTITY_ID_PATTERN = Regex(
    "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
)
