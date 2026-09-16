package com.source.client.knowledge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.source.client.ai.LocalAiRuntime
import com.source.client.ai.SourceAiCapabilities
import com.source.client.ai.SourceAiCapability
import com.source.client.ai.SourceAiEvent
import com.source.client.ai.SourceAiRequest
import com.source.client.ai.SourceAiRuntime
import com.source.client.ai.SourceAiRuntimeState
import com.source.client.ai.SourceAiAvailability
import com.source.client.model.AiModelMetadata
import com.source.client.storage.SilverClaim
import com.source.client.storage.SilverData
import com.source.client.storage.SilverDataset
import com.source.client.storage.SilverEntity
import com.source.client.storage.SilverEvidence
import com.source.client.storage.SilverJsonObject
import com.source.client.storage.SilverJsonString
import com.source.client.storage.SilverObservation
import com.source.client.storage.SilverProducer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SilverDeviceTest {
    @Test(expected = RuntimeException::class)
    fun OldSilverShapeIsRejectedRatherThanMigrated() {
        val legacy = """{"version":1,"modifiedAtMillis":100,"results":[],"removedSources":[]}"""

        SilverData.decode(legacy.toByteArray())
    }

    @Test
    fun SilverDataRoundTripsEvidenceObservationsEntitiesClaimsAndProducerMetadata() {
        val evidence = SilverEvidence.create(
            "source-1",
            "a".repeat(64),
            selector = SilverJsonObject(mapOf(
                "kind" to SilverJsonString("page"),
                "number" to com.source.client.storage.SilverJsonNumber(2.0),
            )),
            excerpt = "Source is in Berlin",
        )
        val observation = observation(evidence, 100)
        val subject = SilverEntity("00000000-0000-4000-8000-000000000001")
        val related = SilverEntity("00000000-0000-4000-8000-000000000002")
        val claims = listOf(
            SilverClaim.create(
                subject.id,
                "status",
                value = SilverJsonString("active"),
                supportingObservationIds = listOf(observation.id),
                producer = observation.producer,
                createdAtMillis = 102,
            ),
            SilverClaim.create(
                subject.id,
                "based-in",
                objectEntityId = related.id,
                supportingObservationIds = listOf(observation.id),
                confidence = .9,
                producer = observation.producer,
                createdAtMillis = 102,
            ),
        )
        val dataset = SilverDataset(
            evidence = listOf(evidence),
            observations = listOf(observation),
            modifiedAtMillis = 103,
            entities = listOf(subject, related),
            claims = claims,
        )

        assertEquals(dataset, SilverData.decode(SilverData.encode(dataset)))
    }

    @Test
    fun ExtractionStoresLocalCandidatesWithoutCreatingGlobalEntities() = runBlocking {
        val model = AiModelMetadata("test-model", 9_000_000_000)
        val runtime = object : SourceAiRuntime {
            override val runtimeState = SourceAiRuntimeState(
                SourceAiAvailability.READY,
                model,
                SourceAiCapabilities(setOf(SourceAiCapability.TEXT), true, true, 8_192),
            )

            override fun stream(request: SourceAiRequest): Flow<SourceAiEvent> = flowOf(
                SourceAiEvent.Started(request.runId, model),
                SourceAiEvent.Delta(request.runId, 0, """{"entities":[{"key":"e1","name":"Source","type":"Project"},{"key":"e2","name":"Berlin","type":"Place"}],"claims":[{"subjectKey":"e1","predicate":"based-in","objectKey":"e2","confidence":0.9,"evidenceExcerpt":"Source is based in Berlin"},{"subjectKey":"e1","predicate":"status","value":"active","confidence":0.8,"evidenceExcerpt":"invented evidence"},{"subjectKey":"e1","predicate":"created-in","value":"e2","confidence":0.7},{"subjectKey":"e1","predicate":"bad-reference","value":"e9","confidence":0.7}]}"""),
                SourceAiEvent.Completed(request.runId, "stop"),
            )
        }
        val extracted = extractSilver(
            runtime,
            BronzeTextSource("source-1", "notes.txt", "file", "a".repeat(64), "Source is based in Berlin and active."),
        )

        assertEquals(model, extracted.model)
        assertEquals(1, extracted.evidence.size)
        assertEquals(4, extracted.observations.size)
        val completion = extracted.observations.single { it.kind == SILVER_EXTRACTION_COMPLETE_KIND }
        assertEquals(SILVER_EXTRACTION_PROCESSOR_ID, completion.producer.processorId)
        assertEquals(model.modelId, completion.producer.modelId)
        assertNull(completion.confidence)
        val candidates = extracted.observations.filterNot { it.kind == SILVER_EXTRACTION_COMPLETE_KIND }
        assertEquals(3, candidates.size)
        val first = candidates[0]
        val second = candidates[1]
        val third = candidates[2]
        assertEquals(SILVER_RELATIONSHIP_CANDIDATE_KIND, first.kind)
        assertEquals(.9, first.confidence ?: 0.0, 0.0)
        assertEquals(
            SilverJsonString("Source is based in Berlin"),
            (first.payload as SilverJsonObject).properties["evidenceExcerpt"],
        )
        assertEquals(SILVER_ATTRIBUTE_CANDIDATE_KIND, second.kind)
        assertNull((second.payload as SilverJsonObject).properties["evidenceExcerpt"])
        val thirdPayload = third.payload as SilverJsonObject
        assertEquals(SILVER_RELATIONSHIP_CANDIDATE_KIND, third.kind)
        assertEquals(SilverJsonString("Berlin"),
            ((thirdPayload.properties["object"] as SilverJsonObject).properties["name"]))
        assertNull(thirdPayload.properties["value"])
    }

    @Test
    fun LocalModelProducesUsableSilverObservations() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("sourceQwenRuntime") == "true")
        val runtime = LocalAiRuntime(ApplicationProvider.getApplicationContext<Context>())
        val extracted = withTimeout(180_000) {
            extractSilver(
                runtime,
                BronzeTextSource(
                    "device-source",
                    "project.txt",
                    "file",
                    "b".repeat(64),
                    "Source is a private software project based in Stockholm. Robin leads Source.",
                ),
            )
        }
        assertEquals(4_000_000_000L, extracted.model.parameterCount)
        assertTrue("Expected observations from local extraction: $extracted", extracted.observations.isNotEmpty())
        runtime.releaseMemory()
    }

    private fun observation(evidence: SilverEvidence, createdAtMillis: Long) = SilverObservation.create(
        kind = SILVER_EXTRACTION_COMPLETE_KIND,
        payload = SilverJsonObject(emptyMap()),
        evidenceIds = listOf(evidence.id),
        producer = SilverProducer.create(SILVER_EXTRACTION_PROCESSOR_ID, "2", "model-4b"),
        createdAtMillis = createdAtMillis,
    )
}
