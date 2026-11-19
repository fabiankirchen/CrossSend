package com.crosssend.app.util

import java.text.SimpleDateFormat
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Date
import java.util.Locale

object DateUtils {

    private val rfcPatterns = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z",
        "EEE, dd MMM yyyy HH:mm:ss z",
        "EEE, d MMM yyyy HH:mm:ss Z",
        "EEE, d MMM yyyy HH:mm:ss z",
        "dd MMM yyyy HH:mm:ss Z",
        "dd MMM yyyy HH:mm:ss z",
        "d MMM yyyy HH:mm:ss Z",
        "EEE, dd MMM yyyy HH:mm Z",
        "EEE, d MMM yyyy HH:mm Z",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd",
        "dd.MM.yyyy HH:mm:ss",
        "dd.MM.yyyy HH:mm",
        "dd.MM.yyyy"
    )

    fun parseDate(raw: String?): Date? {
        if (raw.isNullOrBlank()) return null
        val clean = raw.trim()

        // 1. Try Java 8 Instant / OffsetDateTime (robust ISO 8601)
        try {
            return Date.from(Instant.parse(clean))
        } catch (_: Exception) {}

        try {
            return Date.from(OffsetDateTime.parse(clean).toInstant())
        } catch (_: Exception) {}

        try {
            return Date.from(OffsetDateTime.parse(clean, DateTimeFormatter.ISO_DATE_TIME).toInstant())
        } catch (_: Exception) {}

        // 2. Try RFC patterns with Locale.US
        for (pattern in rfcPatterns) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US).apply { isLenient = true }
                val parsed = sdf.parse(clean)
                if (parsed != null) return parsed
            } catch (_: Exception) {}
        }

        // 3. Try RFC patterns with Locale.GERMANY
        for (pattern in rfcPatterns) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.GERMANY).apply { isLenient = true }
                val parsed = sdf.parse(clean)
                if (parsed != null) return parsed
            } catch (_: Exception) {}
        }

        return null
    }

    private val filenameTimestampRegex = Regex("^(\\d{8}_\\d{4})_.*")

    fun formatForFilename(date: Date): String {
        return SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT).format(date)
    }

    fun parseFilenameTimestamp(fileName: String): Date? {
        val match = filenameTimestampRegex.matchEntire(fileName) ?: return null
        return try {
            SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT).apply { isLenient = false }.parse(match.groupValues[1])
        } catch (_: Exception) {
            null
        }
    }

    fun formatForByline(date: Date): String {
        val isGerman = Locale.getDefault().language == "de"
        return if (isGerman) {
            SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(date)
        } else {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)
        }
    }

    fun formatRelativeOrDate(date: Date): String {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply { time = date }

        val diffMillis = now.timeInMillis - target.timeInMillis
        val isGerman = Locale.getDefault().language == "de"
        val locale = if (isGerman) Locale.GERMANY else Locale.US

        if (diffMillis in 0 until 60 * 1000) {
            return if (isGerman) "Gerade eben" else "Just now"
        } else if (diffMillis in 60 * 1000 until 60 * 60 * 1000) {
            val mins = diffMillis / (60 * 1000)
            return if (isGerman) "Vor $mins Min." else "${mins}m ago"
        } else if (diffMillis in 60 * 60 * 1000 until 24 * 60 * 60 * 1000 &&
            now.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR) &&
            now.get(Calendar.YEAR) == target.get(Calendar.YEAR)
        ) {
            val hours = diffMillis / (60 * 60 * 1000)
            return if (isGerman) "Vor $hours Std." else "${hours}h ago"
        }

        val isSameDay = now.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)

        val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        val isYesterday = yesterday.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
                yesterday.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)

        val timeStr = SimpleDateFormat("HH:mm", locale).format(date)
        val todayStr = if (isGerman) "Heute" else "Today"
        val yesterdayStr = if (isGerman) "Gestern" else "Yesterday"

        return when {
            isSameDay -> "$todayStr, $timeStr"
            isYesterday -> "$yesterdayStr, $timeStr"
            now.get(Calendar.YEAR) == target.get(Calendar.YEAR) -> {
                SimpleDateFormat("d. MMM", locale).format(date)
            }
            else -> {
                SimpleDateFormat(if (isGerman) "dd.MM.yyyy" else "yyyy-MM-dd", locale).format(date)
            }
        }
    }
}
