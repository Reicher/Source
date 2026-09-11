package com.source.client.ui

import com.source.client.ai.AiRuntimeRouter
import com.source.client.ai.LOCAL_AI_MODEL
import com.source.client.knowledge.BronzeTextSource
import com.source.client.knowledge.extractSilver
import com.source.client.model.AiModelMetadata
import com.source.client.model.ConnectedNode
import com.source.client.security.VaultSession
import com.source.client.storage.SilverData
import com.source.client.storage.SilverDataset
import com.source.client.storage.SilverResult
import com.source.client.storage.SourceDataStore
import com.source.client.storage.SourceDataSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class SilverProcessingState { QUEUED, PROCESSING }

data class SilverUiState(
    val dataset: SilverDataset = SilverDataset(),
    val processing: Map<String, SilverProcessingState> = emptyMap(),
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

    var state = SilverUiState()
        private set

    suspend fun reset(activeSession: VaultSession? = null) {
        worker?.cancel()
        retryWorker?.cancel()
        worker = null
        retryWorker = null
        pausedForInteraction = false
        sync.reset()
        val dataset = if (activeSession == null) {
            SilverDataset()
        } else {
            localStore.load(activeSession, SilverData)
        }
        state = SilverUiState(
            dataset = dataset,
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
        if (pausedForInteraction || session() == null || worker?.isActive == true) return
        worker = scope.launch { refineAvailableSources() }
    }

    fun pauseForInteraction() {
        pausedForInteraction = true
        worker?.cancel()
        retryWorker?.cancel()
        retryWorker = null
        val current = state.processing.filterValues { it == SilverProcessingState.PROCESSING }.keys
        if (current.isNotEmpty()) {
            state = state.copy(
                processing = state.processing.mapValues { (id, processing) ->
                    if (id in current && processing == SilverProcessingState.PROCESSING) SilverProcessingState.QUEUED else processing
                },
            )
            publish()
        }
    }

    fun resumeAfterInteraction() {
        pausedForInteraction = false
        val cancellingWorker = worker
        if (cancellingWorker?.isActive == true) {
            cancellingWorker.invokeOnCompletion {
                scope.launch { refresh() }
            }
        } else {
            refresh()
        }
    }

    suspend fun synchronize() {
        val activeSession = session() ?: return
        val connected = connectedNode() ?: return
        val affected = state.pendingSync
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
            pendingSync = state.pendingSync + sourceId,
            syncFailed = state.syncFailed - sourceId,
        )
        sync.changed()
        publish()
        scope.launch {
            sync.persist(activeSession, state.dataset)
            synchronize()
        }
    }

    private suspend fun refineAvailableSources() {
        val attempted = mutableSetOf<String>()
        try {
            while (!pausedForInteraction) {
                val allSources = bronzeSources()
                removeOrphanedResults(allSources.mapTo(mutableSetOf(), BronzeTextSource::id))
                val sources = allSources.filter { it.text.isNotBlank() }
                val desiredModel = connectedNode()?.aiModel ?: LOCAL_AI_MODEL
                val candidates = sources.filter { source ->
                    source.id !in attempted && needsSilverRefinement(
                        state.dataset.results.firstOrNull { it.bronzeSourceId == source.id },
                        source,
                        desiredModel,
                        PROCESSOR_VERSION,
                    )
                }
                if (candidates.isEmpty()) {
                    state = state.copy(processing = emptyMap())
                    publish()
                    return
                }
                state = state.copy(
                    processing = candidates.associate { it.id to SilverProcessingState.QUEUED },
                )
                publish()
                val source = candidates.first()
                attempted += source.id
                state = state.copy(
                    processing = state.processing + (source.id to SilverProcessingState.PROCESSING),
                )
                publish()
                val extracted = extractSilver(aiRuntime, source)
                val currentSource = bronzeSources().firstOrNull { it.id == source.id }
                if (currentSource?.contentSha256 != source.contentSha256) {
                    attempted -= source.id
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
                state = state.copy(processing = state.processing - source.id)
                publish()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
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

    private suspend fun commit(result: SilverResult) {
        val activeSession = session() ?: return
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
        publish()
        synchronize()
    }

    private suspend fun removeOrphanedResults(activeSourceIds: Set<String>) {
        val removed = state.dataset.results.map(SilverResult::bronzeSourceId).filterNot(activeSourceIds::contains).toSet()
        if (removed.isEmpty()) return
        val activeSession = session() ?: return
        state = state.copy(
            dataset = state.dataset.copy(
                results = state.dataset.results.filter { it.bronzeSourceId in activeSourceIds },
                modifiedAtMillis = nextModifiedAt(),
                removedSourceIds = state.dataset.removedSourceIds + removed.associateWith { nextModifiedAt() },
            ),
            processing = state.processing - removed,
            pendingSync = state.pendingSync + removed,
        )
        sync.changed()
        sync.persist(activeSession, state.dataset)
        publish()
    }

    private fun nextModifiedAt(): Long = maxOf(clock().coerceAtLeast(1), state.dataset.modifiedAtMillis + 1)

    private fun publish() = onStateChanged(state)

    private fun scheduleRetry() {
        if (pausedForInteraction || retryWorker?.isActive == true) return
        retryWorker = scope.launch {
            delay(RETRY_DELAY_MILLIS)
            retryWorker = null
            refresh()
        }
    }

    companion object {
        const val PROCESSOR_VERSION = 1
        private const val RETRY_DELAY_MILLIS = 30_000L
    }
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
