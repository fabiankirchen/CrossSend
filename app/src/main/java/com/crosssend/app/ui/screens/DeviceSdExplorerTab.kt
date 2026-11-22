@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.crosssend.app.ui.screens

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.crosssend.app.util.SafeLogger
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.crosssend.app.CrossSendApp
import com.crosssend.app.R
import com.crosssend.app.converter.DocumentProcessor
import com.crosssend.app.network.RemoteDavItem
import com.crosssend.app.network.WifiConnectionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale

internal const val MAX_SD_UPLOAD_BYTES = 150L * 1024 * 1024
private const val TAG = "DeviceSdExplorerTab"

internal sealed interface SdDownloadOutcome {
    data class Saved(val fileName: String) : SdDownloadOutcome
    data object SaveFailed : SdDownloadOutcome
    data class DownloadFailed(val fileName: String) : SdDownloadOutcome
    data class Error(val message: String) : SdDownloadOutcome
}

internal fun parentSdCardPath(currentPath: String): String {
    val parent = currentPath.trimEnd('/').substringBeforeLast('/', "")
    return if (parent.isBlank()) "/" else parent
}

internal suspend fun uploadUriToSdCard(
    context: Context,
    app: CrossSendApp,
    uri: Uri,
    currentDirectory: String
): Result<Pair<String, Boolean>> {
    if (!DocumentProcessor.isSafeSharedUri(context, uri)) {
        return Result.failure(SecurityException("Unsafe shared URI"))
    }
    if (!isSafeSdCardPath(currentDirectory)) {
        return Result.failure(SecurityException("Invalid target directory"))
    }
    return try {
        var uploadFileName = "upload_${System.currentTimeMillis()}"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIdx != -1) {
                    uploadFileName = cursor.getString(nameIdx)
                }
            }
        }
        uploadFileName = uploadFileName.substringAfterLast('/').substringAfterLast('\\').replace("..", "_")
        val safeFileName = File(uploadFileName).name.ifBlank { "upload_${System.currentTimeMillis()}.bin" }
        val uploadExt = safeFileName.substringAfterLast('.', "bin").takeIf { it.isNotBlank() } ?: "bin"

        val tempFile = ensureInsideDirectory(
            context.cacheDir,
            File.createTempFile("sd_upload_", ".$uploadExt", context.cacheDir)
        )
        val success = try {
            withContext(Dispatchers.IO) {
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: throw IOException("Cannot open selected file")
                inputStream.use { input ->
                    tempFile.outputStream().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var totalRead = 0L
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            totalRead += read
                            if (totalRead > MAX_SD_UPLOAD_BYTES) {
                                throw IOException("File exceeds 150 MB upload limit")
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }
            }

            val remoteTarget = "${currentDirectory.trimEnd('/')}/$safeFileName"
            withContext(Dispatchers.IO) {
                app.webDavClient.uploadFile(tempFile, remoteTarget)
            }
        } finally {
            tempFile.delete()
        }
        Result.success(safeFileName to success)
    } catch (e: Exception) {
        Result.failure(e)
    }
}

internal suspend fun downloadItemFromSdCard(
    context: Context,
    app: CrossSendApp,
    item: RemoteDavItem
): SdDownloadOutcome {
    if (!isSafeSdCardPath(item.path)) {
        return SdDownloadOutcome.Error("Invalid SD card path")
    }
    val safeName = File(item.name).name.ifBlank { "download.bin" }
    val downloadExt = safeName.substringAfterLast('.', "bin").takeIf { it.isNotBlank() } ?: "bin"
    return try {
        val tempFile = ensureInsideDirectory(
            context.cacheDir,
            File.createTempFile("sd_transfer_", ".$downloadExt", context.cacheDir)
        )
        try {
            val success = withContext(Dispatchers.IO) {
                app.webDavClient.downloadFile(item.path, tempFile)
            }
            if (success && tempFile.exists()) {
                val saved = withContext(Dispatchers.IO) {
                    saveToPublicDownloads(context, safeName, tempFile)
                }
                if (saved) SdDownloadOutcome.Saved(safeName) else SdDownloadOutcome.SaveFailed
            } else {
                SdDownloadOutcome.DownloadFailed(safeName)
            }
        } finally {
            tempFile.delete()
        }
    } catch (e: Exception) {
        SdDownloadOutcome.Error(e.message.orEmpty())
    }
}

