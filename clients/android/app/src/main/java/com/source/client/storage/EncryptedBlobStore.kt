package com.source.client.storage

import android.content.Context
import com.source.client.security.VaultSession
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class ImportedLibraryBlob(
    val id: String,
    val byteCount: Long,
    val contentSha256: String,
)

class EncryptedUploadPayload internal constructor(
    val file: File,
    val byteCount: Long,
    val encryptedSha256: String,
) : AutoCloseable {
    override fun close() {
        file.delete()
    }
}

/** Streaming AES-GCM storage for Source-owned raw files. */
class EncryptedBlobStore(private val context: Context) {
    private val random = SecureRandom()

    fun importFile(session: VaultSession, input: InputStream): ImportedLibraryBlob {
        check(!session.closed)
        val id = UUID.randomUUID().toString()
        val directory = profileDirectory(session)
        check(directory.exists() || directory.mkdirs()) { "Could not create encrypted Library storage" }
        val temporary = File(directory, ".$id.importing")
        val destination = blobFile(session, id)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val plaintextHash = MessageDigest.getInstance("SHA-256")
        var byteCount = 0L
        try {
            BufferedOutputStream(FileOutputStream(temporary)).use { fileOutput ->
                fileOutput.write(MAGIC)
                fileOutput.write(nonce)
                val cipher = encryptionCipher(session.key, nonce)
                CipherOutputStream(fileOutput, cipher).use { encryptedOutput ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        plaintextHash.update(buffer, 0, read)
                        encryptedOutput.write(buffer, 0, read)
                        byteCount += read
                    }
                    buffer.fill(0)
                }
            }
            moveAtomically(temporary, destination)
            return ImportedLibraryBlob(id, byteCount, plaintextHash.digest().toHex())
        } catch (error: Exception) {
            temporary.delete()
            throw error
        } finally {
            nonce.fill(0)
        }
    }

    fun discardImported(session: VaultSession, id: String) {
        blobFile(session, id).delete()
    }

    fun delete(session: VaultSession, id: String) {
        check(blobFile(session, id).delete() || !blobFile(session, id).exists()) {
            "Could not delete encrypted Library item"
        }
    }

    fun exists(session: VaultSession, id: String): Boolean = blobFile(session, id).isFile

    /** Decrypts and verifies a small local item for an in-app preview. */
    fun readPreview(session: VaultSession, item: LibraryItem, maximumBytes: Long): ByteArray {
        check(!session.closed)
        require(item.byteCount <= maximumBytes) { "The Library item is too large to preview" }
        val plaintextHash = MessageDigest.getInstance("SHA-256")
        val bytes = BufferedInputStream(FileInputStream(blobFile(session, item.id))).use { fileInput ->
            require(fileInput.readExact(MAGIC.size).contentEquals(MAGIC)) { "Invalid encrypted Library item" }
            val nonce = fileInput.readExact(NONCE_BYTES)
            try {
                DigestInputStream(CipherInputStream(fileInput, decryptionCipher(session.key, nonce)), plaintextHash)
                    .use { plaintext -> plaintext.readBytes() }
            } finally {
                nonce.fill(0)
            }
        }
        check(bytes.size.toLong() == item.byteCount) { "Library item size changed" }
        check(plaintextHash.digest().toHex() == item.contentSha256) { "Library item identity changed" }
        return bytes
    }

    fun cleanup(session: VaultSession, referencedIds: Set<String>) {
        profileDirectory(session).listFiles()?.forEach { file ->
            val id = file.name.removeSuffix(".blob")
            if (!file.name.endsWith(".blob") || id !in referencedIds) file.delete()
        }
        File(context.cacheDir, "library-sync").listFiles()?.forEach(File::delete)
    }

    fun createUploadPayload(
        session: VaultSession,
        item: LibraryItem,
        nodeDataKey: ByteArray,
    ): EncryptedUploadPayload {
        check(nodeDataKey.size == 32)
        val stagingDirectory = File(context.cacheDir, "library-sync").also {
            check(it.exists() || it.mkdirs()) { "Could not create Library sync storage" }
        }
        val outputFile = File(stagingDirectory, ".${item.id}.${UUID.randomUUID()}.uploading")
        val remoteNonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val encryptedHash = MessageDigest.getInstance("SHA-256")
        val plaintextHash = MessageDigest.getInstance("SHA-256")
        var plaintextBytes = 0L
        try {
            BufferedInputStream(FileInputStream(blobFile(session, item.id))).use { fileInput ->
                require(fileInput.readExact(MAGIC.size).contentEquals(MAGIC)) { "Invalid encrypted Library item" }
                val localNonce = fileInput.readExact(NONCE_BYTES)
                val localCipher = decryptionCipher(session.key, localNonce)
                DigestInputStream(CipherInputStream(fileInput, localCipher), plaintextHash).use { plaintext ->
                    DigestOutputStream(BufferedOutputStream(FileOutputStream(outputFile)), encryptedHash).use { digestOutput ->
                        digestOutput.write(MAGIC)
                        digestOutput.write(remoteNonce)
                        val remoteCipher = encryptionCipher(nodeDataKey, remoteNonce)
                        CipherOutputStream(digestOutput, remoteCipher).use { encryptedOutput ->
                            val buffer = ByteArray(BUFFER_BYTES)
                            while (true) {
                                val read = plaintext.read(buffer)
                                if (read < 0) break
                                if (read == 0) continue
                                encryptedOutput.write(buffer, 0, read)
                                plaintextBytes += read
                            }
                            buffer.fill(0)
                        }
                    }
                }
                localNonce.fill(0)
            }
            check(plaintextBytes == item.byteCount) { "Library item size changed" }
            check(plaintextHash.digest().toHex() == item.contentSha256) { "Library item identity changed" }
            return EncryptedUploadPayload(
                outputFile,
                outputFile.length(),
                encryptedHash.digest().toHex(),
            )
        } catch (error: Exception) {
            outputFile.delete()
            throw error
        } finally {
            remoteNonce.fill(0)
        }
    }

    private fun profileDirectory(session: VaultSession) = File(context.filesDir, "library/${session.profileId}")
    private fun blobFile(session: VaultSession, id: String) = File(profileDirectory(session), "$id.blob")

    private fun encryptionCipher(key: ByteArray, nonce: ByteArray): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
    }

    private fun decryptionCipher(key: ByteArray, nonce: ByteArray): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
    }

    private fun moveAtomically(source: File, destination: File) {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(source.toPath(), destination.toPath())
        }
    }

    private fun InputStream.readExact(size: Int): ByteArray = ByteArray(size).also { target ->
        var offset = 0
        while (offset < target.size) {
            val read = read(target, offset, target.size - offset)
            require(read > 0) { "Invalid encrypted Library item" }
            offset += read
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") {
        (it.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    private companion object {
        val MAGIC = "SRCLIB01".toByteArray(Charsets.US_ASCII)
        const val NONCE_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val BUFFER_BYTES = 64 * 1024
    }
}
