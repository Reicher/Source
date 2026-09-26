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

internal data class TopCenteredImageLayout(
    val scale: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

internal fun topCenteredImageLayout(
    imageWidth: Int,
    imageHeight: Int,
    viewportWidth: Int,
    viewportHeight: Int,
): TopCenteredImageLayout {
    require(imageWidth > 0 && imageHeight > 0 && viewportWidth > 0 && viewportHeight > 0)
    val scale = minOf(
        viewportWidth.toFloat() / imageWidth,
        viewportHeight.toFloat() / imageHeight,
    )
    val displayedWidth = imageWidth * scale
    val displayedHeight = imageHeight * scale
    val left = (viewportWidth - displayedWidth) / 2f
    return TopCenteredImageLayout(scale, left, 0f, left + displayedWidth, displayedHeight)
}
