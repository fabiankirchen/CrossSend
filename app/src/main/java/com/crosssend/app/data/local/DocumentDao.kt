package com.crosssend.app.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.crosssend.app.data.model.DocumentType
import com.crosssend.app.data.model.SyncStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {

    @Query("SELECT * FROM documents WHERE syncStatus NOT IN ('PENDING_DELETE', 'ARCHIVED') ORDER BY createdAt DESC")
    fun getAllVisibleDocuments(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE type = :type AND syncStatus NOT IN ('PENDING_DELETE', 'ARCHIVED') ORDER BY createdAt DESC")
    fun getDocumentsByType(type: DocumentType): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE syncStatus = 'ARCHIVED' ORDER BY COALESCE(archivedAt, createdAt) DESC")
    fun getArchivedDocuments(): Flow<List<DocumentEntity>>

    @Query("SELECT COUNT(*) FROM documents WHERE syncStatus = 'ARCHIVED'")
    fun getArchivedCountFlow(): Flow<Int>

    @Query("SELECT * FROM documents WHERE type = :type AND syncStatus = 'SYNCED'")
    suspend fun getSyncedDocumentsByType(type: DocumentType): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE syncStatus IN ('PENDING_UPLOAD', 'FAILED') ORDER BY createdAt ASC")
    suspend fun getPendingUploads(): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE syncStatus = 'PENDING_DELETE'")
    suspend fun getPendingDeletes(): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE remotePath = :remotePath LIMIT 1")
    suspend fun findByRemotePath(remotePath: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE fileName = :fileName LIMIT 1")
    suspend fun findByFileName(fileName: String): DocumentEntity?

    @Query("SELECT * FROM documents")
    suspend fun getAllDocuments(): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE LOWER(fileName) LIKE '%.acsm' AND remotePath != ('/' || fileName)")
    suspend fun getAcsmDocumentsNeedingRootPath(): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE sourceUrl = :sourceUrl LIMIT 1")
    suspend fun findBySourceUrl(sourceUrl: String): DocumentEntity?

    @Query("SELECT sourceUrl FROM documents WHERE sourceUrl IS NOT NULL AND syncStatus NOT IN ('PENDING_DELETE', 'FAILED', 'ARCHIVED')")
    fun getAllSourceUrls(): Flow<List<String>>

    @Query("SELECT title FROM documents WHERE syncStatus NOT IN ('PENDING_DELETE', 'FAILED', 'ARCHIVED')")
    fun getAllTitles(): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM documents WHERE type != 'SLEEP' AND syncStatus IN ('PENDING_UPLOAD', 'FAILED')")
    fun getPendingUploadCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM documents WHERE type = :type AND syncStatus IN ('PENDING_UPLOAD', 'FAILED')")
    fun getUnsyncedCountByTypeFlow(type: DocumentType): Flow<Int>

    @Query("SELECT COUNT(*) FROM documents WHERE type = :type AND syncStatus NOT IN ('PENDING_DELETE', 'ARCHIVED')")
    fun getCountByTypeFlow(type: DocumentType): Flow<Int>

    @Query("SELECT * FROM documents WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): DocumentEntity?

    @Query("SELECT * FROM documents WHERE id = :id LIMIT 1")
    fun observeById(id: Long): Flow<DocumentEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(document: DocumentEntity): Long

    @Update
    suspend fun update(document: DocumentEntity)

    @Delete
    suspend fun delete(document: DocumentEntity)

    @Query("UPDATE documents SET syncStatus = 'PENDING_DELETE' WHERE id = :id")
    suspend fun markPendingDelete(id: Long)

    @Query("UPDATE documents SET syncStatus = 'ARCHIVED', archivedAt = :archivedAt, excerpt = COALESCE(:excerpt, excerpt), fileSizeBytes = 0 WHERE id = :id")
    suspend fun markArchived(id: Long, archivedAt: Long = System.currentTimeMillis(), excerpt: String? = null)

    @Query("SELECT fileName FROM documents WHERE syncStatus = 'ARCHIVED' AND COALESCE(archivedAt, createdAt) < :cutoffTimestamp")
    suspend fun getArchivedFileNamesOlderThan(cutoffTimestamp: Long): List<String>

    @Query("DELETE FROM documents WHERE syncStatus = 'ARCHIVED' AND COALESCE(archivedAt, createdAt) < :cutoffTimestamp")
    suspend fun deleteArchivedOlderThan(cutoffTimestamp: Long)

    @Query("UPDATE documents SET syncStatus = 'SYNCED', syncError = null WHERE id = :id")
    suspend fun markSynced(id: Long)

    @Query("UPDATE documents SET syncStatus = :status, syncError = :error WHERE id = :id")
    suspend fun updateSyncStatus(id: Long, status: SyncStatus, error: String? = null)

    @Query("SELECT * FROM documents WHERE renderMode = 'ORIGINAL_LAYOUT' AND sourceUrl IS NOT NULL AND syncStatus NOT IN ('PENDING_DELETE', 'ARCHIVED')")
    suspend fun getOriginalLayoutDocuments(): List<DocumentEntity>

    @Query("UPDATE documents SET pendingDeleteRemotePath = NULL WHERE id = :id")
    suspend fun clearPendingDeleteRemotePath(id: Long)

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteById(id: Long)
}
