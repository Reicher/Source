package com.source.client.storage

import org.json.JSONArray
import org.json.JSONObject

data class SilverBatchResult(
    val evidence: List<SilverEvidence>,
    val observations: List<SilverObservation>,
    val modelId: String,
    val parameterCount: Long,
) {
    init {
        require(modelId.isNotBlank() && modelId.length <= 200) { "Invalid Silver model identifier" }
        require(parameterCount > 0) { "Invalid Silver model parameter count" }
        val evidenceIds = evidence.mapTo(mutableSetOf(), SilverEvidence::id)
        require(evidence.distinctBy(SilverEvidence::id).size == evidence.size) { "Duplicate checkpoint Evidence" }
        require(observations.distinctBy(SilverObservation::id).size == observations.size) {
            "Duplicate checkpoint Observations"
        }
        require(observations.all { evidenceIds.containsAll(it.evidenceIds) }) {
            "Checkpoint Observations must reference checkpoint Evidence"
        }
        require(observations.flatMapTo(mutableSetOf(), SilverObservation::evidenceIds) == evidenceIds) {
            "Checkpoint Evidence must support an Observation"
        }
    }
}

/** One completed deterministic text batch, kept locally until its observations are committed. */
data class SilverBatchCheckpoint(
    val batchIndex: Int,
    val batchContentSha256: String,
    val result: SilverBatchResult,
) {
    init {
        require(batchIndex >= 0) { "Invalid Silver batch index" }
        require(SILVER_CHECKPOINT_SHA256_PATTERN.matches(batchContentSha256)) {
            "Invalid Silver batch content identity"
        }
    }
}

data class SilverRefinementCheckpoint(
    val bronzeSourceId: String,
    val bronzeContentSha256: String,
    val processorVersion: String,
    val totalBatches: Int,
    val completedBatches: List<SilverBatchCheckpoint>,
) {
    init {
        require(bronzeSourceId.isNotBlank() && bronzeSourceId.length <= 240) { "Invalid Bronze source reference" }
        require(SILVER_CHECKPOINT_SHA256_PATTERN.matches(bronzeContentSha256)) {
            "Invalid Bronze content identity"
        }
        require(processorVersion.isNotBlank() && processorVersion.length <= 200) { "Invalid Silver processor version" }
        require(totalBatches > 0) { "A Silver checkpoint must have batches" }
        require(completedBatches.isNotEmpty()) { "A Silver checkpoint must contain completed work" }
        require(completedBatches.distinctBy(SilverBatchCheckpoint::batchIndex).size == completedBatches.size) {
            "Duplicate completed Silver batch"
        }
        require(completedBatches.all { batch ->
            batch.batchIndex in 0 until totalBatches &&
                batch.result.evidence.all {
                    it.bronzeSourceId == bronzeSourceId && it.bronzeContentSha256 == bronzeContentSha256
                } &&
                batch.result.observations.all {
                    it.producer.processorVersion == processorVersion &&
                        it.producer.modelId == batch.result.modelId
                }
        }) { "Silver batch metadata does not match its checkpoint" }
        val first = completedBatches.first().result
        require(completedBatches.all { batch ->
            batch.result.modelId == first.modelId && batch.result.parameterCount == first.parameterCount
        }) { "A Silver checkpoint cannot mix AI models" }
    }
}

data class SilverCheckpointDataset(
    val checkpoints: List<SilverRefinementCheckpoint> = emptyList(),
    val refinementPaused: Boolean = false,
) {
    init {
        require(checkpoints.distinctBy(SilverRefinementCheckpoint::bronzeSourceId).size == checkpoints.size) {
            "Only one Silver checkpoint may exist for a Bronze source"
        }
    }
}

/** Encrypted, device-local working state. Completed records use [SilverData] and its sync path. */
object SilverCheckpointData : SourceData<SilverCheckpointDataset> {
    override val descriptor = SourceDataDescriptor(
        id = "silver-checkpoints",
        remoteAppId = "source-silver-checkpoints",
        snapshotFormat = "source-silver-checkpoints",
        formatVersion = 1,
    )
    override val emptyValue = SilverCheckpointDataset()

