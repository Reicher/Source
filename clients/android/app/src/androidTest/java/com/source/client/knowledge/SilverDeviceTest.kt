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
import com.source.client.model.AiModelMetadata
import com.source.client.storage.SilverClaim
import com.source.client.storage.SilverBatchCheckpoint
import com.source.client.storage.SilverCheckpointData
import com.source.client.storage.SilverCheckpointDataset
import com.source.client.storage.SilverData
import com.source.client.storage.SilverDataset
import com.source.client.storage.SilverEntity
import com.source.client.storage.SilverRefinementCheckpoint
import com.source.client.storage.SilverResult
import com.source.client.storage.SilverScalarValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SilverDeviceTest {
    @Test
    fun SilverCheckpointDataRoundTripsCompletedBatches() {
        val sourceId = "source-1"
        val sourceHash = "a".repeat(64)
        fun batchResult(processedAt: Long) = SilverResult(
            bronzeSourceId = sourceId,
            bronzeContentSha256 = sourceHash,
            entities = emptyList(),
            claims = emptyList(),
            modelId = "model-4b",
            parameterCount = 4_000_000_000,
            processorVersion = 2,
            processedAtMillis = processedAt,
        )
        val dataset = SilverCheckpointDataset(
            checkpoints = listOf(SilverRefinementCheckpoint(
                bronzeSourceId = sourceId,
                bronzeContentSha256 = sourceHash,
                processorVersion = 2,
                totalBatches = 3,
                completedBatches = listOf(
                    SilverBatchCheckpoint(0, "b".repeat(64), batchResult(100)),
                    SilverBatchCheckpoint(1, "c".repeat(64), batchResult(101)),
                ),
            )),
            refinementPaused = true,
        )

        assertEquals(dataset, SilverCheckpointData.decode(SilverCheckpointData.encode(dataset)))
    }

    @Test
    fun SilverDataRoundTripsTaggedClaimsAndProcessingMetadata() {
        val source = "source-1"
        val berlin = SilverEntity(entityId("place", "Berlin"), "Berlin", "place")
        val sourceProject = SilverEntity(entityId("project", "Source"), "Source", "project")
        val result = SilverResult(
            bronzeSourceId = source,
            bronzeContentSha256 = "a".repeat(64),
            entities = listOf(berlin, sourceProject),
            claims = listOf(
                SilverClaim(sourceProject.id, "located-in", objectEntityId = berlin.id, confidence = .9,
                    bronzeSourceId = source, evidenceExcerpt = "Source is in Berlin"),
                SilverClaim(sourceProject.id, "active", value = SilverScalarValue.BooleanValue(true), confidence = .8,
                    bronzeSourceId = source),
                SilverClaim(sourceProject.id, "members", value = SilverScalarValue.Number(4.0), confidence = .7,
                    bronzeSourceId = source),
            ),
            modelId = "model-9b",
            parameterCount = 9_000_000_000,
            processorVersion = 1,
            processedAtMillis = 100,
        )
        val dataset = SilverDataset(
            listOf(result),
            modifiedAtMillis = 101,
            removedSourceIds = mapOf("removed-source" to 99),
        )

        assertEquals(dataset, SilverData.decode(SilverData.encode(dataset)))
    }

    @Test
    fun ExtractionAssignsAppIdsAndKeepsOnlyVerifiedEvidence() = runBlocking {
        val model = AiModelMetadata("test-model", 9_000_000_000)
        val runtime = object : SourceAiRuntime {
            override val capabilities = SourceAiCapabilities(
                setOf(SourceAiCapability.TEXT), true, true, 8_192,
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
        assertEquals(setOf(entityId("project", "Source"), entityId("place", "Berlin")), extracted.entities.map { it.id }.toSet())
        assertEquals("Source is based in Berlin", extracted.claims.first().evidenceExcerpt)
        assertEquals(null, extracted.claims.last().evidenceExcerpt)
        assertEquals(3, extracted.claims.size)
        assertEquals(entityId("place", "Berlin"), extracted.claims[2].objectEntityId)
        assertEquals(null, extracted.claims[2].value)
    }

    @Test
    fun LocalModelProducesUsableSilverExtraction() = runBlocking {
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
        assertTrue("Expected entities from local extraction: $extracted", extracted.entities.isNotEmpty())
        assertTrue("Expected claims from local extraction: $extracted", extracted.claims.isNotEmpty())
        runtime.releaseMemory()
    }
}
