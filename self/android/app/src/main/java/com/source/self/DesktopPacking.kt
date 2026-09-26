package com.source.self

internal data class DesktopPackSize(val width: Int, val height: Int)

internal data class DesktopPackPlacement(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height
}

/** Stable dense packing that lets compact cards occupy space beside taller cards. */
internal fun packDesktopItems(
    sizes: List<DesktopPackSize>,
    maxWidth: Int,
    gap: Int,
): List<DesktopPackPlacement> {
    if (maxWidth <= 0) return sizes.map { DesktopPackPlacement(0, 0, 0, it.height.coerceAtLeast(0)) }
    val placed = mutableListOf<DesktopPackPlacement>()
    sizes.forEach { requested ->
        val width = requested.width.coerceIn(1, maxWidth)
        val height = requested.height.coerceAtLeast(1)
        val xCandidates = buildSet {
            add(0)
            placed.forEach { placement ->
                (placement.right + gap).takeIf { it + width <= maxWidth }?.let(::add)
            }
        }.sorted()
        val yCandidates = buildSet {
            add(0)
            placed.forEach { add(it.bottom + gap) }
        }.sorted()
        val next = yCandidates.asSequence().flatMap { y ->
            xCandidates.asSequence().map { x -> DesktopPackPlacement(x, y, width, height) }
        }.firstOrNull { candidate ->
            candidate.right <= maxWidth && placed.none { existing -> overlaps(candidate, existing, gap) }
        } ?: DesktopPackPlacement(0, (placed.maxOfOrNull { it.bottom } ?: 0) + gap, width, height)
        placed += next
    }
    return placed
}

private fun overlaps(
    first: DesktopPackPlacement,
    second: DesktopPackPlacement,
    gap: Int,
): Boolean = first.x < second.right + gap && first.right + gap > second.x &&
    first.y < second.bottom + gap && first.bottom + gap > second.y
