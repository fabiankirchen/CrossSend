package com.crosssend.app.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.crosssend.app.R
import com.crosssend.app.util.EpubCoverLoader
import com.crosssend.app.data.local.DocumentEntity
import com.crosssend.app.data.model.DocumentType
import com.crosssend.app.data.model.SyncStatus
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DocumentCard(
    document: DocumentEntity,
    onDelete: (DocumentEntity) -> Unit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    uploadProgress: Float? = null
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(16.dp),
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
            // Type icon or Cover thumbnail
            val context = LocalContext.current
            var coverBitmap by remember(document.id, document.localPath, document.fileSizeBytes, document.renderMode) {
                mutableStateOf<Bitmap?>(null)
            }
            LaunchedEffect(document.id, document.localPath, document.fileSizeBytes, document.renderMode) {
                coverBitmap = EpubCoverLoader.loadCover(context, document)
            }

            val bmp = coverBitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = document.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            } else {
                val typeIcon = when (document.type) {
                    DocumentType.ARTICLE -> Icons.AutoMirrored.Filled.Article
                    DocumentType.BOOK -> Icons.AutoMirrored.Filled.MenuBook
                    DocumentType.TEXT -> Icons.AutoMirrored.Filled.Notes
                    DocumentType.SLEEP -> Icons.Default.Bedtime
                }
                Icon(
                    imageVector = typeIcon,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Details
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = document.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val sizeFormatted = formatFileSize(document.fileSizeBytes, LocalLocale.current.platformLocale)
                    Text(
                        text = sizeFormatted,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Sync badge
                    when (document.syncStatus) {
                        SyncStatus.SYNCED -> {
                            val statusColors = com.crosssend.app.ui.theme.LocalStatusColors.current
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = statusColors.synced
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(
                                    text = stringResource(R.string.card_status_synced),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = statusColors.synced
                                )
                            }
                        }
                        SyncStatus.PENDING_UPLOAD -> {
                            val statusColors = com.crosssend.app.ui.theme.LocalStatusColors.current
                            val isUploadingNow = uploadProgress != null
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.CloudUpload,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = if (isUploadingNow) MaterialTheme.colorScheme.primary else statusColors.pending
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(
                                    text = if (isUploadingNow) {
                                        stringResource(R.string.card_status_uploading, ((uploadProgress ?: 0f) * 100).toInt().coerceIn(0, 100))
                                    } else {
                                        stringResource(R.string.card_status_pending)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isUploadingNow) MaterialTheme.colorScheme.primary else statusColors.pending
                                )
                            }
                        }
                        SyncStatus.FAILED -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Error,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(
                                    text = stringResource(R.string.card_status_failed),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        SyncStatus.PENDING_DELETE -> {
                            Text(
                                text = stringResource(R.string.card_status_deleting),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        SyncStatus.ARCHIVED -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Outlined.Inventory2,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(
                                    text = stringResource(R.string.card_status_archived),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Trailing action: Checkbox in selection mode or Delete icon in normal mode
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() }
                )
            } else {
                IconButton(onClick = { onDelete(document) }) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.card_action_delete),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            }
            CardUploadProgressBar(uploadProgress = uploadProgress)
        }
    }
}

/**
 * Slim Material Design 3 Expressive wavy progress indicator docked cleanly to the bottom edge of a card while uploading.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun CardUploadProgressBar(
    uploadProgress: Float?,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = uploadProgress != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        val raw = (uploadProgress ?: 0f).coerceIn(0f, 1f)
        if (raw > 0.01f) {
            val animated by animateFloatAsState(
                targetValue = raw,
                animationSpec = WavyProgressIndicatorDefaults.ProgressAnimationSpec,
                label = "card_upload_progress"
            )
            LinearWavyProgressIndicator(
                progress = { animated },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                stopSize = 0.dp
            )
        } else {
            LinearWavyProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
        }
    }
}

private fun formatFileSize(bytes: Long, locale: Locale): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return if (mb >= 1.0) {
        String.format(locale, "%.1f MB", mb)
    } else {
        String.format(locale, "%.0f KB", kb)
    }
}
