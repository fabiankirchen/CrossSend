package com.crosssend.app.sync

import com.crosssend.app.data.local.DocumentEntity
import com.crosssend.app.data.model.DocumentType
import com.crosssend.app.data.model.RenderMode
import com.crosssend.app.data.model.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncManifestCodecTest {

    @Test
    fun `toJson and parse roundtrip preserves entries`() {
        val entry = SyncManifestEntry(
            key = "https://example.com/post-1",
            type = DocumentType.ARTICLE,
            sourceUrl = "https://example.com/post-1",
            title = "Example Post",
            author = "example.com",
            renderMode = RenderMode.ORIGINAL_LAYOUT,
            fileName = "20261115_120000_Example_Post.xtch",
            remotePath = "/crosssend/articles/20261115_120000_Example_Post.xtch",
            excerpt = "Short preview",
            status = SyncManifestStatus.ACTIVE,
            createdAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_000_000L
        )
        val json = SyncManifestCodec.toJson(
            SyncManifest(version = 1, updatedAt = 1_700_000_000_000L, entries = listOf(entry))
        )
        val parsed = SyncManifestCodec.parse(json)
        assertNotNull(parsed)
        assertEquals(1, parsed!!.entries.size)
        assertEquals(entry, parsed.entries.first())
    }

    @Test
    fun `parse rejects unsafe urls and path traversal`() {
        val unsafeJson = """
            {
              "version": 1,
              "updatedAt": 1700000000000,
              "entries": [
                {
                  "type": "ARTICLE",
                  "sourceUrl": "http://127.0.0.1/admin",
                  "title": "SSRF",
                  "remotePath": "/crosssend/articles/ssrf.epub",
                  "status": "ACTIVE",
                  "createdAt": 1000,
                  "updatedAt": 1000
                },
                {
                  "type": "ARTICLE",
                  "sourceUrl": "javascript:alert(1)",
                  "title": "XSS",
                  "remotePath": "/crosssend/articles/xss.epub",
                  "status": "ACTIVE",
                  "createdAt": 1000,
                  "updatedAt": 1000
                },
                {
                  "type": "ARTICLE",
                  "sourceUrl": "https://example.com/good",
                  "title": "Good",
                  "remotePath": "/crosssend/articles/../../etc/passwd",
                  "status": "ACTIVE",
                  "createdAt": 2000,
                  "updatedAt": 2000
                }
              ]
            }
        """.trimIndent()

        val parsed = SyncManifestCodec.parse(unsafeJson)
        assertNotNull(parsed)
        assertEquals(1, parsed!!.entries.size)
        assertEquals("https://example.com/good", parsed.entries.first().sourceUrl)
        assertTrue(!parsed.entries.first().remotePath.contains(".."))
    }

    @Test
    fun `mergeEntries prefers newer tombstone and prunes expired tombstones`() {
        val now = 200L * 24L * 60L * 60L * 1000L
        val localActive = SyncManifestEntry(
            key = "https://example.com/a",
            type = DocumentType.ARTICLE,
            sourceUrl = "https://example.com/a",
            title = "A",
            author = null,
            renderMode = RenderMode.READER,
            fileName = "a.epub",
            remotePath = "/crosssend/articles/a.epub",
            excerpt = null,
            status = SyncManifestStatus.ACTIVE,
            createdAt = now - 5_000L,
            updatedAt = now - 5_000L
        )
        val remoteArchivedNewer = localActive.copy(
            status = SyncManifestStatus.ARCHIVED,
            updatedAt = now - 1_000L
        )
        val expiredTombstone = localActive.copy(
            key = "https://example.com/old",
            sourceUrl = "https://example.com/old",
            status = SyncManifestStatus.ARCHIVED,
            createdAt = 1_000L,
            updatedAt = 1_000L
        )

        val merged = SyncManifestCodec.mergeEntries(
            local = listOf(localActive),
            remote = listOf(remoteArchivedNewer, expiredTombstone),
            nowMs = now
        )
        assertEquals(1, merged.size)
        assertEquals(SyncManifestStatus.ARCHIVED, merged.first().status)
        assertEquals("https://example.com/a", merged.first().key)
    }

    @Test
    fun `fromDocumentEntity ignores sleep screens and maps archived status`() {
        val sleepDoc = DocumentEntity(
            id = 1,
            title = "Sleep",
            type = DocumentType.SLEEP,
            localPath = "/tmp/sleep.bmp",
            fileName = "sleep.bmp",
            remotePath = "/sleep/sleep.bmp",
            fileSizeBytes = 100
        )
        assertNull(SyncManifestCodec.fromDocumentEntity(sleepDoc))

        val archivedArticle = DocumentEntity(
            id = 2,
            title = "Article",
            sourceUrl = "https://example.com/read",
            type = DocumentType.ARTICLE,
            localPath = "/tmp/a.xtch",
            fileName = "a.xtch",
            remotePath = "/crosssend/articles/a.xtch",
            fileSizeBytes = 0,
            createdAt = 1000L,
            archivedAt = 2000L,
            syncStatus = SyncStatus.ARCHIVED,
            renderMode = RenderMode.ORIGINAL_LAYOUT
        )
        val entry = SyncManifestCodec.fromDocumentEntity(archivedArticle)
        assertNotNull(entry)
        assertEquals(SyncManifestStatus.ARCHIVED, entry!!.status)
        assertEquals(2000L, entry.updatedAt)
        assertEquals(RenderMode.ORIGINAL_LAYOUT, entry.renderMode)
    }

    @Test
    fun `reactivated archived article overrides older remote tombstone during merge`() {
        val remoteArchived = SyncManifestEntry(
            key = "https://example.com/re-send",
            type = DocumentType.ARTICLE,
            sourceUrl = "https://example.com/re-send",
            title = "Archived Article",
            author = null,
            renderMode = RenderMode.READER,
            fileName = "20261101_1200_Archived_Article.epub",
            remotePath = "/crosssend/articles/20261101_1200_Archived_Article.epub",
            excerpt = null,
            status = SyncManifestStatus.ARCHIVED,
            createdAt = 1000L,
            updatedAt = 2000L
        )
        val reactivatedEntity = DocumentEntity(
            id = 5,
            title = "Archived Article",
            sourceUrl = "https://example.com/re-send",
            type = DocumentType.ARTICLE,
            localPath = "/tmp/20261101_1200_Archived_Article.epub",
            fileName = "20261101_1200_Archived_Article.epub",
            remotePath = "/crosssend/articles/20261101_1200_Archived_Article.epub",
            fileSizeBytes = 1024L,
            createdAt = 3000L,
            archivedAt = null,
            syncStatus = SyncStatus.PENDING_UPLOAD,
            renderMode = RenderMode.READER
        )

        val localEntry = SyncManifestCodec.fromDocumentEntity(reactivatedEntity)
        assertNotNull(localEntry)
        assertEquals(SyncManifestStatus.ACTIVE, localEntry!!.status)
        assertEquals(3000L, localEntry.updatedAt)

        val merged = SyncManifestCodec.mergeEntries(
            local = listOf(localEntry),
            remote = listOf(remoteArchived),
            nowMs = 4000L
        )
        assertEquals(1, merged.size)
        assertEquals(SyncManifestStatus.ACTIVE, merged.first().status)
        assertEquals(3000L, merged.first().updatedAt)
    }
}
