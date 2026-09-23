package com.source.self

import org.json.JSONObject
import org.json.JSONArray
import android.util.Base64
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import java.net.Socket
import javax.net.ssl.SSLEngine

private fun pin(cert: X509Certificate): String = MessageDigest.getInstance("SHA-256")
    .digest(cert.encoded).joinToString("") { "%02x".format(it) }

class PairingHttpException(val status: Int, val path: String) : Exception("Source returned $status for $path")

class PairingTransport(private val state: PairingState) {
    @Volatile private var current: HttpsURLConnection? = null
    fun cancel() { current?.disconnect() }
    private fun close(connection: HttpsURLConnection) {
        connection.disconnect()
        if (current === connection) current = null
    }
    fun connect(source: SourceRef, address: String, port: Int): String {
        val urlHost = if (address.contains(':')) "[$address]" else address
        val base = "https://$urlHost:$port"
        if (!state.isPaired()) {
            val request = JSONObject().put("id", source.id).put("token", source.token)
            val response = call(base, "/v1/pair", source.pin, "POST", request.toString())
            val id = response.getString("id")
            require(id == source.id) { "wrong Source response" }
            return response.getString("person_id")
        }
        val response = call(base, "/v1/status", source.pin, "GET", null)
        require(response.getString("id") == source.id) { "wrong Source response" }
        require(response.getString("status") == "connected") { "Source rejected connection" }
        val personId = response.getString("person_id")
        require(personId == state.personId()) { "Source relationship changed" }
        return personId
    }

    private fun call(base: String, path: String, pinnedSource: String, method: String, body: String?): JSONObject {
        val connection = open(base, path, pinnedSource, method)
        try {
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            if (connection.responseCode != 200) throw PairingHttpException(connection.responseCode, path)
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { close(connection) }
    }

    fun manifest(source: SourceRef, address: String, port: Int): List<BronzeItem> {
        val connection = open(base(address, port), "/v1/bronze", source.pin, "GET")
        try {
            if (connection.responseCode != 200) throw PairingHttpException(connection.responseCode, "/v1/bronze")
            return BronzeItem.list(JSONArray(connection.inputStream.bufferedReader().use { it.readText() }))
        } finally { close(connection) }
    }

    fun silver(source: SourceRef, address: String, port: Int): JSONObject {
        val connection = open(base(address, port), "/v1/silver", source.pin, "GET")
        try {
            if (connection.responseCode != 200) throw PairingHttpException(connection.responseCode, "/v1/silver")
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { close(connection) }
    }

    fun planSync(source: SourceRef, address: String, port: Int, jobs: List<SyncJobPlan>): Map<String, String> {
        val values = JSONArray()
        jobs.forEach { job -> values.put(JSONObject()
            .put("key", job.key).put("title", job.title).put("direction", job.direction)) }
        val response = call(base(address, port), "/v1/jobs/sync", source.pin, "POST",
            JSONObject().put("jobs", values).toString())
        val planned = response.getJSONArray("jobs")
        return (0 until planned.length()).associate { index ->
            val job = planned.getJSONObject(index)
            job.getString("key") to job.getString("job_id")
        }
    }

    fun completeSync(source: SourceRef, address: String, port: Int, jobId: String) {
        call(base(address, port), "/v1/jobs/sync/$jobId/complete", source.pin, "POST", null)
    }

    fun upload(source: SourceRef, address: String, port: Int, item: BronzeItem, file: File?) {
        val connection = open(base(address, port), "/v1/bronze/${item.id}", source.pin,
            if (item.deleted) "DELETE" else "PUT")
        try {
            val header = Base64.encodeToString(item.json().toString().toByteArray(Charsets.UTF_8),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            connection.setRequestProperty("X-Bronze-Metadata", header)
            if (!item.deleted) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(item.size)
                file?.inputStream()?.use { input -> connection.outputStream.use { output -> input.copyTo(output) } }
                    ?: error("Bronze content missing")
            }
            if (connection.responseCode != 200) throw PairingHttpException(connection.responseCode, "/v1/bronze/${item.id}")
        } finally { close(connection) }
    }

    fun download(source: SourceRef, address: String, port: Int, item: BronzeItem, store: BronzeStore) {
        if (item.deleted) { store.install(item, null); return }
        val connection = open(base(address, port), "/v1/bronze/${item.id}", source.pin, "GET")
        try {
            if (connection.responseCode != 200) throw PairingHttpException(connection.responseCode, "/v1/bronze/${item.id}")
            connection.inputStream.use { store.install(item, it) }
        } finally { close(connection) }
    }

    private fun base(address: String, port: Int): String =
        "https://${if (address.contains(':')) "[$address]" else address}:$port"

    private fun open(base: String, path: String, pinnedSource: String, method: String): HttpsURLConnection {
        val store = state.keyStore()
        val alias = state.keyAlias()
        val certificate = store.getCertificate(alias) as X509Certificate
        val privateKey = store.getKey(alias, null) as PrivateKey
        val keyManager = object : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<java.security.Principal>?): Array<String>? =
                if (keyType == "EC") arrayOf(alias) else null
            override fun chooseClientAlias(keyTypes: Array<String>?, issuers: Array<java.security.Principal>?, socket: Socket?): String? =
                if (keyTypes?.contains("EC") == true) alias else null
            override fun chooseEngineClientAlias(keyTypes: Array<String>?, issuers: Array<java.security.Principal>?, engine: SSLEngine?): String? =
                if (keyTypes?.contains("EC") == true) alias else null
            override fun getServerAliases(keyType: String?, issuers: Array<java.security.Principal>?): Array<String>? = null
            override fun chooseServerAlias(keyType: String?, issuers: Array<java.security.Principal>?, socket: Socket?): String? = null
            override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(certificate)
            override fun getPrivateKey(alias: String?): PrivateKey = privateKey
        }
        val trustManager = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw UnsupportedOperationException()
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                require(chain.size == 1 && pin(chain[0]) == pinnedSource) { "Source identity does not match QR" }
                chain[0].checkValidity()
            }
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(arrayOf<KeyManager>(keyManager), arrayOf(trustManager), null) }
        val connection = URL(base + path).openConnection() as HttpsURLConnection
        connection.sslSocketFactory = ssl.socketFactory
        connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, session ->
            try { pin(session.peerCertificates[0] as X509Certificate) == pinnedSource } catch (_: Exception) { false }
        }
        connection.connectTimeout = 5000
        connection.readTimeout = 15000
        connection.requestMethod = method
        connection.setRequestProperty("Accept", "application/json")
        current = connection
        return connection
    }
}
