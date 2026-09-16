package com.source.client.ui

import android.os.SystemClock
import android.util.Log
import com.source.client.knowledge.BronzeTextSource
import com.source.client.knowledge.sha256Hex
import com.source.client.knowledge.SILVER_EXTRACTION_PROCESSOR_ID
import com.source.client.knowledge.SILVER_EXTRACTION_COMPLETE_KIND
import com.source.client.model.AiModelMetadata
import com.source.client.model.ConnectedNode
import com.source.client.protocol.SourceApiException
import com.source.client.protocol.SilverRefinementJob
import com.source.client.security.VaultSession
import com.source.client.storage.SilverBatchCheckpoint
import com.source.client.storage.SilverCheckpointData
import com.source.client.storage.SilverCheckpointDataset
import com.source.client.storage.SilverData
import com.source.client.storage.SilverDataset
import com.source.client.storage.SilverObservation
import com.source.client.storage.SilverRefinementCheckpoint
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

enum class SilverProcessingState { QUEUED, PROCESSING }

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
    val processing: Map<String, SilverProcessingState> = emptyMap(),
    val progress: Map<String, SilverBatchProgress> = emptyMap(),
    val refinementPaused: Boolean = false,
    val pendingSync: Set<String> = emptySet(),
    val syncing: Set<String> = emptySet(),
    val syncFailed: Set<String> = emptySet(),
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
    private var pausedForInteraction = false
    private var pausedForBackground = false
    private var pausedByUser = false
    private var checkpoints = SilverCheckpointDataset()
    private val activeNodeJobs = mutableMapOf<String, String>()
    private val checkpointStoreMutex = Mutex()
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
        pausedForInteraction = false
        pausedForBackground = false
        pausedByUser = false
        activeNodeJobs.clear()
        datasetMutex.withLock {
            sync.reset()
            val dataset = if (activeSession == null) {
                SilverDataset()
            } else {
                localStore.load(activeSession, SilverData)
            }
            checkpoints = if (activeSession == null) {
                SilverCheckpointDataset()
            } else {
                checkpointStoreMutex.withLock {
                    withContext(Dispatchers.Default) {
                        val legacy = localStore.load(activeSession, SilverCheckpointData)
                        if (legacy.checkpoints.isEmpty()) {
                            legacy
                        } else {
                            legacy.copy(checkpoints = emptyList()).also {
                                localStore.save(activeSession, SilverCheckpointData, it)
                            }
                        }
                    }
                }
            }
            pausedByUser = checkpoints.refinementPaused
            state = SilverUiState(
                dataset = dataset,
                refinementPaused = pausedByUser,
                pendingSync = emptySet(),
            )
            publish()
        }
        if (activeSession != null) refresh()
    }

    fun refresh() {
        retryWorker?.cancel()
        retryWorker = null
        if (refinementIsPaused() || session() == null || worker?.isCompleted == false) return
        worker = scope.launch { refineAvailableSources() }
    }

    fun pauseForInteraction() {
        pausedForInteraction = true
        cancelWorker()
    }

    fun resumeAfterInteraction() {
        pausedForInteraction = false
        resumeWhenReady()
    }

    fun pauseForBackground() {
        pausedForBackground = true
        cancelWorker()
    }

    fun resumeAfterBackground() {
        pausedForBackground = false
        resumeWhenReady()
    }

    fun pauseRefinement() {
        if (pausedByUser) return
        pausedByUser = true
        checkpoints = checkpoints.copy(refinementPaused = true)
        state = state.copy(refinementPaused = true)
        cancelWorker()
        publish()
        val activeSession = session()
        val snapshot = checkpoints
        scope.launch { persistCheckpoints(activeSession, snapshot) }
        controlActiveNodeJobs("pause")
    }

    fun resumeRefinement() {
        if (!pausedByUser) return
        pausedByUser = false
        checkpoints = checkpoints.copy(refinementPaused = false)
        state = state.copy(refinementPaused = false)
        publish()
        val activeSession = session()
        val snapshot = checkpoints
        scope.launch { persistCheckpoints(activeSession, snapshot) }
        controlActiveNodeJobs("resume")
        resumeWhenReady()
    }

    suspend fun synchronize() {
        val activeSession = session() ?: return
        val connected = connectedNode() ?: return
        datasetMutex.withLock {
            val affected = state.pendingSync
            val syncStartedAt = SystemClock.elapsedRealtime()
            if (affected.isNotEmpty()) {
                Log.i(SILVER_LOG_TAG, "Silver sync started items=${affected.size}")
            }
            if (affected.isNotEmpty()) {
                state = state.copy(syncing = affected, syncFailed = state.syncFailed - affected)
                publish()
            }
            try {
                state.dataset.removedSourceIds.keys.forEach { sourceId ->
                    nodeApi.removeSilver(
                        connected.discovered.apiBaseUrl,
                        connected.trusted,
                        sourceId,
                        UUID.randomUUID().toString(),
                    )
                }
            } catch (error: Exception) {
                state = state.copy(syncing = emptySet(), syncFailed = state.syncFailed + affected)
                Log.w(SILVER_LOG_TAG, "Silver removal sync failed error=${syncErrorSummary(error)}")
                publish()
                scheduleRetry()
                return@withLock
            }
            sync.synchronize(activeSession, connected, state.dataset) { remote ->
                state = state.copy(
                    dataset = remote,
                    pendingSync = emptySet(),
                    syncing = emptySet(),
                    syncFailed = emptySet(),
                )
                publish()
            }
            state = if (sync.isBackedUp) {
                state.copy(pendingSync = emptySet(), syncing = emptySet(), syncFailed = emptySet())
            } else {
                state.copy(syncing = emptySet(), syncFailed = state.syncFailed + affected)
            }
            if (affected.isNotEmpty()) {
                val error = sync.lastError
                if (sync.isBackedUp) {
                    Log.i(
                        SILVER_LOG_TAG,
                        "Silver sync finished durationMs=${SystemClock.elapsedRealtime() - syncStartedAt}",
                    )
                } else {
                    Log.w(
                        SILVER_LOG_TAG,
                        "Silver sync failed durationMs=${SystemClock.elapsedRealtime() - syncStartedAt} " +
                            "error=${syncErrorSummary(error)}",
                    )
                }
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
                    processing = state.processing - affectedSourceIds,
                    progress = state.progress - affectedSourceIds,
                    pendingSync = state.pendingSync + affectedSourceIds,
                    syncFailed = state.syncFailed - affectedSourceIds,
                )
                checkpoints = checkpoints.copy(checkpoints = checkpoints.checkpoints.filterNot {
                    it.bronzeSourceId in affectedSourceIds
                })
                sync.changed()
                publish()
                sync.persist(activeSession, state.dataset)
                persistCheckpoints(activeSession)
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
        try {
            while (!refinementIsPaused()) {
                val connected = connectedNode() ?: run {
                    state = state.copy(processing = emptyMap(), progress = emptyMap())
                    publish()
                    return
                }
                val desiredModel = connected.aiModel ?: run {
                    state = state.copy(processing = emptyMap(), progress = emptyMap())
                    publish()
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
                    state = state.copy(processing = emptyMap(), progress = emptyMap())
                    publish()
                    return
                }
                state = state.copy(
                    processing = candidateSources.associate { it.id to SilverProcessingState.QUEUED },
                    progress = candidateSources.associate { source ->
                        source.id to SilverBatchProgress(0, 1)
                    },
                )
                publish()
                val source = candidateSources.first()
                attempted += source.id
                state = state.copy(
                    processing = state.processing + (source.id to SilverProcessingState.PROCESSING),
                )
                publish()
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
                activeNodeJobs[source.id] = job.id
                job = observeNodeJob(connected, source.id, job)
                if (job.state == "cancelled") {
                    activeNodeJobs.remove(source.id)
                    state = state.copy(
                        processing = state.processing - source.id,
                        progress = state.progress - source.id,
                    )
                    publish()
                    continue
                }
                synchronize()
                activeNodeJobs.remove(source.id)
                state = state.copy(
                    processing = state.processing - source.id,
                    progress = state.progress - source.id,
                )
                publish()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(
                SILVER_LOG_TAG,
                "refinement failed error=${syncErrorSummary(error)}; retryMs=$RETRY_DELAY_MILLIS",
            )
            state = state.copy(
                processing = state.processing.mapValues { (_, value) ->
                    if (value == SilverProcessingState.PROCESSING) SilverProcessingState.QUEUED else value
                },
            )
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
                processing = state.processing + (
                    sourceId to if (job.state == "running") SilverProcessingState.PROCESSING else SilverProcessingState.QUEUED
                ),
                progress = state.progress + (sourceId to SilverBatchProgress(job.completedBatches, job.totalBatches)),
            )
            publish()
            when (job.state) {
                "completed", "cancelled" -> return job
                "failed" -> throw SourceApiException(
                    job.errorCode ?: "silver_refinement_failed",
                    "The Node could not refine knowledge from this source.",
                )
                "paused" -> {
                    if (pausedByUser) {
                        delay(JOB_POLL_MILLIS)
                    } else {
                        job = nodeApi.controlSilverRefinementJob(
                            connected.discovered.apiBaseUrl,
                            connected.trusted,
                            job.id,
                            "resume",
                        )
                    }
                }
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

    private fun controlActiveNodeJobs(action: String) {
        val connected = connectedNode() ?: return
        activeNodeJobs.values.toSet().forEach { jobId ->
            scope.launch {
                runCatching {
                    nodeApi.controlSilverRefinementJob(
                        connected.discovered.apiBaseUrl,
                        connected.trusted,
                        jobId,
                        action,
                    )
                }.onFailure { error ->
                    Log.w(SILVER_LOG_TAG, "Node refinement $action failed error=${syncErrorSummary(error as? Exception)}")
                }
            }
        }
    }

    private fun nextModifiedAt(): Long = maxOf(clock().coerceAtLeast(1), state.dataset.modifiedAtMillis + 1)

    private fun refinementIsPaused(): Boolean = pausedForInteraction || pausedForBackground || pausedByUser

    private fun cancelWorker() {
        worker?.cancel()
        retryWorker?.cancel()
        retryWorker = null
        val current = state.processing.filterValues { it == SilverProcessingState.PROCESSING }.keys
        if (current.isEmpty()) return
        state = state.copy(
            processing = state.processing.mapValues { (id, processing) ->
                if (id in current) SilverProcessingState.QUEUED else processing
            },
        )
        publish()
    }

    private fun resumeWhenReady() {
        if (refinementIsPaused()) return
        val cancellingWorker = worker
        if (cancellingWorker != null && !cancellingWorker.isCompleted) {
            cancellingWorker.invokeOnCompletion { scope.launch { refresh() } }
        } else {
            refresh()
        }
    }

    private suspend fun persistCheckpoints(
        activeSession: VaultSession? = session(),
        snapshot: SilverCheckpointDataset = checkpoints,
    ) {
        activeSession ?: return
        checkpointStoreMutex.withLock {
            withContext(Dispatchers.Default) {
                localStore.save(activeSession, SilverCheckpointData, snapshot)
            }
        }
    }

    private fun scheduleRetry() {
        if (refinementIsPaused() || retryWorker?.isActive == true) return
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

internal fun firstUnfinishedBatch(totalBatches: Int, completedBatches: Set<Int>): Int? {
    require(totalBatches > 0)
    return (0 until totalBatches).firstOrNull { it !in completedBatches }
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

internal fun isReusableCheckpoint(
    checkpoint: SilverRefinementCheckpoint,
    source: BronzeTextSource,
    chunks: List<String>,
    desiredModel: AiModelMetadata,
    processorVersion: String,
): Boolean = checkpoint.bronzeSourceId == source.id &&
    checkpoint.bronzeContentSha256 == source.contentSha256 &&
    checkpoint.processorVersion == processorVersion &&
    checkpoint.totalBatches == chunks.size &&
    checkpoint.completedBatches.first().result.let { result ->
        result.modelId == desiredModel.modelId && result.parameterCount == desiredModel.parameterCount
    } &&
    checkpoint.completedBatches.all { batch ->
        batch.batchIndex in chunks.indices && batch.batchContentSha256 == sha256Hex(chunks[batch.batchIndex])
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
private const val NODE_EXTRACTION_PROCESSOR_VERSION = "1"
