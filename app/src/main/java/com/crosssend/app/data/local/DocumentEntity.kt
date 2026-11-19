package com.crosssend.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.crosssend.app.data.model.DocumentType
import com.crosssend.app.data.model.RenderMode
import com.crosssend.app.data.model.SyncStatus

@Entity(
    tableName = "documents",
    indices = [
        Index("syncStatus"),
        Index("sourceUrl"),
        Index("fileName"),
        Index("remotePath"),
        Index("type")
    ]
)
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val author: String? = null,
    val sourceUrl: String? = null,
    val type: DocumentType,
    val localPath: String,
    val fileName: String,
    val remotePath: String,
    val fileSizeBytes: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val syncStatus: SyncStatus = SyncStatus.PENDING_UPLOAD,
    val syncError: String? = null,
    val archivedAt: Long? = null,
    val excerpt: String? = null,
    val renderMode: RenderMode = RenderMode.READER,
    val pendingDeleteRemotePath: String? = null
)
