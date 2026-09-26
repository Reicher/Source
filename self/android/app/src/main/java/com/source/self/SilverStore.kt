package com.source.self

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

data class SilverSource(
    val bronzeSourceId: String,
    val bronzeContentSha256: String,
    val title: String,
    val mime: String,
    val evidenceIds: List<String>,
    val observationIds: List<String>,
    val entityIds: List<String>,
    val claimIds: List<String>,
    val stale: Boolean = false,
    val modelId: String? = null,
    val modelRevision: String? = null,
)

data class SilverEvidence(
    val id: String,
    val bronzeSourceId: String,
    val bronzeContentSha256: String,
    val selector: JSONObject?,
    val excerpt: String,
)

data class SilverObservation(
    val id: String,
    val kind: String,
    val payload: Any,
    val evidenceIds: List<String>,
    val processorId: String,
    val processorVersion: String,
    val confidence: Double? = null,
    val modelId: String? = null,
    val modelRevision: String? = null,
)

data class SilverEntity(val id: String)

data class SilverClaim(
    val id: String,
    val subjectEntityId: String,
    val predicate: String,
    val value: Any?,
    val objectEntityId: String?,
    val supportingObservationIds: List<String>,
    val state: String,
    val processorId: String? = null,
    val processorVersion: String? = null,
    val modelId: String? = null,
    val modelRevision: String? = null,
)

data class SilverClaimGroup(
    val predicate: String,
    val value: String,
    val claims: List<SilverClaim>,
    val confidence: Double?,
    val linkedEntityId: String?,
)

data class SilverProcessing(
    val bronzeSourceId: String,
    val state: String,
    val completedBatches: Int,
    val totalBatches: Int,
) {
    companion object {
        fun list(values: JSONArray): List<SilverProcessing> = values.objects().map { processing -> SilverProcessing(
            processing.getString("bronze_source_id"), processing.getString("state"),
            processing.getInt("completed_batches"), processing.getInt("total_batches"),
        ) }
    }
}

data class SourceJob(
    val id: String,
    val kind: String,
    val title: String,
    val direction: String?,
    val state: String,
    val queuedAt: Long,
    val completedAt: Long?,
) {
    companion object {
        fun fromJson(value: JSONObject) = SourceJob(
            value.getString("id"), value.getString("kind"), value.getString("title"),
            value.optString("direction").takeIf(String::isNotEmpty), value.getString("state"),
            value.getLong("queued_at"), value.optLong("completed_at").takeIf { value.has("completed_at") },
        )
    }
}

data class SourceJobs(
    val revision: Long,
    val queued: List<SourceJob>,
    val completed: List<SourceJob>,
    val queuedCount: Int = queued.size,
    val completedCount: Int = completed.size,
) {
    companion object {
        val EMPTY = SourceJobs(0, emptyList(), emptyList())
        fun fromJson(value: JSONObject) = SourceJobs(
            value.optLong("revision"), value.array("queued").objects().map(SourceJob::fromJson),
            value.array("completed").objects().map(SourceJob::fromJson),
            value.optInt("queued_count", value.array("queued").length()),
            value.optInt("completed_count", value.array("completed").length()),
        )
    }
}

private fun SourceJob.persistenceJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("kind", kind)
    .put("title", title)
    .put("state", state)
    .put("queued_at", queuedAt)
    .also { value -> direction?.let { value.put("direction", it) } }
    .also { value -> completedAt?.let { value.put("completed_at", it) } }

private fun SourceJobs.persistenceJson(): JSONObject = JSONObject()
    .put("revision", revision)
    .put("queued_count", queuedCount)
    .put("completed_count", completedCount)
    .put("queued", JSONArray().also { values -> queued.forEach { values.put(it.persistenceJson()) } })
    .put("completed", JSONArray().also { values -> completed.forEach { values.put(it.persistenceJson()) } })

