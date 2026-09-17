package com.source.client.ui

import android.os.SystemClock
import android.util.Log
import com.source.client.knowledge.BronzeTextSource
import com.source.client.knowledge.SILVER_EXTRACTION_PROCESSOR_ID
import com.source.client.knowledge.SILVER_EXTRACTION_COMPLETE_KIND
import com.source.client.model.AiModelMetadata
import com.source.client.model.ConnectedNode
import com.source.client.protocol.SourceApiException
import com.source.client.protocol.SilverRefinementJob
import com.source.client.security.VaultSession
import com.source.client.storage.SilverData
import com.source.client.storage.SilverDataset
import com.source.client.storage.SilverObservation
import com.source.client.storage.SourceDataStore
import com.source.client.storage.SourceDataSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

data class SilverBatchProgress(
    val completedBatches: Int,
    val totalBatches: Int,
) {
    init {
        require(totalBatches > 0 && completedBatches in 0..totalBatches)
    }
}

data class SilverUiState(
    val dataset: SilverDataset = SilverDataset(),
    val jobs: Map<String, SilverRefinementJob> = emptyMap(),
    val syncErrors: Set<String> = emptySet(),
)

internal class SilverController(
    private val scope: CoroutineScope,
    private val localStore: SourceDataStore,
    private val nodeApi: com.source.client.protocol.SourceNodeApi,
    private val session: () -> VaultSession?,
    private val connectedNode: () -> ConnectedNode?,
    private val bronzeSources: suspend () -> List<BronzeTextSource>,
    private val onStateChanged: (SilverUiState) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val sync = SourceDataSync(SilverData, localStore, nodeApi)
    private var worker: Job? = null
    private var retryWorker: Job? = null
    // A refinement commit and a remote reconciliation both replace the complete persisted snapshot.
    // Keep their read/merge/persist/state transitions atomic so neither can publish stale Silver state.
    private val datasetMutex = Mutex()

    var state = SilverUiState()
        private set

    suspend fun reset(activeSession: VaultSession? = null) {
        val previousWorker = worker
        val previousRetryWorker = retryWorker
        worker = null
        retryWorker = null
        previousWorker?.cancelAndJoin()
        previousRetryWorker?.cancelAndJoin()
        datasetMutex.withLock {
            sync.reset()
            val dataset = if (activeSession == null) {
                SilverDataset()
            } else {
                localStore.load(activeSession, SilverData)
            }
            state = SilverUiState(dataset = dataset)
            publish()
        }
        if (activeSession != null) refresh()
    }

    fun refresh() {
        if (session() == null || worker?.isCompleted == false) return
        retryWorker?.cancel()
        retryWorker = null
        worker = scope.launch { refineAvailableSources() }
    }

    suspend fun synchronize() {
        val activeSession = session() ?: return
        val connected = connectedNode() ?: return
        datasetMutex.withLock {
            val syncStartedAt = SystemClock.elapsedRealtime()
            try {
                val jobs = nodeApi.silverRefinementJobs(
                    connected.discovered.apiBaseUrl,
                    connected.trusted,
                ).associateBy(SilverRefinementJob::sourceId)
                state = state.copy(jobs = jobs, syncErrors = emptySet())
                publish()
                state.dataset.removedSourceIds.keys.forEach { sourceId ->
                    nodeApi.removeSilver(
                        connected.discovered.apiBaseUrl,
                        connected.trusted,
                        sourceId,
                        silverRemovalOperationId(sourceId),
                    )
                }
            } catch (error: Exception) {
                val awaiting = state.jobs.values.filter { it.state == "completed" }.mapTo(mutableSetOf()) { it.sourceId }
                state = state.copy(syncErrors = state.syncErrors + awaiting)
                Log.w(SILVER_LOG_TAG, "Silver removal sync failed error=${syncErrorSummary(error)}")
                publish()
                scheduleRetry()
                return@withLock
            }
            sync.synchronize(activeSession, connected, state.dataset) { remote ->
                state = state.copy(
                    dataset = remote,
                    syncErrors = emptySet(),
                )
                publish()
            }
            val error = sync.lastError
            if (error == null) {
                state = state.copy(syncErrors = emptySet())
                Log.i(SILVER_LOG_TAG, "Silver sync finished durationMs=${SystemClock.elapsedRealtime() - syncStartedAt}")
            } else {
                val awaiting = state.jobs.values.filter { it.state == "completed" }.mapTo(mutableSetOf()) { it.sourceId }
                state = state.copy(syncErrors = awaiting)
                Log.w(
                    SILVER_LOG_TAG,
                    "Silver sync failed durationMs=${SystemClock.elapsedRealtime() - syncStartedAt} " +
                        "error=${syncErrorSummary(error)}",
                )
            }
            publish()
        }
        refresh()
    }

    fun removeSource(sourceId: String) {
        val activeSession = session() ?: return
        scope.launch {
            val removed = datasetMutex.withLock {
                if (
                    state.dataset.evidence.none { it.bronzeSourceId == sourceId } &&
                    sourceId in state.dataset.removedSourceIds
                ) return@withLock false
                val now = nextModifiedAt()
                val affectedSourceIds = setOf(sourceId)
                state = state.copy(
                    dataset = removeSilverSources(state.dataset, affectedSourceIds, now),
                    jobs = state.jobs - affectedSourceIds,
                    syncErrors = state.syncErrors - affectedSourceIds,
                )
                sync.changed()
                publish()
                sync.persist(activeSession, state.dataset)
                true
            }
            if (removed) {
                synchronize()
                refresh()
            }
        }
    }

    private suspend fun refineAvailableSources() {
        val attempted = mutableSetOf<String>()
        var activeSourceId: String? = null
        try {
            while (true) {
                val connected = connectedNode() ?: run {
                    return
                }
                val desiredModel = connected.aiModel ?: run {
                    return
                }
                val sources = bronzeSources().filter { it.text.isNotBlank() }
                val candidateSources = sources.filter { source ->
                    source.id !in attempted && needsSilverRefinement(
                        state.dataset,
                        source,
                        desiredModel,
                        NODE_EXTRACTION_PROCESSOR_VERSION,
                        NODE_EXTRACTION_PROCESSOR_ID,
                    )
                }
                if (candidateSources.isEmpty()) {
                    return
                }
                val source = candidateSources.first()
                activeSourceId = source.id
                attempted += source.id
                Log.i(
                    SILVER_LOG_TAG,
                    "Node refinement candidate started sourceType=${source.sourceType} " +
                        "bytes=${source.text.toByteArray(Charsets.UTF_8).size}",
                )
                var job = nodeApi.refineSilver(
                    connected.discovered.apiBaseUrl,
                    connected.trusted,
                    source,
                    UUID.randomUUID().toString(),
                )
                state = state.copy(
                    jobs = state.jobs + (source.id to job),
                    syncErrors = state.syncErrors - source.id,
                )
                publish()
                job = observeNodeJob(connected, source.id, job)
                if (job.state == "cancelled") {
                    continue
                }
                synchronize()
                activeSourceId = null
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(
                SILVER_LOG_TAG,
                "refinement failed error=${syncErrorSummary(error)}; retryMs=$RETRY_DELAY_MILLIS",
            )
            activeSourceId?.let { state = state.copy(syncErrors = state.syncErrors + it) }
            publish()
            scheduleRetry()
        } finally {
            worker = null
        }
    }

    private fun publish() = onStateChanged(state)

    private suspend fun observeNodeJob(
        connected: ConnectedNode,
        sourceId: String,
        initial: SilverRefinementJob,
    ): SilverRefinementJob {
        var job = initial
        while (true) {
            state = state.copy(
                jobs = state.jobs + (sourceId to job),
                syncErrors = state.syncErrors - sourceId,
            )
            publish()
            when (job.state) {
                "completed", "cancelled" -> return job
                "failed" -> throw SourceApiException(
                        job.errorCode ?: "silver_refinement_failed",
                        "The Node could not refine knowledge from this source.",
                    )
                else -> {
                    delay(JOB_POLL_MILLIS)
                    job = nodeApi.silverRefinementJob(
                        connected.discovered.apiBaseUrl,
                        connected.trusted,
                        job.id,
                    )
                }
            }
        }
    }

    private fun nextModifiedAt(): Long = maxOf(clock().coerceAtLeast(1), state.dataset.modifiedAtMillis + 1)

    private fun scheduleRetry() {
        if (retryWorker?.isActive == true) return
        retryWorker = scope.launch {
            delay(RETRY_DELAY_MILLIS)
            retryWorker = null
            refresh()
        }
    }

    companion object {
        private const val RETRY_DELAY_MILLIS = 30_000L
        private const val JOB_POLL_MILLIS = 1_000L
        private const val SILVER_LOG_TAG = "SourceSilver"
    }
}

internal fun removeSilverSources(
    dataset: SilverDataset,
    sourceIds: Set<String>,
    removedAtMillis: Long,
): SilverDataset {
    require(sourceIds.isNotEmpty())
    require(removedAtMillis > 0)
    val removedEvidenceIds = dataset.evidence.filter { it.bronzeSourceId in sourceIds }.mapTo(mutableSetOf()) { it.id }
    val observations = dataset.observations.filterNot { observation ->
        observation.evidenceIds.any(removedEvidenceIds::contains)
    }
    val observationIds = observations.mapTo(mutableSetOf()) { it.id }
    val claims = dataset.claims.filter { claim ->
        observationIds.containsAll(claim.supportingObservationIds)
    }
    val referencedEntityIds = claims.flatMapTo(mutableSetOf()) { claim ->
        listOfNotNull(claim.subjectEntityId, claim.objectEntityId)
    }
    val entities = dataset.entities.filter { it.id in referencedEntityIds }
    val referencedEvidenceIds = observations.flatMapTo(mutableSetOf(), SilverObservation::evidenceIds)
    return dataset.copy(
        evidence = dataset.evidence.filter { evidence ->
            evidence.bronzeSourceId !in sourceIds && evidence.id in referencedEvidenceIds
        },
        observations = observations,
        entities = entities,
        claims = claims,
        modifiedAtMillis = removedAtMillis,
        removedSourceIds = dataset.removedSourceIds + sourceIds.associateWith { sourceId ->
            maxOf(dataset.removedSourceIds[sourceId] ?: 0, removedAtMillis)
        },
    )
}

private fun syncErrorSummary(error: Exception?): String = when (error) {
    is SourceApiException -> "SourceApiException:${error.code}"
    null -> "unknown"
    else -> error.javaClass.simpleName
}

internal fun needsSilverRefinement(
    dataset: SilverDataset,
    source: BronzeTextSource,
    desiredModel: AiModelMetadata,
    processorVersion: String,
    processorId: String = SILVER_EXTRACTION_PROCESSOR_ID,
): Boolean {
    val matchingEvidenceIds = dataset.evidence.filter { evidence ->
        evidence.bronzeSourceId == source.id && evidence.bronzeContentSha256 == source.contentSha256
    }.mapTo(mutableSetOf()) { it.id }
    return dataset.observations.none { observation ->
        observation.kind == SILVER_EXTRACTION_COMPLETE_KIND &&
            observation.producer.processorId == processorId &&
            observation.producer.processorVersion == processorVersion &&
            observation.producer.modelId == desiredModel.modelId &&
            observation.evidenceIds.any(matchingEvidenceIds::contains)
    }
}

private const val NODE_EXTRACTION_PROCESSOR_ID = "source.node.silver-extraction"
internal const val NODE_EXTRACTION_PROCESSOR_VERSION = "4"

internal fun silverRemovalOperationId(sourceId: String): String = UUID.nameUUIDFromBytes(
    "source-silver-removal\u0000$sourceId".toByteArray(Charsets.UTF_8),
).toString()
