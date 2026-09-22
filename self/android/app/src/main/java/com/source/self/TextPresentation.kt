package com.source.self

internal fun excerptWords(value: String, limit: Int = 15): String {
    val words = value.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    if (words.isEmpty()) return ""
    return words.take(limit).joinToString(" ") + if (words.size > limit) "…" else ""
}
