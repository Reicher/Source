package com.source.self

class BronzeSync(private val store: BronzeStore, private val transport: PairingTransport) {
    // This queue exists only for this trusted connection. Durable revisions and tombstones
    // are read again on the next run, so an interrupted transfer never needs resuming.
    fun run(source: SourceRef, address: String, port: Int, active: () -> Boolean, onChange: () -> Unit) {
        val remote = transport.manifest(source, address, port).associateBy { it.id }
        val local = store.all().associateBy { it.id }
        for (id in (local.keys + remote.keys).sorted()) {
            if (!active()) return
            val here = local[id]
            val there = remote[id]
            when {
                here == null && there != null -> {
                    transport.download(source, address, port, there, store)
                    if (active()) onChange()
                }
                here != null && there == null -> {
                    transport.upload(source, address, port, here, if (here.deleted) null else store.content(here))
                    if (active()) { store.acknowledge(here); onChange() }
                }
                here != null && there != null && here.revision > there.revision -> {
                    check(here.isDeletionOf(there)) { "Immutable local Bronze conflict" }
                    transport.upload(source, address, port, here, if (here.deleted) null else store.content(here))
                    if (active()) { store.acknowledge(here); onChange() }
                }
                here != null && there != null && there.revision > here.revision -> {
                    check(here.ackedRevision == here.revision) { "Unsynchronized local Bronze must not be overwritten" }
                    check(there.isDeletionOf(here)) { "Immutable Source Bronze conflict" }
                    transport.download(source, address, port, there, store)
                    if (active()) onChange()
                }
                here != null && there != null -> {
                    check(here.sameBronze(there)) { "Bronze revision mismatch" }
                    if (here.ackedRevision != here.revision) {
                        store.acknowledge(here)
                        if (active()) onChange()
                    }
                }
            }
        }
    }
}
