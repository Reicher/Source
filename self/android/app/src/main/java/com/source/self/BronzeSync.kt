package com.source.self

data class SyncJobPlan(val key: String, val title: String, val direction: String)

class BronzeSync(private val store: BronzeStore, private val transport: PairingTransport) {
    // The transfer plan is visible and durable on Source. Bronze revisions and tombstones
    // still remain the source of truth, so interrupted work is safely replanned on reconnect.
    fun run(
        source: SourceRef,
        address: String,
        port: Int,
        active: () -> Boolean,
        onChange: () -> Unit,
        onPlan: () -> Unit,
    ) {
        val remote = transport.manifest(source, address, port).associateBy { it.id }
        val local = store.all().associateBy { it.id }
        data class Pending(val plan: SyncJobPlan, val transfer: () -> Unit)
        val pending = mutableListOf<Pending>()
        for (id in (local.keys + remote.keys).sorted()) {
            if (!active()) return
            val here = local[id]
            val there = remote[id]
            when {
                here == null && there != null -> {
                    pending += Pending(SyncJobPlan("$id:${there.revision}:to_self", there.title, "to_self")) {
                        transport.download(source, address, port, there, store)
                    }
                }
                here != null && there == null -> {
                    pending += Pending(SyncJobPlan("$id:${here.revision}:to_source", here.title, "to_source")) {
                        transport.upload(source, address, port, here, if (here.deleted) null else store.content(here))
                        store.acknowledge(here)
                    }
                }
                here != null && there != null && here.revision > there.revision -> {
                    check(here.isDeletionOf(there)) { "Immutable local Bronze conflict" }
                    pending += Pending(SyncJobPlan("$id:${here.revision}:to_source", here.title, "to_source")) {
                        transport.upload(source, address, port, here, if (here.deleted) null else store.content(here))
                        store.acknowledge(here)
                    }
                }
                here != null && there != null && there.revision > here.revision -> {
                    check(here.ackedRevision == here.revision) { "Unsynchronized local Bronze must not be overwritten" }
                    check(there.isDeletionOf(here)) { "Immutable Source Bronze conflict" }
                    pending += Pending(SyncJobPlan("$id:${there.revision}:to_self", there.title, "to_self")) {
                        transport.download(source, address, port, there, store)
                    }
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
        val jobIds = transport.planSync(source, address, port, pending.map(Pending::plan))
        if (!active()) return
        onPlan()
        for (operation in pending) {
            if (!active()) return
            operation.transfer()
            if (active()) onChange()
            transport.completeSync(source, address, port, jobIds.getValue(operation.plan.key))
        }
    }
}
