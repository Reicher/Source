package com.source.self

import org.json.JSONObject
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

class PairingTransport(private val state: PairingState) {
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
        val store = state.keyStore()
        val alias = state.keyAlias()
        val certificate = store.getCertificate(alias) as X509Certificate
        val privateKey = store.getKey(alias, null) as PrivateKey
        val keyManager = object : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<java.security.Principal>?): Array<String> = arrayOf(alias)
            override fun chooseClientAlias(keyTypes: Array<String>?, issuers: Array<java.security.Principal>?, socket: Socket?): String = alias
            override fun chooseEngineClientAlias(keyTypes: Array<String>?, issuers: Array<java.security.Principal>?, engine: SSLEngine?): String = alias
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
        connection.readTimeout = 5000
        connection.requestMethod = method
        connection.setRequestProperty("Accept", "application/json")
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        try {
            if (connection.responseCode != 200) throw IllegalStateException("Source returned ${connection.responseCode}")
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }
}
