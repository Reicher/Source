package com.source.self

internal fun excerptWords(value: String, limit: Int = 15): String {
    val words = value.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    if (words.isEmpty()) return ""
    return words.take(limit).joinToString(" ") + if (words.size > limit) "…" else ""
}

internal fun imageSampleSize(width: Int, height: Int, maxDimension: Int): Int {
    require(maxDimension > 0)
    val largest = maxOf(width, height).coerceAtLeast(1).toLong()
    return ((largest + maxDimension - 1L) / maxDimension).coerceAtLeast(1L).toInt()
}
