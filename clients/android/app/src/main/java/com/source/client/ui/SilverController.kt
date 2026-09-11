package com.source.client.ui

import android.os.SystemClock
import android.util.Log
import com.source.client.ai.AiRuntimeRouter
import com.source.client.ai.LOCAL_AI_MODEL
import com.source.client.knowledge.BronzeTextSource
import com.source.client.knowledge.ExtractedSilver
import com.source.client.knowledge.combineSilverBatches
import com.source.client.knowledge.deterministicTextChunks
import com.source.client.knowledge.extractSilverBatch
import com.source.client.knowledge.sha256Hex
import com.source.client.model.AiModelMetadata
import com.source.client.model.ConnectedNode
import com.source.client.protocol.SourceApiException
import com.source.client.security.VaultSession
import com.source.client.storage.SilverBatchCheckpoint
import com.source.client.storage.SilverCheckpointData
import com.source.client.storage.SilverCheckpointDataset
import com.source.client.storage.SilverData
import com.source.client.storage.SilverDataset
import com.source.client.storage.SilverRefinementCheckpoint
import com.source.client.storage.SilverResult
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
    private val aiRuntime: AiRuntimeRouter,
    private val scope: CoroutineScope,
    private val localStore: SourceDataStore,
    nodeApi: com.source.client.protocol.SourceNodeApi,
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
    private val checkpointStoreMutex = Mutex()

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
                    localStore.load(activeSession, SilverCheckpointData)
                }
            }
        }
        pausedByUser = checkpoints.refinementPaused
        state = SilverUiState(
            dataset = dataset,
            refinementPaused = pausedByUser,
            pendingSync = if (activeSession == null) emptySet() else buildSet {
                dataset.results.mapTo(this, SilverResult::bronzeSourceId)
                addAll(dataset.removedSourceIds.keys)
            },
        )
        publish()
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
        resumeWhenReady()
    }

    suspend fun synchronize() {
        val activeSession = session() ?: return
        val connected = connectedNode() ?: return
        val affected = state.pendingSync
        val syncStartedAt = SystemClock.elapsedRealtime()
        if (affected.isNotEmpty()) {
            Log.i(SILVER_LOG_TAG, "Silver sync started items=${affected.size}")
        }
        if (affected.isNotEmpty()) {
            state = state.copy(syncing = affected, syncFailed = state.syncFailed - affected)
            publish()
        }
        sync.synchronize(activeSession, connected, state.dataset) { remote ->
            state = state.copy(dataset = remote, pendingSync = emptySet(), syncing = emptySet(), syncFailed = emptySet())
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
                Log.i(SILVER_LOG_TAG, "Silver sync finished durationMs=${SystemClock.elapsedRealtime() - syncStartedAt}")
            } else {
                Log.w(
                    SILVER_LOG_TAG,
                    "Silver sync failed durationMs=${SystemClock.elapsedRealtime() - syncStartedAt} " +
                        "error=${syncErrorSummary(error)}",
                )
            }
        }
        publish()
        refresh()
    }

    fun removeSource(sourceId: String) {
        val activeSession = session() ?: return
        if (state.dataset.results.none { it.bronzeSourceId == sourceId } && sourceId in state.dataset.removedSourceIds) return
        val now = nextModifiedAt()
        state = state.copy(
            dataset = state.dataset.copy(
                results = state.dataset.results.filterNot { it.bronzeSourceId == sourceId },
                modifiedAtMillis = now,
                removedSourceIds = state.dataset.removedSourceIds + (sourceId to now),
            ),
            processing = state.processing - sourceId,
            progress = state.progress - sourceId,
            pendingSync = state.pendingSync + sourceId,
            syncFailed = state.syncFailed - sourceId,
        )
        checkpoints = checkpoints.copy(checkpoints = checkpoints.checkpoints.filterNot {
            it.bronzeSourceId == sourceId
        })
        sync.changed()
        publish()
        scope.launch {
            sync.persist(activeSession, state.dataset)
            persistCheckpoints(activeSession)
            synchronize()
        }
    }

    private suspend fun refineAvailableSources() {
        val attempted = mutableSetOf<String>()
        try {
            while (!refinementIsPaused()) {
                val allSources = bronzeSources()
                removeOrphanedData(allSources.mapTo(mutableSetOf(), BronzeTextSource::id))
                val sources = allSources.filter { it.text.isNotBlank() }
                val desiredModel = connectedNode()?.aiModel ?: LOCAL_AI_MODEL
                val candidateSources = sources.filter { source ->
                    source.id !in attempted && needsSilverRefinement(
                        state.dataset.results.firstOrNull { it.bronzeSourceId == source.id },
                        source,
                        desiredModel,
                        PROCESSOR_VERSION,
                    )
                }
                val candidates = candidateSources.map { source ->
                    val chunks = deterministicTextChunks(source.text)
                    val stored = checkpoints.checkpoints.firstOrNull { it.bronzeSourceId == source.id }
                    SilverCandidate(
                        source,
                        chunks,
                        stored?.takeIf { isReusableCheckpoint(it, source, chunks, desiredModel, PROCESSOR_VERSION) },
                    )
                }
                val reusableIds = candidates.mapNotNullTo(mutableSetOf()) { candidate ->
                    candidate.checkpoint?.bronzeSourceId
                }
                if (checkpoints.checkpoints.any { it.bronzeSourceId !in reusableIds }) {
                    checkpoints = checkpoints.copy(checkpoints = checkpoints.checkpoints.filter {
                        it.bronzeSourceId in reusableIds
                    })
                    persistCheckpoints()
                }
                if (candidates.isEmpty()) {
                    state = state.copy(processing = emptyMap(), progress = emptyMap())
                    publish()
                    return
                }
                state = state.copy(
                    processing = candidates.associate { it.source.id to SilverProcessingState.QUEUED },
                    progress = candidates.associate { candidate ->
                        candidate.source.id to SilverBatchProgress(
                            candidate.checkpoint?.completedBatches?.size ?: 0,
                            candidate.chunks.size,
                        )
                    },
                )
                publish()
                val candidate = candidates.first()
                val source = candidate.source
                attempted += source.id
                state = state.copy(
                    processing = state.processing + (source.id to SilverProcessingState.PROCESSING),
                )
                publish()
                Log.i(
                    SILVER_LOG_TAG,
                    "refinement candidate started sourceType=${source.sourceType} " +
                        "bytes=${source.text.toByteArray(Charsets.UTF_8).size} " +
                        "completedBatches=${candidate.checkpoint?.completedBatches?.size ?: 0}/${candidate.chunks.size}",
                )
                val extracted = refineSource(candidate)
                val currentSource = bronzeSources().firstOrNull { it.id == source.id }
                if (currentSource?.contentSha256 != source.contentSha256) {
                    attempted -= source.id
                    removeCheckpoint(source.id)
                    persistCheckpoints()
                    state = state.copy(processing = state.processing + (source.id to SilverProcessingState.QUEUED))
                    publish()
                    continue
                }
                val result = SilverResult(
                    bronzeSourceId = source.id,
                    bronzeContentSha256 = source.contentSha256,
                    entities = extracted.entities,
                    claims = extracted.claims,
                    modelId = extracted.model.modelId,
                    parameterCount = extracted.model.parameterCount,
                    processorVersion = PROCESSOR_VERSION,
                    processedAtMillis = clock().coerceAtLeast(1),
                )
                val existing = state.dataset.results.firstOrNull { it.bronzeSourceId == source.id }
                if (shouldReplaceSilver(existing, result)) commit(result)
                removeCheckpoint(source.id)
                persistCheckpoints()
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

    private suspend fun refineSource(candidate: SilverCandidate): ExtractedSilver {
        val source = candidate.source
        var checkpoint = candidate.checkpoint
        while (true) {
            val completedByIndex = checkpoint?.completedBatches.orEmpty().associateBy(SilverBatchCheckpoint::batchIndex)
            val batchIndex = firstUnfinishedBatch(candidate.chunks.size, completedByIndex.keys)
                ?: return combineSilverBatches(candidate.chunks.indices.map { index ->
                    val result = checkNotNull(completedByIndex[index]).result
                    ExtractedSilver(
                        result.entities,
                        result.claims,
                        AiModelMetadata(result.modelId, result.parameterCount),
                    )
                })
            val extracted = extractSilverBatch(
                aiRuntime,
                source,
                candidate.chunks[batchIndex],
                batchIndex,
                candidate.chunks.size,
            )
            val establishedModel = checkpoint?.completedBatches?.firstOrNull()?.result?.let {
                AiModelMetadata(it.modelId, it.parameterCount)
            }
            if (establishedModel != null && establishedModel != extracted.model) {
                Log.i(SILVER_LOG_TAG, "refinement model changed; restarting source checkpoints")
                checkpoint = null
                removeCheckpoint(source.id)
                persistCheckpoints()
                updateProgress(source.id, 0, candidate.chunks.size)
                continue
            }
            val batchResult = SilverResult(
                bronzeSourceId = source.id,
                bronzeContentSha256 = source.contentSha256,
                entities = extracted.entities,
                claims = extracted.claims,
                modelId = extracted.model.modelId,
                parameterCount = extracted.model.parameterCount,
                processorVersion = PROCESSOR_VERSION,
                processedAtMillis = clock().coerceAtLeast(1),
            )
            val completed = checkpoint?.completedBatches.orEmpty() + SilverBatchCheckpoint(
                batchIndex,
                sha256Hex(candidate.chunks[batchIndex]),
                batchResult,
            )
            checkpoint = SilverRefinementCheckpoint(
                bronzeSourceId = source.id,
                bronzeContentSha256 = source.contentSha256,
                processorVersion = PROCESSOR_VERSION,
                totalBatches = candidate.chunks.size,
                completedBatches = completed,
            )
            putCheckpoint(checkpoint)
            persistCheckpoints()
            updateProgress(source.id, completed.size, candidate.chunks.size)
        }
    }

    private suspend fun commit(result: SilverResult) {
        val activeSession = session() ?: return
        val storeStartedAt = SystemClock.elapsedRealtime()
        val now = nextModifiedAt()
        state = state.copy(
            dataset = state.dataset.copy(
                results = state.dataset.results.filterNot { it.bronzeSourceId == result.bronzeSourceId } + result,
                modifiedAtMillis = now,
                removedSourceIds = state.dataset.removedSourceIds - result.bronzeSourceId,
            ),
            pendingSync = state.pendingSync + result.bronzeSourceId,
            syncFailed = state.syncFailed - result.bronzeSourceId,
        )
        sync.changed()
        sync.persist(activeSession, state.dataset)
        Log.i(
            SILVER_LOG_TAG,
            "Silver stored durationMs=${SystemClock.elapsedRealtime() - storeStartedAt} " +
                "entities=${result.entities.size} claims=${result.claims.size}",
        )
        publish()
        synchronize()
    }

    private suspend fun removeOrphanedData(activeSourceIds: Set<String>) {
        val removed = state.dataset.results.map(SilverResult::bronzeSourceId).filterNot(activeSourceIds::contains).toSet()
        val orphanedCheckpoints = checkpoints.checkpoints.map(SilverRefinementCheckpoint::bronzeSourceId)
            .filterNot(activeSourceIds::contains).toSet()
        if (removed.isEmpty() && orphanedCheckpoints.isEmpty()) return
        val activeSession = session() ?: return
        if (orphanedCheckpoints.isNotEmpty()) {
            checkpoints = checkpoints.copy(checkpoints = checkpoints.checkpoints.filter {
                it.bronzeSourceId in activeSourceIds
            })
            persistCheckpoints(activeSession)
        }
        if (removed.isEmpty()) return
        state = state.copy(
            dataset = state.dataset.copy(
                results = state.dataset.results.filter { it.bronzeSourceId in activeSourceIds },
                modifiedAtMillis = nextModifiedAt(),
                removedSourceIds = state.dataset.removedSourceIds + removed.associateWith { nextModifiedAt() },
            ),
            processing = state.processing - removed,
            progress = state.progress - removed,
            pendingSync = state.pendingSync + removed,
        )
        sync.changed()
        sync.persist(activeSession, state.dataset)
        publish()
    }

    private fun nextModifiedAt(): Long = maxOf(clock().coerceAtLeast(1), state.dataset.modifiedAtMillis + 1)

    private fun publish() = onStateChanged(state)

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

    private fun updateProgress(sourceId: String, completedBatches: Int, totalBatches: Int) {
        state = state.copy(
            progress = state.progress + (sourceId to SilverBatchProgress(completedBatches, totalBatches)),
        )
        publish()
    }

    private fun putCheckpoint(checkpoint: SilverRefinementCheckpoint) {
        checkpoints = checkpoints.copy(
            checkpoints = checkpoints.checkpoints.filterNot {
                it.bronzeSourceId == checkpoint.bronzeSourceId
            } + checkpoint,
        )
    }

    private fun removeCheckpoint(sourceId: String) {
        checkpoints = checkpoints.copy(
            checkpoints = checkpoints.checkpoints.filterNot { it.bronzeSourceId == sourceId },
        )
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
        const val PROCESSOR_VERSION = 2
        private const val RETRY_DELAY_MILLIS = 30_000L
        private const val SILVER_LOG_TAG = "SourceSilver"
    }
}

private data class SilverCandidate(
    val source: BronzeTextSource,
    val chunks: List<String>,
    val checkpoint: SilverRefinementCheckpoint?,
)

internal fun firstUnfinishedBatch(totalBatches: Int, completedBatches: Set<Int>): Int? {
    require(totalBatches > 0)
    return (0 until totalBatches).firstOrNull { it !in completedBatches }
}

internal fun isReusableCheckpoint(
    checkpoint: SilverRefinementCheckpoint,
    source: BronzeTextSource,
    chunks: List<String>,
    desiredModel: AiModelMetadata,
    processorVersion: Int,
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
    existing: SilverResult?,
    source: BronzeTextSource,
    desiredModel: AiModelMetadata,
    processorVersion: Int,
): Boolean = when {
    existing == null -> true
    existing.bronzeContentSha256 != source.contentSha256 -> true
    existing.processorVersion < processorVersion -> true
    else -> existing.processorVersion == processorVersion && existing.parameterCount < desiredModel.parameterCount
}

internal fun shouldReplaceSilver(existing: SilverResult?, candidate: SilverResult): Boolean = when {
    existing == null -> true
    existing.bronzeContentSha256 != candidate.bronzeContentSha256 -> true
    candidate.processorVersion > existing.processorVersion -> true
    candidate.processorVersion < existing.processorVersion -> false
    else -> candidate.parameterCount > existing.parameterCount
}
