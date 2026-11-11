package com.crosssend.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.crosssend.app.R
import com.crosssend.app.data.local.DocumentEntity
import com.crosssend.app.data.model.DocumentType
import com.crosssend.app.data.model.SyncStatus
import com.crosssend.app.util.EpubCoverLoader

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DocumentCoverCard(
    document: DocumentEntity,
    onDelete: (DocumentEntity) -> Unit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    uploadProgress: Float? = null
) {
    val context = LocalContext.current
    var coverBitmap by remember(document.id, document.localPath, document.fileSizeBytes, document.renderMode) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(document.id, document.localPath, document.fileSizeBytes, document.renderMode) {
        coverBitmap = EpubCoverLoader.loadCover(context, document)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(6.dp)
            .clip(RoundedCornerShape(20.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(20.dp),
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
        ) {
            // Layer 1 (Background / Cover Image)
            if (coverBitmap != null) {
                Image(
                    bitmap = coverBitmap!!.asImageBitmap(),
                    contentDescription = document.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            if (document.type == DocumentType.BOOK) {
                                Brush.verticalGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.tertiaryContainer,
                                        MaterialTheme.colorScheme.surfaceContainerHighest
                                    )
                                )
                            } else {
                                Brush.verticalGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.primaryContainer,
                                        MaterialTheme.colorScheme.surfaceContainerHigh
                                    )
                                )
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (document.type == DocumentType.BOOK) {
                            Icons.AutoMirrored.Filled.MenuBook
                        } else {
                            Icons.AutoMirrored.Filled.Article
                        },
                        contentDescription = null,
                        modifier = Modifier
                            .size(48.dp)
                            .alpha(0.22f),
                        tint = if (document.type == DocumentType.BOOK) {
                            MaterialTheme.colorScheme.onTertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                }
            }

            // Layer 2 (Bottom Gradient Scrim for legibility)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.0f to Color.Transparent,
                            0.38f to Color.Transparent,
                            0.72f to Color.Black.copy(alpha = 0.62f),
                            1.0f to Color.Black.copy(alpha = 0.86f)
                        )
                    )
            )

            // Layer 3 (Top Overlay Row — Alignment.TopCenter, padding(8.dp))
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Top-Start: Sync Status pill
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.88f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val statusColors = com.crosssend.app.ui.theme.LocalStatusColors.current
                        when (document.syncStatus) {
                            SyncStatus.SYNCED -> {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = statusColors.synced
                                )
                                Text(
                                    text = stringResource(R.string.card_cover_status_synced),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = statusColors.synced
                                )
                            }
                            SyncStatus.PENDING_UPLOAD -> {
                                val isUploadingNow = uploadProgress != null
                                Icon(
                                    imageVector = Icons.Default.CloudUpload,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = if (isUploadingNow) MaterialTheme.colorScheme.primary else statusColors.pending
                                )
                                Text(
                                    text = if (isUploadingNow) {
                                        "${(uploadProgress!! * 100).toInt().coerceIn(0, 100)}%"
                                    } else {
                                        stringResource(R.string.card_cover_status_pending)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = if (isUploadingNow) MaterialTheme.colorScheme.primary else statusColors.pending
                                )
                            }
                            SyncStatus.FAILED -> {
                                Icon(
                                    imageVector = Icons.Default.Error,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Text(
                                    text = stringResource(R.string.card_status_failed),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            SyncStatus.PENDING_DELETE -> {
                                Text(
                                    text = stringResource(R.string.card_cover_status_deleting),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            SyncStatus.ARCHIVED -> {
                                Icon(
                                    imageVector = Icons.Outlined.Inventory2,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = stringResource(R.string.card_status_archived),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Top-End: Selection Checkbox or Delete Button
                if (isSelectionMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onClick() }
                    )
                } else {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.82f),
                        modifier = Modifier.size(30.dp)
                    ) {
                        IconButton(
                            onClick = { onDelete(document) },
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = stringResource(R.string.card_action_delete),
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Layer 4 (Bottom Typography Overlay — Alignment.BottomStart, padding(12.dp))
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                Text(
                    text = document.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = if (document.type == DocumentType.BOOK) FontFamily.Serif else FontFamily.Default
                )
                if (!document.author.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = document.author,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.82f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Layer 5 (Bottom-edge Material Design 3 sync progress bar)
            CardUploadProgressBar(
                uploadProgress = uploadProgress,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            )
        }
    }
}
