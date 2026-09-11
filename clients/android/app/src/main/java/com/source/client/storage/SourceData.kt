package com.source.client.storage

/** Describes one independently stored and synchronized Source dataset. */
data class SourceDataDescriptor(
    val id: String,
    val remoteAppId: String,
    val snapshotFormat: String,
    val formatVersion: Int,
) {
    init {
        require(id.matches(IDENTIFIER_PATTERN)) { "Invalid local dataset identifier" }
        require(remoteAppId.matches(IDENTIFIER_PATTERN)) { "Invalid remote storage application identifier" }
        require(snapshotFormat.isNotBlank()) { "Snapshot format is required" }
        require(formatVersion > 0) { "Dataset format version must be positive" }
    }

    private companion object {
        val IDENTIFIER_PATTERN = Regex("^[a-z][a-z0-9-]{1,31}$")
    }
}

data class SourceDataVersion(
    val modifiedAtMillis: Long,
    val contentIdentity: String,
)

interface SourceData<T> {
    val descriptor: SourceDataDescriptor
    val supportedFormatVersions: Set<Int> get() = setOf(descriptor.formatVersion)
    val emptyValue: T

    fun encode(value: T): ByteArray
    fun decode(value: ByteArray): T
    fun version(value: T): SourceDataVersion
    /** Optional dataset-specific merge, used when independent clients can safely reconcile item-by-item. */
    fun merge(local: T, remote: T): T? = null
}

internal enum class SourceDataResolution { USE_REMOTE, MATCH, KEEP_LOCAL }

internal fun resolveSourceData(
    local: SourceDataVersion,
    remote: SourceDataVersion,
): SourceDataResolution = when {
    remote.modifiedAtMillis > local.modifiedAtMillis -> SourceDataResolution.USE_REMOTE
    remote.contentIdentity == local.contentIdentity -> SourceDataResolution.MATCH
    else -> SourceDataResolution.KEEP_LOCAL
}
