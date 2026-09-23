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

data class SilverProcessing(
    val bronzeSourceId: String,
    val state: String,
    val completedBatches: Int,
    val totalBatches: Int,
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
) {
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
        .firstOrNull() ?: "Entity ${entity.id.take(8)}"

    fun claimsFor(entityId: String): List<SilverClaim> = claims.filter {
        it.subjectEntityId == entityId || it.objectEntityId == entityId
    }

    fun supportingBronze(entityId: String): List<String> {
        val observationIds = claimsFor(entityId).flatMap { it.supportingObservationIds }.toSet()
        val evidenceIds = observations.filter { it.id in observationIds }.flatMap { it.evidenceIds }.toSet()
        return evidence.filter { it.id in evidenceIds }.map { it.bronzeSourceId }.distinct()
    }

    companion object {
        val EMPTY = SilverSnapshot(0, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

        fun fromJson(value: JSONObject): SilverSnapshot {
            require(value.getInt("schema_version") == 1) { "Unsupported Silver schema" }
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
                value.array("processing").objects().map { processing -> SilverProcessing(
                    processing.getString("bronze_source_id"), processing.getString("state"),
                    processing.getInt("completed_batches"), processing.getInt("total_batches"),
                ) },
            )
        }
    }
}

class SilverStore(context: Context) {
    private val root = File(context.filesDir, "silver")
    private val file = AtomicFile(File(root, "snapshot.json"))
    private var value: SilverSnapshot

    init {
        check(root.mkdirs() || root.isDirectory)
        value = load()
    }

    @Synchronized fun snapshot(): SilverSnapshot = value

    @Synchronized fun install(json: JSONObject): Boolean {
        val next = SilverSnapshot.fromJson(json)
        if (next.revision < value.revision) return false
        if (next.revision == value.revision) return false
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
            val changed = next != value
            value = next
            return changed
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    private fun load(): SilverSnapshot = try {
        SilverSnapshot.fromJson(JSONObject(file.openRead().bufferedReader().use { it.readText() }))
    } catch (_: FileNotFoundException) {
        SilverSnapshot.EMPTY
    }
}

private fun JSONObject.array(name: String): JSONArray = optJSONArray(name) ?: JSONArray()
private fun JSONObject.strings(name: String): List<String> = array(name).let { values ->
    (0 until values.length()).map { values.getString(it) }
}
private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