private fun SilverProcessing.persistenceJson(): JSONObject = JSONObject()
    .put("bronze_source_id", bronzeSourceId)
    .put("state", state)
    .put("completed_batches", completedBatches)
    .put("total_batches", totalBatches)

internal fun SourceStatus.persistenceJson(): JSONObject {
    val persistedJobs = requireNotNull(jobs)
    val persistedProcessing = requireNotNull(processing)
    return JSONObject()
        .put("silver_revision", requireNotNull(silverRevision))
        .put("jobs_revision", requireNotNull(jobsRevision))
        .put("jobs", persistedJobs.persistenceJson())
        .put("processing", JSONArray().also { values ->
            persistedProcessing.forEach { values.put(it.persistenceJson()) }
        })
}

internal fun sourceStatusFromPersistenceJson(value: JSONObject): SourceStatus = SourceStatus(
    personId = "",
    silverRevision = value.getLong("silver_revision"),
    jobsRevision = value.getLong("jobs_revision"),
    jobs = SourceJobs.fromJson(value.getJSONObject("jobs")),
    processing = SilverProcessing.list(value.getJSONArray("processing")),
)

data class SilverKnowledge(
    val source: SilverSource?,
    val evidence: List<SilverEvidence>,
    val observations: List<SilverObservation>,
    val entities: List<SilverEntity>,
    val claims: List<SilverClaim>,
    val processing: SilverProcessing?,
) {
    val itemCount: Int get() = observations.size + entities.size + claims.size
}

