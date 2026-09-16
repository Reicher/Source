package com.source.client.storage

internal fun testEvidence(
    sourceId: String = "source-1",
    sourceHash: String = "a".repeat(64),
) = SilverEvidence.create(sourceId, sourceHash)

internal fun testObservation(
    evidence: SilverEvidence = testEvidence(),
    createdAtMillis: Long = 100,
    processorVersion: String = "2",
    modelId: String = "model-4b",
    kind: String = "knowledge-candidates",
    payload: SilverJsonValue = SilverJsonObject(mapOf(
        "claims" to SilverJsonArray(emptyList()),
        "entities" to SilverJsonArray(emptyList()),
    )),
) = SilverObservation.create(
    kind = kind,
    payload = payload,
    evidenceIds = listOf(evidence.id),
    producer = SilverProducer.create(
        processorId = "source.android.silver-extraction",
        processorVersion = processorVersion,
        modelId = modelId,
    ),
    createdAtMillis = createdAtMillis,
)
