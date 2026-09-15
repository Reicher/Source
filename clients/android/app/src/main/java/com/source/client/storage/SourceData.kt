package com.source.client.storage

/** Describes one independently stored and synchronized Source dataset. */
data class SourceDataDescriptor(
    val id: String,
    val remoteAppId: String,
    val snapshotFormat: String,
    val formatVersion: Int,
    /** Collection used by canonical sync. Null keeps an explicitly transitional compatibility path. */
    val canonicalCollection: String? = null,
    /** Node-authoritative datasets are read-only offline caches on Clients. */
    val authority: SourceDataAuthority = SourceDataAuthority.CLIENT,
) {
    init {
        require(id.matches(IDENTIFIER_PATTERN)) { "Invalid local dataset identifier" }
        require(remoteAppId.matches(IDENTIFIER_PATTERN)) { "Invalid remote storage application identifier" }
        require(snapshotFormat.isNotBlank()) { "Snapshot format is required" }
        require(formatVersion > 0) { "Dataset format version must be positive" }
        canonicalCollection?.let {
            require(it.matches(CANONICAL_COLLECTION_PATTERN)) { "Invalid canonical collection identifier" }
        }
    }

    private companion object {
        val IDENTIFIER_PATTERN = Regex("^[a-z][a-z0-9-]{1,31}$")
        val CANONICAL_COLLECTION_PATTERN = Regex("^[a-z][a-z0-9-]{1,63}$")
    }
}

enum class SourceDataAuthority { CLIENT, NODE }

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

/** Timestamp resolution is retained only for datasets still on the legacy whole-snapshot transport. */
internal fun resolveLegacySourceData(
    local: SourceDataVersion,
    remote: SourceDataVersion,
): SourceDataResolution = when {
    remote.modifiedAtMillis > local.modifiedAtMillis -> SourceDataResolution.USE_REMOTE
    remote.contentIdentity == local.contentIdentity -> SourceDataResolution.MATCH
    else -> SourceDataResolution.KEEP_LOCAL
}
