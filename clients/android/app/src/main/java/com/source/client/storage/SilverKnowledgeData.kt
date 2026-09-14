package com.source.client.storage

import java.util.UUID

data class SilverEntity(
    val id: String,
    val originObservationIds: List<String>,
    val createdBy: SilverProducer,
    val createdAtMillis: Long,
) {
    init {
        require(SILVER_ENTITY_ID_PATTERN.matches(id)) { "Invalid Silver Entity identifier" }
        require(originObservationIds.isNotEmpty()) { "A Silver Entity must reference its origin Observations" }
        require(originObservationIds.all(SILVER_RECORD_ID_PATTERN::matches)) {
            "Invalid Silver Entity origin Observation reference"
        }
        require(originObservationIds == originObservationIds.distinct().sorted()) {
            "Silver Entity origin Observation references must be sorted and unique"
        }
        require(createdAtMillis > 0) { "Invalid Silver Entity creation time" }
    }

    companion object {
        fun create(
            originObservationIds: Collection<String>,
            createdBy: SilverProducer,
            createdAtMillis: Long,
        ): SilverEntity = SilverEntity(
            id = UUID.randomUUID().toString(),
            originObservationIds = originObservationIds.distinct().sorted(),
            createdBy = createdBy,
            createdAtMillis = createdAtMillis,
        )
    }
}

enum class SilverScalarType(val storageValue: String) {
    TEXT("text"),
    NUMBER("number"),
    BOOLEAN("boolean");

    companion object {
        fun fromStorageValue(value: String): SilverScalarType = entries.firstOrNull { it.storageValue == value }
            ?: throw IllegalArgumentException("Unsupported Silver scalar type")
    }
}

data class SilverScalar(
    val type: SilverScalarType,
    val value: SilverJsonValue,
) {
    init {
        require(value == normalizeSilverJson(value)) { "Silver scalar value must be normalized" }
        require(
            (type == SilverScalarType.TEXT && value is SilverJsonString) ||
                (type == SilverScalarType.NUMBER && value is SilverJsonNumber) ||
                (type == SilverScalarType.BOOLEAN && value is SilverJsonBoolean),
        ) { "Silver scalar type does not match its value" }
    }

    companion object {
        fun text(value: String) = SilverScalar(SilverScalarType.TEXT, SilverJsonString(normalizeSilverText(value)))
        fun number(value: Double) = SilverScalar(
            SilverScalarType.NUMBER,
            SilverJsonNumber(if (value == 0.0) 0.0 else value),
        )
        fun boolean(value: Boolean) = SilverScalar(SilverScalarType.BOOLEAN, SilverJsonBoolean(value))
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
    val value: SilverScalar? = null,
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
            value: SilverScalar? = null,
            supportingObservationIds: Collection<String>,
            confidence: Double? = null,
            producer: SilverProducer,
            state: SilverClaimState = SilverClaimState.ACTIVE,
            createdAtMillis: Long,
        ): SilverClaim {
            val normalizedPredicate = normalizeSilverText(predicate)
            val normalizedObservationIds = supportingObservationIds.distinct().sorted()
            val normalizedConfidence = if (confidence == 0.0) 0.0 else confidence
            return SilverClaim(
                id = claimId(
                    subjectEntityId,
                    normalizedPredicate,
                    objectEntityId,
                    value,
                    normalizedObservationIds,
                    normalizedConfidence,
                    producer,
                ),
                subjectEntityId = subjectEntityId,
                predicate = normalizedPredicate,
                objectEntityId = objectEntityId,
                value = value,
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
    value: SilverScalar?,
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

internal fun claimObjectIdentity(objectEntityId: String?, value: SilverScalar?): SilverJsonObject = when {
    objectEntityId != null && value == null -> SilverJsonObject(mapOf(
        "entityId" to SilverJsonString(objectEntityId),
    ))
    objectEntityId == null && value != null -> SilverJsonObject(mapOf(
        "scalar" to SilverJsonObject(mapOf(
            "type" to SilverJsonString(value.type.storageValue),
            "value" to value.value,
        )),
    ))
    else -> throw IllegalArgumentException("A Silver Claim must contain exactly one Entity object or scalar value")
}

internal val SILVER_ENTITY_ID_PATTERN = Regex(
    "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
)
