package com.source.self

internal fun connectionDurationPresentation(durationMillis: Long): String {
    val totalMinutes = (durationMillis.coerceAtLeast(0L) / 60_000L).toInt()
    if (totalMinutes < 1) return "less than a minute"
    if (totalMinutes < 60) return "$totalMinutes ${if (totalMinutes == 1) "minute" else "minutes"}"

    val totalHours = totalMinutes / 60
    val minutes = totalMinutes % 60
    if (totalHours < 24) return buildString {
        append(totalHours).append(' ').append(if (totalHours == 1) "hour" else "hours")
        if (minutes > 0) append(' ').append(minutes).append(" min")
    }

    val days = totalHours / 24
    val hours = totalHours % 24
    return buildString {
        append(days).append(' ').append(if (days == 1) "day" else "days")
        if (hours > 0) append(' ').append(hours).append(" h")
    }
}
