package com.source.client.storage

import com.source.client.security.SecureVault
import com.source.client.security.VaultSession

class SourceDataStore(private val secureVault: SecureVault) {
    fun <T> load(session: VaultSession, data: SourceData<T>): T {
        val plaintext = secureVault.loadData(session, data.descriptor) ?: return data.emptyValue
        return try {
            data.decode(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    fun <T> save(session: VaultSession, data: SourceData<T>, value: T) {
        val plaintext = data.encode(value)
        try {
            secureVault.saveData(session, data.descriptor, plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    fun <T> createSnapshot(
        session: VaultSession,
        data: SourceData<T>,
        value: T,
        encryptionKey: ByteArray = session.key,
    ): ByteArray {
        val plaintext = data.encode(value)
        return try {
            secureVault.createDataSnapshot(session, data.descriptor, plaintext, encryptionKey)
        } finally {
            plaintext.fill(0)
        }
    }

    fun <T> readSnapshot(
        session: VaultSession,
        data: SourceData<T>,
        snapshot: ByteArray,
        encryptionKey: ByteArray = session.key,
    ): T {
        val plaintext = secureVault.readDataSnapshot(
            session,
            data.descriptor,
            snapshot,
            encryptionKey,
            data.supportedFormatVersions,
        )
        return try {
            data.decode(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }
}
