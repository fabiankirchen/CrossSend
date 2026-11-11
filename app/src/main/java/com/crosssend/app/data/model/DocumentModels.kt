package com.crosssend.app.data.model

enum class DocumentType(val subfolder: String, val displayName: String) {
    ARTICLE("articles", "Articles"),
    BOOK("books", "Books"),
    TEXT("texts", "Notes"),
    SLEEP("sleep", "Sleep Screens");

    fun localizedName(isDe: Boolean): String = when (this) {
        ARTICLE -> if (isDe) "Artikel" else "Article"
        BOOK -> if (isDe) "Buch" else "Book"
        TEXT -> if (isDe) "Notiz" else "Note"
        SLEEP -> if (isDe) "Sleepscreen" else "Sleep Screen"
    }
}

enum class RenderMode {
    READER,
    ORIGINAL_LAYOUT
}

enum class SyncStatus {
    PENDING_UPLOAD,
    SYNCED,
    PENDING_DELETE,
    FAILED,
    ARCHIVED
}

sealed class SyncResult {
    data class Success(val uploadedCount: Int, val deletedCount: Int) : SyncResult()
    data class Error(val message: String) : SyncResult()
}