internal suspend fun deleteItemFromSdCard(
    app: CrossSendApp,
    item: RemoteDavItem
): Boolean {
    if (!isSafeSdCardPath(item.path)) return false
    return withContext(Dispatchers.IO) {
        app.webDavClient.deleteFile(item.path)
    }
}

/**
 * Displays the CrossPoint reader SD card file explorer tab, including connection status,
 * folder navigation, file sizes, download, and delete actions.
 */
@Composable
internal fun FileExplorerTab(
    currentPath: String,
    items: List<RemoteDavItem>,
    isLoading: Boolean,
    isDownloading: Boolean,
    isConnected: Boolean,
    connectionState: WifiConnectionState,
    onNavigateTo: (String) -> Unit,
    onDownloadRequested: (RemoteDavItem) -> Unit,
    onDeleteRequested: (RemoteDavItem) -> Unit,
    onConnect: () -> Unit,
    onDirectHotspotConnect: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    fileLoadError: String? = null,
    onRetry: () -> Unit = {},
    onChangeAddress: () -> Unit = {}
) {
    if (!isConnected) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            CrossPointConnectionCard(
                connectionState = connectionState,
                onConnect = onConnect,
                onDirectHotspotConnect = onDirectHotspotConnect,
                onChangeAddress = onChangeAddress,
                onCancel = onCancel
            )
        }
        return
    }

    val locale = LocalLocale.current.platformLocale
    val sortedItems = remember(items) {
        items
            .distinctBy { it.path }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }))
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val dirCount = sortedItems.count { it.isDirectory }
            val fileCount = sortedItems.count { !it.isDirectory }
            Text(
                text = buildString {
                    if (dirCount > 0) append(pluralStringResource(R.plurals.dev_files_summary_folders, dirCount, dirCount))
                    if (dirCount > 0 && fileCount > 0) append(" · ")
                    if (fileCount > 0) append(pluralStringResource(R.plurals.dev_files_summary_files, fileCount, fileCount))
                    if (dirCount == 0 && fileCount == 0 && !isLoading && fileLoadError == null) {
                        append(stringResource(R.string.dev_files_summary_empty))
                    }
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    LoadingIndicator(modifier = Modifier.size(36.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.dev_files_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else if (fileLoadError != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Wifi,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.dev_connection_failed),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.dev_connection_failed_desc, fileLoadError),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                onClick = onChangeAddress,
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                    contentColor = MaterialTheme.colorScheme.onSurface
                                )
                            ) {
                                Text(stringResource(R.string.dev_btn_adjust_address))
                            }
                            FilledTonalButton(
                                onClick = onRetry,
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.onErrorContainer,
                                    contentColor = MaterialTheme.colorScheme.errorContainer
                                )
                            ) {
                                Text(stringResource(R.string.dev_btn_retry))
                            }
                        }
                    }
                }
            }
        } else if (sortedItems.isEmpty()) {
            val isSubfolder = currentPath != "/" && currentPath.isNotBlank()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (isSubfolder) {
                    ParentFolderCard(
                        onClick = { onNavigateTo(parentSdCardPath(currentPath)) },
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = if (isSubfolder) stringResource(R.string.dev_folder_empty_title) else stringResource(R.string.dev_no_files_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.dev_folder_empty_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            val isSubfolder = currentPath != "/" && currentPath.isNotBlank()
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
            ) {
                if (isSubfolder) {
                    item(key = "__parent__") {
                        ParentFolderCard(
                            onClick = { onNavigateTo(parentSdCardPath(currentPath)) },
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
                items(sortedItems, key = { it.path }) { item ->
                    SdCardItemRow(
                        item = item,
                        currentPath = currentPath,
                        isDownloading = isDownloading,
                        locale = locale,
                        onNavigateTo = onNavigateTo,
                        onDownloadRequested = onDownloadRequested,
                        onDeleteRequested = onDeleteRequested
                    )
                }
            }
        }
    }
}

@Composable
private fun ParentFolderCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "..",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.dev_parent_folder),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SdCardItemRow(
    item: RemoteDavItem,
    currentPath: String,
    isDownloading: Boolean,
    locale: Locale,
    onNavigateTo: (String) -> Unit,
    onDownloadRequested: (RemoteDavItem) -> Unit,
    onDeleteRequested: (RemoteDavItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val isPluginEntry = item.isDirectory && (item.name.equals("plugins", true) || currentPath.contains("plugins", true))
    val isBookEntry = !item.isDirectory && (item.name.endsWith(".epub", true) || item.name.endsWith(".pdf", true))
    val isImageEntry = !item.isDirectory && (item.name.endsWith(".bmp", true) || item.name.endsWith(".jpg", true) || item.name.endsWith(".png", true))
    val canNavigateDir = item.isDirectory && isSafeSdCardPath(item.path)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .padding(vertical = 4.dp)
            .then(
                if (canNavigateDir) {
                    Modifier.clickable { onNavigateTo(item.path) }
                } else {
                    Modifier
                }
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        when {
                            isPluginEntry -> MaterialTheme.colorScheme.tertiaryContainer
                            item.isDirectory -> MaterialTheme.colorScheme.primaryContainer
                            isBookEntry -> MaterialTheme.colorScheme.tertiaryContainer
                            isImageEntry -> MaterialTheme.colorScheme.secondaryContainer
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when {
                        isPluginEntry -> Icons.Default.Extension
                        item.isDirectory -> Icons.Default.Folder
                        isBookEntry -> Icons.AutoMirrored.Filled.MenuBook
                        isImageEntry -> Icons.Default.Image
                        else -> Icons.Default.Description
                    },
                    contentDescription = null,
                    tint = when {
                        isPluginEntry -> MaterialTheme.colorScheme.onTertiaryContainer
                        item.isDirectory -> MaterialTheme.colorScheme.onPrimaryContainer
                        isBookEntry -> MaterialTheme.colorScheme.onTertiaryContainer
                        isImageEntry -> MaterialTheme.colorScheme.onSecondaryContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (item.isDirectory) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (item.isDirectory) {
                        stringResource(R.string.dev_item_folder)
                    } else {
                        formatFileSize(item.sizeBytes, locale)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!item.isDirectory) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { onDownloadRequested(item) },
                        enabled = !isDownloading,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = stringResource(R.string.dev_action_download),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    IconButton(
                        onClick = { onDeleteRequested(item) },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = stringResource(R.string.dev_action_delete),
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

internal fun saveToPublicDownloads(context: Context, fileName: String, file: File): Boolean {
    return try {
        val safeName = File(fileName).name
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .ifBlank { "download.bin" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                put(
                    MediaStore.MediaColumns.MIME_TYPE,
                    when {
                        safeName.endsWith(".epub", true) -> "application/epub+zip"
                        safeName.endsWith(".pdf", true) -> "application/pdf"
                        safeName.endsWith(".bmp", true) -> "image/bmp"
                        safeName.endsWith(".png", true) -> "image/png"
                        safeName.endsWith(".jpg", true) || safeName.endsWith(".jpeg", true) -> "image/jpeg"
                        safeName.endsWith(".txt", true) -> "text/plain"
                        else -> "application/octet-stream"
                    }
                )
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/CrossPoint")
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return false
            try {
                val outputStream = resolver.openOutputStream(uri)
                if (outputStream == null) {
                    resolver.delete(uri, null, null)
                    return false
                }
                outputStream.use { output ->
                    file.inputStream().use { input -> input.copyTo(output) }
                }
                true
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            val baseDownloads = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            val targetDir = File(baseDownloads, "CrossPoint").apply { mkdirs() }
            val dest = ensureInsideDirectory(targetDir, File(targetDir, safeName))
            file.copyTo(dest, overwrite = true)
            true
        }
    } catch (e: Exception) {
        SafeLogger.e(TAG, "Failed to save downloaded file to public Downloads", e)
        false
    }
}

internal fun formatFileSize(bytes: Long, locale: Locale = Locale.getDefault()): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(locale, "%.1f MB", bytes / (1024.0 * 1024.0))
    }
}

internal fun isSafeSdCardPath(path: String): Boolean {
    if (path.isBlank() || path.contains('\u0000')) return false
    val normalized = path.replace('\\', '/')
    if (!normalized.startsWith("/")) return false
    val segments = normalized.split('/')
    if (segments.any { it == ".." }) return false
    return true
}

internal fun ensureInsideDirectory(dir: File, file: File): File {
    val dirCanonical = dir.canonicalFile
    val fileCanonical = file.canonicalFile
    if (!fileCanonical.path.startsWith(dirCanonical.path + File.separator)) {
        throw SecurityException("Invalid file path: $file outside of $dir")
    }
    return file
}