data class SilverSnapshot(
    val revision: Long,
    val sources: List<SilverSource>,
    val evidence: List<SilverEvidence>,
    val observations: List<SilverObservation>,
    val entities: List<SilverEntity>,
    val claims: List<SilverClaim>,
    val processing: List<SilverProcessing>,
    val jobs: SourceJobs = SourceJobs.EMPTY,
) {
    fun needsSilverSnapshot(status: SourceStatus): Boolean =
        status.silverRevision == null || revision != status.silverRevision

    fun withStatus(status: SourceStatus): SilverSnapshot {
        val nextJobs = status.jobs ?: return this
        val nextProcessing = status.processing ?: return this
        if (status.silverRevision != revision || status.jobsRevision != nextJobs.revision ||
            nextJobs.revision < jobs.revision) return this
        return copy(processing = nextProcessing, jobs = nextJobs)
    }

    fun forBronze(id: String): SilverKnowledge {
        val source = sources.firstOrNull { it.bronzeSourceId == id }
        return SilverKnowledge(
            source,
            evidence.filter { it.bronzeSourceId == id },
            observations.filter { it.id in (source?.observationIds ?: emptyList()) },
            entities.filter { it.id in (source?.entityIds ?: emptyList()) },
            claims.filter { it.id in (source?.claimIds ?: emptyList()) },
            processing.firstOrNull { it.bronzeSourceId == id },
        )
    }

    fun label(entity: SilverEntity): String = claims.asSequence()
        .filter { it.subjectEntityId == entity.id && it.state == "active" && it.predicate == "name" }
        .mapNotNull { it.value as? String }
        .firstOrNull() ?: "Unnamed entity"

    fun claimsFor(entityId: String): List<SilverClaim> = claims.filter {
        it.subjectEntityId == entityId || it.objectEntityId == entityId
    }

    fun activeClaimCount(entityId: String): Int = claimsFor(entityId).count { it.state == "active" }

    /** The first supporting observation is the candidate that produced the claim. */
    fun confidence(claim: SilverClaim): Double? = claim.supportingObservationIds.asSequence()
        .mapNotNull { observationId -> observations.firstOrNull { it.id == observationId } }
        .firstOrNull()
        ?.confidence

    fun claimGroupsFor(entityId: String): List<SilverClaimGroup> {
        data class ClaimKey(
            val subjectEntityId: String,
            val predicate: String,
            val value: String?,
            val objectEntityId: String?,
        )

        return claimsFor(entityId)
            .filter { it.state == "active" }
            .groupBy { claim ->
                ClaimKey(
                    claim.subjectEntityId,
                    claim.predicate,
                    claim.value?.let { "${it.javaClass.name}:$it" },
                    claim.objectEntityId,
                )
            }
            .values
            .map { matchingClaims ->
                val sortedClaims = matchingClaims.sortedWith(
                    compareByDescending<SilverClaim> { confidence(it) != null }
                        .thenByDescending { confidence(it) ?: 0.0 }
                        .thenBy { it.id },
                )
                val claim = sortedClaims.first()
                SilverClaimGroup(
                    claim.predicate,
                    claimValue(claim),
                    sortedClaims,
                    confidence(claim),
                    linkedEntityId(claim, entityId),
                )
            }
            .sortedWith(
                compareByDescending<SilverClaimGroup> { it.confidence != null }
                    .thenByDescending { it.confidence ?: 0.0 }
                    .thenBy { it.predicate }
                    .thenBy { it.value },
            )
    }

    private fun claimValue(claim: SilverClaim): String {
        val objectId = claim.objectEntityId
        if (objectId != null) {
            val objectLabel = entities.firstOrNull { it.id == objectId }?.let(::label) ?: "Unknown"
            val subjectLabel = entities.firstOrNull { it.id == claim.subjectEntityId }?.let(::label) ?: "Unknown"
            return "$subjectLabel → $objectLabel"
        }
        return when (val value = claim.value) {
            null -> "Unknown"
            is String -> value
            else -> value.toString()
        }
    }

    private fun linkedEntityId(claim: SilverClaim, viewedEntityId: String): String? {
        val linkedId = when (viewedEntityId) {
            claim.subjectEntityId -> claim.objectEntityId
            claim.objectEntityId -> claim.subjectEntityId
            else -> null
        }
        return linkedId?.takeIf { id -> entities.any { it.id == id } }
    }

    fun describe(claim: SilverClaim): String {
        val predicate = claim.predicate.replace('_', ' ').replace('-', ' ')
        val objectId = claim.objectEntityId
        if (objectId != null) {
            val subject = entities.firstOrNull { it.id == claim.subjectEntityId }
            val objectEntity = entities.firstOrNull { it.id == objectId }
            val subjectLabel = subject?.let(::label) ?: "Unknown"
            val objectLabel = objectEntity?.let(::label) ?: "Unknown"
            return "$subjectLabel — $predicate → $objectLabel"
        }
        return "$predicate · ${claim.value ?: "Unknown"}"
    }

    fun supportingBronze(entityId: String): List<String> {
        val observationIds = claimsFor(entityId)
            .filter { it.state == "active" }
            .flatMap { it.supportingObservationIds }
            .toSet()
        val evidenceIds = observations.filter { it.id in observationIds }.flatMap { it.evidenceIds }.toSet()
        return evidence.filter { it.id in evidenceIds }.map { it.bronzeSourceId }.distinct()
    }

    companion object {
        val EMPTY = SilverSnapshot(0, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

        fun fromJson(value: JSONObject): SilverSnapshot {
            require(value.getInt("schema_version") == 1) { "Unsupported Silver schema" }
            val jobs = value.optJSONObject("jobs")
            return SilverSnapshot(
                value.getLong("revision"),
                value.array("sources").objects().map { source -> SilverSource(
                    source.getString("bronze_source_id"), source.getString("bronze_content_sha256"),
                    source.getString("title"), source.getString("mime"), source.strings("evidence_ids"),
                    source.strings("observation_ids"), source.strings("entity_ids"), source.strings("claim_ids"),
                    source.optBoolean("stale", false),
                    source.optString("model_id").takeIf(String::isNotEmpty),
                    source.optString("model_revision").takeIf(String::isNotEmpty),
                ) },
                value.array("evidence").objects().map { evidence -> SilverEvidence(
                    evidence.getString("id"), evidence.getString("bronze_source_id"),
                    evidence.getString("bronze_content_sha256"), evidence.optJSONObject("selector"),
                    evidence.optString("excerpt"),
                ) },
                value.array("observations").objects().map { observation ->
                    val producer = observation.getJSONObject("producer")
                    SilverObservation(
                        observation.getString("id"), observation.getString("kind"),
                        observation.get("payload"), observation.strings("evidence_ids"),
                        producer.getString("processor_id"), producer.getString("processor_version"),
                        observation.optDouble("confidence").takeUnless(Double::isNaN),
                        producer.optString("model_id").takeIf(String::isNotEmpty),
                        producer.optString("model_revision").takeIf(String::isNotEmpty),
                    )
                },
                value.array("entities").objects().map { SilverEntity(it.getString("id")) },
                value.array("claims").objects().map { claim ->
                    val producer = claim.getJSONObject("producer")
                    SilverClaim(
                        claim.getString("id"), claim.getString("subject_entity_id"), claim.getString("predicate"),
                        claim.opt("value").takeUnless { it == null || it === JSONObject.NULL },
                        claim.optString("object_entity_id").takeIf(String::isNotEmpty),
                        claim.strings("supporting_observation_ids"), claim.getString("state"),
                        producer.getString("processor_id"), producer.getString("processor_version"),
                        producer.optString("model_id").takeIf(String::isNotEmpty),
                        producer.optString("model_revision").takeIf(String::isNotEmpty),
                    )
                },
                SilverProcessing.list(value.array("processing")),
                if (jobs == null) SourceJobs.EMPTY else SourceJobs.fromJson(jobs),
            )
        }
    }
}

class SilverStore(context: Context) {
    private val root = File(context.filesDir, "silver")
    private val file = AtomicFile(File(root, "snapshot.json"))
    private val statusFile = AtomicFile(File(root, "status.json"))
    private var value: SilverSnapshot

    init {
        check(root.mkdirs() || root.isDirectory)
        value = load()
    }

    @Synchronized fun snapshot(): SilverSnapshot = value

    @Synchronized fun needsSilverSnapshot(status: SourceStatus): Boolean = value.needsSilverSnapshot(status)

    @Synchronized fun updateStatus(status: SourceStatus): Boolean {
        val next = value.withStatus(status)
        if (next == value) return false
        write(statusFile, status.persistenceJson().toString().toByteArray(Charsets.UTF_8))
        value = next
        return true
    }

    @Synchronized fun install(json: JSONObject): Boolean {
        val next = SilverSnapshot.fromJson(json)
        if (next.revision < value.revision) return false
        if (next.revision == value.revision && next.jobs.revision < value.jobs.revision) return false
        if (next == value) return false
        write(file, json.toString().toByteArray(Charsets.UTF_8))
        val changed = next != value
        value = next
        return changed
    }

    private fun load(): SilverSnapshot {
        val snapshot = try {
            SilverSnapshot.fromJson(JSONObject(file.openRead().bufferedReader().use { it.readText() }))
        } catch (_: FileNotFoundException) {
            SilverSnapshot.EMPTY
        }
        val status = try {
            sourceStatusFromPersistenceJson(JSONObject(statusFile.openRead().bufferedReader().use { it.readText() }))
        } catch (_: FileNotFoundException) {
            return snapshot
        }
        return snapshot.withStatus(status)
    }

    private fun write(target: AtomicFile, bytes: ByteArray) {
        val output = target.startWrite()
        try {
            output.write(bytes)
            target.finishWrite(output)
        } catch (error: Exception) {
            target.failWrite(output)
            throw error
        }
    }
}

private fun JSONObject.array(name: String): JSONArray = optJSONArray(name) ?: JSONArray()
private fun JSONObject.strings(name: String): List<String> = array(name).let { values ->
    (0 until values.length()).map { values.getString(it) }
}
private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
