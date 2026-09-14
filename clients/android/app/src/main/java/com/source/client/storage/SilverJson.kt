package com.source.client.storage

import java.security.MessageDigest
import java.text.Normalizer
import org.erdtman.jcs.JsonCanonicalizer
import org.json.JSONArray
import org.json.JSONObject

/** The I-JSON value types accepted in Silver identity-bearing structured data. */
sealed interface SilverJsonValue

data object SilverJsonNull : SilverJsonValue

data class SilverJsonBoolean(val value: Boolean) : SilverJsonValue

data class SilverJsonNumber(val value: Double) : SilverJsonValue {
    init {
        require(value.isFinite()) { "Silver JSON numbers must be finite" }
    }
}

data class SilverJsonString(val value: String) : SilverJsonValue

data class SilverJsonArray(val values: List<SilverJsonValue>) : SilverJsonValue

data class SilverJsonObject(val properties: Map<String, SilverJsonValue>) : SilverJsonValue

internal fun normalizeSilverJson(value: SilverJsonValue): SilverJsonValue = when (value) {
    SilverJsonNull -> SilverJsonNull
    is SilverJsonBoolean -> value
    is SilverJsonNumber -> if (value.value == 0.0) SilverJsonNumber(0.0) else value
    is SilverJsonString -> SilverJsonString(normalizeSilverText(value.value))
    is SilverJsonArray -> SilverJsonArray(value.values.map(::normalizeSilverJson))
    is SilverJsonObject -> {
        val normalized = linkedMapOf<String, SilverJsonValue>()
        value.properties.forEach { (rawName, rawValue) ->
            val name = normalizeSilverText(rawName)
            require(name !in normalized) { "Silver JSON property names collide after NFC normalization" }
            normalized[name] = normalizeSilverJson(rawValue)
        }
        SilverJsonObject(normalized.toMap())
    }
}

internal fun normalizeSilverText(value: String): String {
    requireValidUnicode(value)
    return Normalizer.normalize(value, Normalizer.Form.NFC)
}

internal fun encodeSilverJson(value: SilverJsonValue): Any = when (value) {
    SilverJsonNull -> JSONObject.NULL
    is SilverJsonBoolean -> value.value
    is SilverJsonNumber -> value.value
    is SilverJsonString -> value.value
    is SilverJsonArray -> JSONArray().apply { value.values.forEach { put(encodeSilverJson(it)) } }
    is SilverJsonObject -> JSONObject().apply {
        value.properties.forEach { (name, child) -> put(name, encodeSilverJson(child)) }
    }
}

internal fun decodeSilverJson(value: Any?): SilverJsonValue = normalizeSilverJson(when (value) {
    null, JSONObject.NULL -> SilverJsonNull
    is Boolean -> SilverJsonBoolean(value)
    is String -> SilverJsonString(value)
    is Byte, is Short, is Int -> SilverJsonNumber((value as Number).toDouble())
    is Long -> {
        require(value in -MAXIMUM_EXACT_JSON_INTEGER..MAXIMUM_EXACT_JSON_INTEGER) {
            "Silver JSON integers must be exactly representable as binary64"
        }
        SilverJsonNumber(value.toDouble())
    }
    is Float, is Double -> SilverJsonNumber((value as Number).toDouble())
    is JSONArray -> SilverJsonArray(List(value.length()) { decodeSilverJson(value.get(it)) })
    is JSONObject -> {
        val names = value.keys().asSequence().toList()
        SilverJsonObject(names.associateWith { decodeSilverJson(value.get(it)) })
    }
    else -> throw IllegalArgumentException("Unsupported Silver JSON value")
})

internal fun canonicalSilverJson(value: SilverJsonValue): ByteArray {
    val normalized = normalizeSilverJson(value)
    return JsonCanonicalizer(serializeSilverJson(normalized)).encodedUTF8
}

internal fun silverRecordId(prefix: String, identity: SilverJsonObject): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(prefix.toByteArray(Charsets.UTF_8))
    digest.update(0)
    digest.update(canonicalSilverJson(identity))
    return digest.digest().joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}

private fun serializeSilverJson(value: SilverJsonValue): String = when (value) {
    SilverJsonNull -> "null"
    is SilverJsonBoolean -> value.value.toString()
    is SilverJsonNumber -> value.value.toString()
    is SilverJsonString -> quoteSilverJson(value.value)
    is SilverJsonArray -> value.values.joinToString(prefix = "[", postfix = "]") { serializeSilverJson(it) }
    is SilverJsonObject -> value.properties.entries.joinToString(prefix = "{", postfix = "}") { (name, child) ->
        "${quoteSilverJson(name)}:${serializeSilverJson(child)}"
    }
}

private fun quoteSilverJson(value: String): String = buildString {
    append('"')
    value.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\t' -> append("\\t")
            '\n' -> append("\\n")
            '\u000c' -> append("\\f")
            '\r' -> append("\\r")
            else -> if (character.code < 0x20) {
                append("\\u")
                append(character.code.toString(16).padStart(4, '0'))
            } else {
                append(character)
            }
        }
    }
    append('"')
}

private fun requireValidUnicode(value: String) {
    var index = 0
    while (index < value.length) {
        val character = value[index]
        when {
            character.isHighSurrogate() -> {
                require(index + 1 < value.length && value[index + 1].isLowSurrogate()) {
                    "Silver JSON contains invalid Unicode"
                }
                index += 2
            }
            character.isLowSurrogate() -> throw IllegalArgumentException("Silver JSON contains invalid Unicode")
            else -> index += 1
        }
    }
}

private const val MAXIMUM_EXACT_JSON_INTEGER = 9_007_199_254_740_991L
