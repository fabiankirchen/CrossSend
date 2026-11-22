package com.crosssend.app.util

import android.util.Log
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

/**
 * Zero-PII logging helpers safe on both Android and plain JVM unit tests.
 *
 * Strips URL credentials, paths, query strings, fragments, and local filesystem
 * directories before forwarding log messages to [android.util.Log].
 */
object SafeLogger {

    private const val MAX_MESSAGE_LENGTH = 500
    private const val MAX_PATH_TAIL_LENGTH = 64

    private val EMBEDDED_URL_REGEX = Regex(
        """https?://[^\s"'<>)]+""",
        RegexOption.IGNORE_CASE
    )

    private val PRIVATE_PATH_REGEX = Regex(
        """(?:/data/user/\d+|/data/data|/storage/emulated/\d+|/Users|/home)/[^\s"']+"""
    )

    /**
     * Redacts sensitive components from [rawUrl].
     *
     * - Null or blank inputs return `"<empty>"`.
     * - HTTP/HTTPS URLs preserve scheme, host, and non-default port, replacing non-root
     *   paths with `"/..."` and omitting userinfo, query parameters, and fragments.
     * - `content://` URIs preserve only the sanitized authority (`"content://<authority>/..."`).
     * - `file:`, `mailto:`, and `data:` URIs collapse to `"<scheme>:<redacted>"`.
     * - Unrecognized or malformed URLs return `"<invalid-url>"`.
     */
    fun redactUrl(rawUrl: String?): String {
        if (rawUrl.isNullOrBlank()) return "<empty>"
        val trimmed = rawUrl.trim()
        val lower = trimmed.lowercase(Locale.ROOT)

        if (lower.startsWith("content://")) {
            val afterScheme = trimmed.substring("content://".length)
            val rawAuthority = afterScheme
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore('#')
            val authority = rawAuthority.substringAfterLast('@').trim()
            return if (authority.isNotEmpty()) {
                "content://$authority/..."
            } else {
                "content:<redacted>"
            }
        }

        if (lower.startsWith("file:")) return "file:<redacted>"
        if (lower.startsWith("mailto:")) return "mailto:<redacted>"
        if (lower.startsWith("data:")) return "data:<redacted>"

        val httpUrl = trimmed.toHttpUrlOrNull() ?: return "<invalid-url>"
        val formattedHost = if (':' in httpUrl.host) "[${httpUrl.host}]" else httpUrl.host
        val portSuffix = if (httpUrl.port != HttpUrl.defaultPort(httpUrl.scheme)) {
            ":${httpUrl.port}"
        } else {
            ""
        }
        val hasNonRootPath = httpUrl.pathSegments.any { it.isNotEmpty() }
        val pathSuffix = if (hasNonRootPath) "/..." else "/"
        return "${httpUrl.scheme}://$formattedHost$portSuffix$pathSuffix"
    }

    /**
     * Redacts directory hierarchy, query strings, and embedded URLs from [rawPath].
     */
    fun redactPath(rawPath: String?): String {
        if (rawPath.isNullOrBlank()) return "<empty>"
        val trimmed = rawPath.trim()
        val lower = trimmed.lowercase(Locale.ROOT)

        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return redactUrl(trimmed)
        }
        if ("http://" in lower || "https://" in lower) {
            return "<redacted-path>"
        }

        val withoutQuery = trimmed.substringBefore('?').substringBefore('#')
        val trimmedTrailing = withoutQuery.trimEnd('/', '\\')
        if (trimmedTrailing.isEmpty()) return "<root>"

        val baseName = trimmedTrailing
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
        if (baseName.isEmpty()) return "<empty>"

        return ".../${baseName.take(MAX_PATH_TAIL_LENGTH)}"
    }

    /**
     * Sanitizes [message] by redacting embedded HTTP(S) URLs and private UNIX-style
     * storage/home paths, and truncates messages longer than 500 characters.
     */
    fun sanitizeMessage(message: String?): String {
        if (message.isNullOrBlank()) return ""

        var sanitized = EMBEDDED_URL_REGEX.replace(message) { match ->
            val rawMatch = match.value
            val coreUrl = rawMatch.trimEnd('.', ',', ';', ':')
            val trailing = rawMatch.substring(coreUrl.length)
            if (coreUrl.isEmpty()) rawMatch else redactUrl(coreUrl) + trailing
        }

        sanitized = PRIVATE_PATH_REGEX.replace(sanitized, "<local-path>")

        return if (sanitized.length > MAX_MESSAGE_LENGTH) {
            sanitized.take(MAX_MESSAGE_LENGTH - 3) + "..."
        } else {
            sanitized
        }
    }

    /**
     * Formats a sanitized log line with an optional sanitized [throwable] summary
     * so raw exception URLs or paths are never forwarded to logcat.
     */
    internal fun formatLogMessage(message: String, throwable: Throwable? = null): String {
        val safeMessage = sanitizeMessage(message)
        if (throwable == null) return safeMessage

        val throwableName = throwable::class.java.simpleName.ifBlank { "Throwable" }
        val throwableMessage = sanitizeMessage(throwable.message)
        val throwableSummary = if (throwableMessage.isNotEmpty()) {
            "$throwableName: $throwableMessage"
        } else {
            throwableName
        }

        return if (safeMessage.isNotEmpty()) {
            "$safeMessage ($throwableSummary)"
        } else {
            throwableSummary
        }
    }

    fun d(tag: String, message: String) {
        val formatted = formatLogMessage(message)
        try {
            Log.d(tag, formatted)
        } catch (_: Throwable) {
            // android.util.Log is stubbed in plain JVM unit tests.
        }
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        val formatted = formatLogMessage(message, throwable)
        try {
            Log.w(tag, formatted)
        } catch (_: Throwable) {
            // android.util.Log is stubbed in plain JVM unit tests.
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val formatted = formatLogMessage(message, throwable)
        try {
            Log.e(tag, formatted)
        } catch (_: Throwable) {
            // android.util.Log is stubbed in plain JVM unit tests.
        }
    }
}
