package com.crosssend.app.sync

import com.crosssend.app.converter.EInkImageOptimizer
import com.crosssend.app.data.local.DocumentEntity
import com.crosssend.app.data.model.DocumentType
import com.crosssend.app.data.model.RenderMode
import com.crosssend.app.data.model.SyncStatus
import com.crosssend.app.network.WebDavClient
import org.json.JSONArray
import org.json.JSONObject

enum class SyncManifestStatus {
    ACTIVE,
    ARCHIVED
}

data class SyncManifestEntry(
    val key: String,
    val type: DocumentType,
    val sourceUrl: String?,
    val title: String,
    val author: String?,
    val renderMode: RenderMode,
    val fileName: String,
    val remotePath: String,
    val excerpt: String?,
    val status: SyncManifestStatus,
    val createdAt: Long,
    val updatedAt: Long
)

data class SyncManifest(
    val version: Int = CURRENT_VERSION,
    val updatedAt: Long,
    val entries: List<SyncManifestEntry>
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

object SyncManifestCodec {
    const val REMOTE_MANIFEST_PATH = "/crosssend/.crosssend-sync.json"
    private const val MAX_MANIFEST_ENTRIES = 2000
    private const val TOMBSTONE_RETENTION_MS = 90L * 24L * 60L * 60L * 1000L

    fun canonicalKey(sourceUrl: String?, remotePath: String): String {
        val cleanUrl = sourceUrl?.trim()?.takeIf { it.isNotBlank() }
        if (cleanUrl != null) return cleanUrl
        return WebDavClient.sanitizeRemotePath(remotePath)
    }

    fun fromDocumentEntity(doc: DocumentEntity): SyncManifestEntry? {
        if (doc.type == DocumentType.SLEEP) return null
        val status = when (doc.syncStatus) {
            SyncStatus.ARCHIVED, SyncStatus.PENDING_DELETE -> SyncManifestStatus.ARCHIVED
            SyncStatus.SYNCED, SyncStatus.PENDING_UPLOAD -> SyncManifestStatus.ACTIVE
            SyncStatus.FAILED -> return null
        }
        val safeRemotePath = try {
            WebDavClient.sanitizeRemotePath(doc.remotePath)
        } catch (_: Exception) {
            return null
        }
        val cleanUrl = doc.sourceUrl?.trim()?.takeIf {
            it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true)
        }
        val updatedAt = when (status) {
            SyncManifestStatus.ARCHIVED -> doc.archivedAt ?: doc.createdAt
            SyncManifestStatus.ACTIVE -> doc.createdAt
        }
        return SyncManifestEntry(
            key = canonicalKey(cleanUrl, safeRemotePath),
            type = doc.type,
            sourceUrl = cleanUrl,
            title = doc.title.take(300),
            author = doc.author?.take(200),
            renderMode = doc.renderMode,
            fileName = doc.fileName.take(255),
            remotePath = safeRemotePath,
            excerpt = doc.excerpt?.take(300),
            status = status,
            createdAt = doc.createdAt,
            updatedAt = updatedAt
        )
    }

    fun parse(rawJson: String): SyncManifest? {
        if (rawJson.isBlank()) return null
        return try {
            val root = JSONObject(rawJson)
            val version = root.optInt("version", SyncManifest.CURRENT_VERSION)
            if (version <= 0 || version > SyncManifest.CURRENT_VERSION) return null
            val updatedAt = root.optLong("updatedAt", 0L)
            val rawEntries = root.optJSONArray("entries") ?: JSONArray()
            val parsed = mutableListOf<SyncManifestEntry>()
            val count = minOf(rawEntries.length(), MAX_MANIFEST_ENTRIES)
            for (i in 0 until count) {
                val obj = rawEntries.optJSONObject(i) ?: continue
                val rawRemotePath = obj.optString("remotePath", "").trim()
                val safeRemotePath = if (rawRemotePath.isNotBlank()) {
                    try {
                        WebDavClient.sanitizeRemotePath(rawRemotePath)
                    } catch (_: Exception) {
                        continue
                    }
                } else {
                    ""
                }

                val rawSourceUrl = obj.optString("sourceUrl", "").trim().takeIf { it.isNotBlank() }
                val safeSourceUrl = rawSourceUrl?.takeIf { EInkImageOptimizer.isSafeExternalImageUrl(it) }
                if (rawSourceUrl != null && safeSourceUrl == null) {
                    // Reject entries carrying unsafe schemes/internal SSRF URLs
                    continue
                }
                if (safeSourceUrl == null && safeRemotePath.isBlank()) continue

                val type = when (obj.optString("type", "ARTICLE").uppercase()) {
                    "BOOK" -> DocumentType.BOOK
                    "ARTICLE" -> DocumentType.ARTICLE
                    else -> continue
                }
                val renderMode = when (obj.optString("renderMode", "READER").uppercase()) {
                    "ORIGINAL_LAYOUT" -> RenderMode.ORIGINAL_LAYOUT
                    else -> RenderMode.READER
                }
                val status = when (obj.optString("status", "ACTIVE").uppercase()) {
                    "ARCHIVED" -> SyncManifestStatus.ARCHIVED
                    else -> SyncManifestStatus.ACTIVE
                }
                val title = obj.optString("title", "").trim().ifBlank { safeSourceUrl ?: safeRemotePath }.take(300)
                val author = obj.optString("author", "").trim().takeIf { it.isNotBlank() }?.take(200)
                val fileName = obj.optString("fileName", "").trim().take(255)
                val excerpt = obj.optString("excerpt", "").trim().takeIf { it.isNotBlank() }?.take(300)
                val createdAt = obj.optLong("createdAt", 0L).coerceAtLeast(0L)
                val entryUpdatedAt = obj.optLong("updatedAt", createdAt).coerceAtLeast(createdAt)
                val key = canonicalKey(safeSourceUrl, safeRemotePath)

                parsed.add(
                    SyncManifestEntry(
                        key = key,
                        type = type,
                        sourceUrl = safeSourceUrl,
                        title = title,
                        author = author,
                        renderMode = renderMode,
                        fileName = fileName,
                        remotePath = safeRemotePath,
                        excerpt = excerpt,
                        status = status,
                        createdAt = createdAt,
                        updatedAt = entryUpdatedAt
                    )
                )
            }
            SyncManifest(
                version = version,
                updatedAt = updatedAt,
                entries = parsed
            )
        } catch (_: Exception) {
            null
        }
    }

    fun toJson(manifest: SyncManifest): String {
        val root = JSONObject()
        root.put("version", manifest.version)
        root.put("updatedAt", manifest.updatedAt)
        val arr = JSONArray()
        for (entry in manifest.entries.take(MAX_MANIFEST_ENTRIES)) {
            val obj = JSONObject()
            obj.put("key", entry.key)
            obj.put("type", entry.type.name)
            entry.sourceUrl?.let { obj.put("sourceUrl", it) }
            obj.put("title", entry.title)
            entry.author?.let { obj.put("author", it) }
            obj.put("renderMode", entry.renderMode.name)
            obj.put("fileName", entry.fileName)
            obj.put("remotePath", entry.remotePath)
            entry.excerpt?.let { obj.put("excerpt", it) }
            obj.put("status", entry.status.name)
            obj.put("createdAt", entry.createdAt)
            obj.put("updatedAt", entry.updatedAt)
            arr.put(obj)
        }
        root.put("entries", arr)
        return root.toString()
    }

    fun mergeEntries(
        local: List<SyncManifestEntry>,
        remote: List<SyncManifestEntry>,
        nowMs: Long = System.currentTimeMillis()
    ): List<SyncManifestEntry> {
        val merged = linkedMapOf<String, SyncManifestEntry>()
        for (entry in remote) {
            merged[entry.key] = entry
        }
        for (entry in local) {
            val existing = merged[entry.key]
            if (existing == null) {
                merged[entry.key] = entry
            } else {
                val winner = when {
                    entry.updatedAt > existing.updatedAt -> entry
                    entry.updatedAt < existing.updatedAt -> existing
                    entry.status == SyncManifestStatus.ARCHIVED -> entry
                    else -> existing
                }
                merged[entry.key] = winner
            }
        }
        val cutoff = nowMs - TOMBSTONE_RETENTION_MS
        return merged.values
            .filter { !(it.status == SyncManifestStatus.ARCHIVED && it.updatedAt < cutoff) }
            .sortedWith(compareByDescending<SyncManifestEntry> { it.createdAt }.thenBy { it.key })
            .take(MAX_MANIFEST_ENTRIES)
    }
}