    override fun encode(value: SilverCheckpointDataset): ByteArray = JSONObject().apply {
        put("version", descriptor.formatVersion)
        put("refinementPaused", value.refinementPaused)
        put("checkpoints", JSONArray().apply {
            value.checkpoints.sortedBy(SilverRefinementCheckpoint::bronzeSourceId).forEach { checkpoint ->
                put(JSONObject().apply {
                    put("bronzeSourceId", checkpoint.bronzeSourceId)
                    put("bronzeContentSha256", checkpoint.bronzeContentSha256)
                    put("processorVersion", checkpoint.processorVersion)
                    put("totalBatches", checkpoint.totalBatches)
                    put("completedBatches", JSONArray().apply {
                        checkpoint.completedBatches.sortedBy(SilverBatchCheckpoint::batchIndex).forEach { batch ->
                            put(JSONObject().apply {
                                put("batchIndex", batch.batchIndex)
                                put("batchContentSha256", batch.batchContentSha256)
                                put("result", encodeResult(batch.result))
                            })
                        }
                    })
                })
            }
        })
    }.toString().toByteArray(Charsets.UTF_8)

    override fun decode(value: ByteArray): SilverCheckpointDataset {
        val root = JSONObject(value.toString(Charsets.UTF_8))
        val version = root.getInt("version")
        require(version == descriptor.formatVersion) { "Unsupported Silver checkpoint version" }
        val checkpoints = root.getJSONArray("checkpoints")
        return SilverCheckpointDataset(
            checkpoints = List(checkpoints.length()) { checkpointIndex ->
                val checkpoint = checkpoints.getJSONObject(checkpointIndex)
                val batches = checkpoint.getJSONArray("completedBatches")
                SilverRefinementCheckpoint(
                    bronzeSourceId = checkpoint.getString("bronzeSourceId"),
                    bronzeContentSha256 = checkpoint.getString("bronzeContentSha256"),
                    processorVersion = checkpoint.getString("processorVersion"),
                    totalBatches = checkpoint.getInt("totalBatches"),
                    completedBatches = List(batches.length()) { batchIndex ->
                        val batch = batches.getJSONObject(batchIndex)
                        SilverBatchCheckpoint(
                            batchIndex = batch.getInt("batchIndex"),
                            batchContentSha256 = batch.getString("batchContentSha256"),
                            result = decodeResult(batch.getJSONObject("result")),
                        )
                    },
                )
            },
            refinementPaused = root.getBoolean("refinementPaused"),
        )
    }

    override fun version(value: SilverCheckpointDataset): SourceDataVersion = SourceDataVersion(
        modifiedAtMillis = value.checkpoints.flatMap(SilverRefinementCheckpoint::completedBatches)
            .flatMap { it.result.observations }.maxOfOrNull(SilverObservation::createdAtMillis) ?: 0,
        contentIdentity = encode(value).toString(Charsets.UTF_8),
    )

    private fun encodeResult(result: SilverBatchResult) = JSONObject().apply {
        put("modelId", result.modelId)
        put("parameterCount", result.parameterCount)
        put("evidence", JSONArray().apply { result.evidence.forEach { put(SilverData.encodeEvidence(it)) } })
        put("observations", JSONArray().apply {
            result.observations.forEach { put(SilverData.encodeObservation(it)) }
        })
    }

    private fun decodeResult(value: JSONObject): SilverBatchResult {
        val evidence = value.getJSONArray("evidence")
        val observations = value.getJSONArray("observations")
        return SilverBatchResult(
            evidence = List(evidence.length()) { SilverData.decodeEvidence(evidence.getJSONObject(it)) },
            observations = List(observations.length()) {
                SilverData.decodeObservation(observations.getJSONObject(it))
            },
            modelId = value.getString("modelId"),
            parameterCount = value.getLong("parameterCount"),
        )
    }
}

private val SILVER_CHECKPOINT_SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
