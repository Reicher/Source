package com.source.self

import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.atomic.AtomicInteger

data class SourceSyncChanges(var bronze: Boolean = false, var silver: Boolean = false)

object ForegroundSyncState {
    private val owners = AtomicInteger(0)
    val active: Boolean get() = owners.get() > 0
    fun enter() { owners.incrementAndGet() }
    fun leave() { owners.updateAndGet { value -> maxOf(0, value - 1) } }
}

class SourceSynchronizer(
    private val state: PairingState,
    private val bronze: BronzeStore,
    private val silver: SilverStore,
) {
    companion object {
        private val syncLock = ReentrantLock()
    }

    private val transport = PairingTransport(state)

    fun cancel() = transport.cancel()

    fun run(
        source: SourceRef,
        address: String,
        port: Int,
        active: () -> Boolean,
        onSilverChanged: () -> Unit = {},
    ): SourceSyncChanges {
        syncLock.lockInterruptibly()
        try {
            val changes = SourceSyncChanges()
            if (!active()) return changes

            val initialStatus = transport.connect(source, address, port)
            if (!active()) return changes
            if (!state.isPaired()) state.savePaired(source, initialStatus.personId)
            changes.silver = refreshSilver(source, address, port, initialStatus, onSilverChanged)

            BronzeSync(bronze, transport).run(
                source,
                address,
                port,
                active,
                { changes.bronze = true },
                {
                    if (active()) {
                        changes.silver = refreshSilver(
                            source, address, port, transport.status(source, address, port), onSilverChanged,
                        ) || changes.silver
                    }
                },
            )
            if (!active()) return changes
            changes.silver = refreshSilver(
                source, address, port, transport.status(source, address, port), onSilverChanged,
            ) || changes.silver
            return changes
        } finally {
            syncLock.unlock()
        }
    }

    private fun refreshSilver(
        source: SourceRef,
        address: String,
        port: Int,
        status: SourceStatus,
        onChanged: () -> Unit,
    ): Boolean {
        var changed = false
        if (silver.needsSilverSnapshot(status)) {
            changed = silver.install(transport.silver(source, address, port))
        }
        changed = silver.updateStatus(status) || changed
        if (changed) onChanged()
        return changed
    }
}
