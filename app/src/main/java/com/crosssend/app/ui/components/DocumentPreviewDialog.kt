package com.crosssend.app.ui.components

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.crosssend.app.CrossSendApp
import com.crosssend.app.R
import com.crosssend.app.WebArticleLoginRequest
import com.crosssend.app.converter.DocumentProcessor
import com.crosssend.app.converter.EInkImageOptimizer
import com.crosssend.app.converter.XtcBuilder
import com.crosssend.app.converter.XtchInfo
import com.crosssend.app.data.local.DocumentEntity
import com.crosssend.app.data.local.DomainHostUtils
import com.crosssend.app.data.model.DocumentType
import com.crosssend.app.data.model.RenderMode
import com.crosssend.app.data.model.SyncStatus
import com.crosssend.app.util.DocumentPreviewReader
import com.crosssend.app.util.EpubCoverLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DocumentPreviewDialog(
    document: DocumentEntity,
    onDismiss: () -> Unit,
    onDelete: (DocumentEntity) -> Unit,
    modifier: Modifier = Modifier,
    onResendFromArchive: ((DocumentEntity) -> Unit)? = null
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val app = CrossSendApp.instance
    val observedDocument by remember(document.id) {
        app.database.documentDao().observeById(document.id)
    }.collectAsStateWithLifecycle(initialValue = document)
    val liveDocument = observedDocument ?: document
    val rerenderingDocId by app.rerenderingDocId.collectAsStateWithLifecycle()
    var coverBitmap by remember(liveDocument.id) { mutableStateOf<Bitmap?>(null) }
    var previewText by remember(liveDocument.id) { mutableStateOf<String?>(null) }
    var isLoadingText by remember(liveDocument.id) { mutableStateOf(true) }
    var isSwitchingMode by remember(liveDocument.id) { mutableStateOf(false) }
    val isBusyRendering = isSwitchingMode || rerenderingDocId == liveDocument.id
    var xtchInfo by remember(liveDocument.id) { mutableStateOf<XtchInfo?>(null) }
    var xtchFileModified by remember(liveDocument.id) { mutableLongStateOf(0L) }
    var fullscreenInitialPage by remember(liveDocument.id) { mutableStateOf<Int?>(null) }

    LaunchedEffect(
        liveDocument.id,
        liveDocument.localPath,
        liveDocument.fileSizeBytes,
        liveDocument.renderMode,
        isBusyRendering
    ) {
        isLoadingText = true
        coverBitmap = EpubCoverLoader.loadCover(context, liveDocument)
        val file = File(liveDocument.localPath)
        xtchFileModified = withContext(Dispatchers.IO) {
            if (file.exists()) file.lastModified() else 0L
        }
        xtchInfo = withContext(Dispatchers.IO) {
            if (liveDocument.renderMode == RenderMode.ORIGINAL_LAYOUT && file.exists()) {
                XtcBuilder.readXtchInfo(file)
            } else {
                null
            }
        }
        val readText = DocumentPreviewReader.readPreviewText(liveDocument)
        previewText = if (!readText.isNullOrBlank()) {
            readText
        } else {
            liveDocument.excerpt ?: if (liveDocument.syncStatus == SyncStatus.ARCHIVED || !file.exists()) {
                resources.getString(R.string.preview_no_text_archive)
            } else {
                null
            }
        }
        isLoadingText = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
        ),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            // Header Row: Type icon + Title on Left, Action buttons on Right
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    val icon = when {
                        liveDocument.syncStatus == SyncStatus.ARCHIVED -> Icons.Outlined.Inventory2
                        liveDocument.type == DocumentType.ARTICLE -> Icons.AutoMirrored.Filled.Article
                        liveDocument.type == DocumentType.BOOK -> Icons.AutoMirrored.Filled.MenuBook
                        liveDocument.type == DocumentType.TEXT -> Icons.AutoMirrored.Filled.Notes
                        else -> Icons.Default.Bedtime
                    }
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = if (liveDocument.syncStatus == SyncStatus.ARCHIVED) {
                            stringResource(R.string.preview_title_archive)
                        } else {
                            val isDe = LocalLocale.current.platformLocale.language == "de"
                            stringResource(R.string.preview_title_format, liveDocument.type.localizedName(isDe))
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Resend from Archive button if available
                    if (liveDocument.syncStatus == SyncStatus.ARCHIVED && !liveDocument.sourceUrl.isNullOrBlank() && onResendFromArchive != null) {
                        FilledTonalIconButton(
                            onClick = {
                                onDismiss()
                                onResendFromArchive(liveDocument)
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudUpload,
                                contentDescription = stringResource(R.string.preview_action_resend),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Share IconButton
                    FilledTonalIconButton(
                        onClick = {
                            if (!liveDocument.sourceUrl.isNullOrBlank() && (liveDocument.sourceUrl.startsWith("http://") || liveDocument.sourceUrl.startsWith("https://"))) {
                                try {
                                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_SUBJECT, liveDocument.title)
                                        putExtra(Intent.EXTRA_TEXT, "${liveDocument.title}\n${liveDocument.sourceUrl}")
                                    }
                                    context.startActivity(Intent.createChooser(sendIntent, resources.getString(R.string.preview_share_article)))
                                } catch (_: Exception) {}
                            } else if (File(liveDocument.localPath).exists()) {
                                try {
                                    val uri = androidx.core.content.FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        File(liveDocument.localPath)
                                    )
                                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = if (liveDocument.fileName.endsWith(".xtch", true) || liveDocument.fileName.endsWith(".xtc", true)) {
                                            "application/octet-stream"
                                        } else {
                                            "application/epub+zip"
                                        }
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(sendIntent, resources.getString(R.string.preview_share_document)))
                                } catch (_: Exception) {}
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Share,
                            contentDescription = stringResource(R.string.preview_action_share),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Scrollable Content
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .verticalScroll(scrollState)
            ) {
                // Header: Cover + Title / Author
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    val bmp = coverBitmap
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = liveDocument.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(width = 68.dp, height = 96.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = liveDocument.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        liveDocument.author?.let { author ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.preview_by_author, author),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // Metadata chips / info
                        val displayDate = com.crosssend.app.util.DateUtils.parseFilenameTimestamp(liveDocument.fileName)
                            ?: Date(liveDocument.createdAt)
                        val locale = LocalLocale.current.platformLocale
                        val dateFormatted = java.text.DateFormat.getDateTimeInstance(
                            java.text.DateFormat.MEDIUM,
                            java.text.DateFormat.SHORT,
                            locale
                        ).format(displayDate)
                        val sizeText = if (liveDocument.syncStatus == SyncStatus.ARCHIVED) {
                            stringResource(R.string.card_status_archived)
                        } else {
                            formatFileSize(liveDocument.fileSizeBytes, locale)
                        }
                        Text(
                            text = "$dateFormatted • $sizeText",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        // Sync state badge
                        when (liveDocument.syncStatus) {
                            SyncStatus.SYNCED -> {
                                val statusColors = com.crosssend.app.ui.theme.LocalStatusColors.current
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(13.dp),
                                        tint = statusColors.synced
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        stringResource(R.string.preview_status_synced),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = statusColors.synced
                                    )
                                }
                            }
                            SyncStatus.PENDING_UPLOAD -> {
                                val statusColors = com.crosssend.app.ui.theme.LocalStatusColors.current
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.CloudUpload,
                                        contentDescription = null,
                                        modifier = Modifier.size(13.dp),
                                        tint = statusColors.pending
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        stringResource(R.string.preview_status_pending),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = statusColors.pending
                                    )
                                }
                            }
                            SyncStatus.FAILED -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Error,
                                        contentDescription = null,
                                        modifier = Modifier.size(13.dp),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        stringResource(R.string.preview_status_failed),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            SyncStatus.PENDING_DELETE -> {
                                Text(
                                    stringResource(R.string.preview_status_deleting),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            SyncStatus.ARCHIVED -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Outlined.Inventory2,
                                        contentDescription = null,
                                        modifier = Modifier.size(13.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        stringResource(R.string.preview_status_in_archive),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Render Mode Selector (Reading View EPUB vs Original Layout XTCH)
                val safeSourceUrl = liveDocument.sourceUrl?.trim()?.let { raw ->
                    if (raw.startsWith("http://", ignoreCase = true)) {
                        DocumentProcessor.normalizeExternalHttpsUrl(raw)
                    } else {
                        raw
                    }
                }?.takeIf { EInkImageOptimizer.isSafeHttpsExternalUrl(it) }
                if (liveDocument.type == DocumentType.ARTICLE &&
                    liveDocument.syncStatus != SyncStatus.ARCHIVED &&
                    safeSourceUrl != null
                ) {
                    val domainHost = DomainHostUtils.normalizeHost(safeSourceUrl) ?: ""
                    val crosspointPrefs = remember { context.getSharedPreferences("crosspoint_prefs", Context.MODE_PRIVATE) }
                    val profile = remember {
                        EInkImageOptimizer.Companion.CrossPointHardwareProfile.fromModelString(
                            crosspointPrefs.getString("device_model", "X4")
                        )
                    }
                    Text(
                        text = stringResource(R.string.preview_render_mode_label),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            enabled = !isBusyRendering,
                            selected = liveDocument.renderMode == RenderMode.READER,
                            onClick = {
                                if (liveDocument.renderMode != RenderMode.READER) {
                                    isSwitchingMode = true
                                    app.applicationScope.launch {
                                        try {
                                            val ok = app.documentProcessor.switchDocumentRenderMode(liveDocument.id, RenderMode.READER)
                                            if (!ok) {
                                                withContext(Dispatchers.Main) {
                                                    Toast.makeText(context, resources.getString(R.string.preview_mode_switch_failed), Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        } finally {
                                            withContext(Dispatchers.Main) {
                                                isSwitchingMode = false
                                            }
                                        }
                                    }
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                        ) {
                            Text(
                                text = stringResource(R.string.preview_mode_reader),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        SegmentedButton(
                            enabled = !isBusyRendering,
                            selected = liveDocument.renderMode == RenderMode.ORIGINAL_LAYOUT,
                            onClick = {
                                if (liveDocument.renderMode != RenderMode.ORIGINAL_LAYOUT) {
                                    isSwitchingMode = true
                                    app.applicationScope.launch {
                                        try {
                                            val ok = app.documentProcessor.switchDocumentRenderMode(liveDocument.id, RenderMode.ORIGINAL_LAYOUT)
                                            if (!ok) {
                                                withContext(Dispatchers.Main) {
                                                    Toast.makeText(context, resources.getString(R.string.preview_mode_switch_failed), Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        } finally {
                                            withContext(Dispatchers.Main) {
                                                isSwitchingMode = false
                                            }
                                        }
                                    }
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                        ) {
                            Text(
                                text = stringResource(R.string.preview_mode_original),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (isBusyRendering) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            LoadingIndicator(modifier = Modifier.size(16.dp))
                            Text(
                                text = stringResource(R.string.preview_mode_switching),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else if (liveDocument.renderMode == RenderMode.ORIGINAL_LAYOUT) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(
                                R.string.preview_mode_target_resolution,
                                profile.screenWidth,
                                profile.screenHeight,
                                profile.displayName.substringBefore(" /"),
                                domainHost
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalButton(
                            enabled = !isBusyRendering,
                            onClick = {
                                app.pendingWebViewRequest.value = WebArticleLoginRequest(
                                    url = safeSourceUrl,
                                    title = liveDocument.title,
                                    rerenderOriginalLayoutDocId = if (liveDocument.renderMode == RenderMode.ORIGINAL_LAYOUT) liveDocument.id else null
                                )
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Public,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.preview_open_in_app_browser),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (liveDocument.renderMode == RenderMode.ORIGINAL_LAYOUT) {
                            OutlinedIconButton(
                                enabled = !isBusyRendering,
                                onClick = {
                                    isSwitchingMode = true
                                    app.applicationScope.launch {
                                        try {
                                            val ok = app.documentProcessor.processWebUrlOriginalLayout(
                                                url = safeSourceUrl,
                                                fallbackTitle = liveDocument.title,
                                                author = liveDocument.author,
                                                existingDoc = liveDocument
                                            )
                                            if (!ok) {
                                                withContext(Dispatchers.Main) {
                                                    Toast.makeText(context, resources.getString(R.string.preview_mode_switch_failed), Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        } finally {
                                            withContext(Dispatchers.Main) {
                                                isSwitchingMode = false
                                            }
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = stringResource(R.string.preview_rerender_xtch),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(10.dp))

                // Interactive XTCH E-Ink page preview for ORIGINAL_LAYOUT documents
                val activeXtch = xtchInfo
                if (liveDocument.renderMode == RenderMode.ORIGINAL_LAYOUT &&
                    activeXtch != null &&
                    activeXtch.pageCount > 0 &&
                    File(liveDocument.localPath).exists()
                ) {
                    XtchPagePreviewSection(
                        xtchPath = liveDocument.localPath,
                        fileModified = xtchFileModified,
                        xtchInfo = activeXtch,
                        onOpenFullscreen = { pageIndex ->
                            fullscreenInitialPage = pageIndex
                        }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Text Excerpt / Preview
                Text(
                    text = stringResource(R.string.preview_label_excerpt),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(6.dp))

                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 90.dp, max = 220.dp)
                            .padding(12.dp)
                    ) {
                        if (isLoadingText) {
                            Row(
                                modifier = Modifier.align(Alignment.Center),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                LoadingIndicator(modifier = Modifier.size(28.dp))
                                Text(stringResource(R.string.preview_loading_content), style = MaterialTheme.typography.bodySmall)
                            }
                        } else {
                            Text(
                                text = previewText ?: stringResource(R.string.preview_no_text_available),
                                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                                color = MaterialTheme.colorScheme.onSurface,
                                fontFamily = FontFamily.Serif
                            )
                        }
                    }
                }

                // Source URL link if present (clickable to open in browser)
                val sourceUrl = liveDocument.sourceUrl
                if (!sourceUrl.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, sourceUrl.toUri())
                                    context.startActivity(intent)
                                } catch (_: Exception) {}
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.OpenInBrowser,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.preview_source_format, sourceUrl),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }

    val fsPage = fullscreenInitialPage
    val activeXtchForFs = xtchInfo
    if (fsPage != null && activeXtchForFs != null && activeXtchForFs.pageCount > 0) {
        XtchFullscreenViewerDialog(
            xtchPath = liveDocument.localPath,
            fileModified = xtchFileModified,
            xtchInfo = activeXtchForFs,
            initialPage = fsPage,
            onDismiss = { fullscreenInitialPage = null }
        )
    }
}

@Composable
private fun XtchPagePreviewSection(
    xtchPath: String,
    fileModified: Long,
    xtchInfo: XtchInfo,
    onOpenFullscreen: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { xtchInfo.pageCount }
    )
    LaunchedEffect(xtchInfo.pageCount, fileModified) {
        if (xtchInfo.pageCount > 0 && pagerState.currentPage >= xtchInfo.pageCount) {
            pagerState.scrollToPage(0)
        }
    }
    val aspectRatio = remember(xtchInfo.width, xtchInfo.height) { xtchInfo.aspectRatio() }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(
                    R.string.preview_xtch_pages_title,
                    pagerState.currentPage + 1,
                    xtchInfo.pageCount
                ),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalIconButton(
                    enabled = pagerState.currentPage > 0,
                    onClick = {
                        val target = (pagerState.currentPage - 1).coerceAtLeast(0)
                        scope.launch { pagerState.animateScrollToPage(target) }
                    }
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = stringResource(R.string.preview_xtch_prev_page),
                        modifier = Modifier.size(18.dp)
                    )
                }
                FilledTonalIconButton(
                    enabled = pagerState.currentPage < xtchInfo.pageCount - 1,
                    onClick = {
                        val target = (pagerState.currentPage + 1).coerceAtMost(xtchInfo.pageCount - 1)
                        scope.launch { pagerState.animateScrollToPage(target) }
                    }
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.preview_xtch_next_page),
                        modifier = Modifier.size(18.dp)
                    )
                }
                FilledTonalIconButton(
                    onClick = { onOpenFullscreen(pagerState.currentPage) }
                ) {
                    Icon(
                        imageVector = Icons.Default.Fullscreen,
                        contentDescription = stringResource(R.string.preview_xtch_fullscreen),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.45f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
            ) { pageIndex ->
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        shadowElevation = 2.dp,
                        modifier = Modifier
                            .width(204.dp)
                            .aspectRatio(aspectRatio)
                            .clickable { onOpenFullscreen(pageIndex) }
                    ) {
                        XtchDecodedPageImage(
                            xtchPath = xtchPath,
                            fileModified = fileModified,
                            pageIndex = pageIndex,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun XtchFullscreenViewerDialog(
    xtchPath: String,
    fileModified: Long,
    xtchInfo: XtchInfo,
    initialPage: Int,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(
        initialPage = initialPage.coerceIn(0, (xtchInfo.pageCount - 1).coerceAtLeast(0)),
        pageCount = { xtchInfo.pageCount }
    )
    val aspectRatio = remember(xtchInfo.width, xtchInfo.height) { xtchInfo.aspectRatio() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.94f)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(16.dp)
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 72.dp)
                ) { pageIndex ->
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLowest,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(aspectRatio)
                        ) {
                            XtchDecodedPageImage(
                                xtchPath = xtchPath,
                                fileModified = fileModified,
                                pageIndex = pageIndex,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }

                // Top-right close button
                FilledTonalIconButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.webview_close)
                    )
                }

                // Bottom M3 floating page switcher
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 6.dp,
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(
                            enabled = pagerState.currentPage > 0,
                            onClick = {
                                val target = (pagerState.currentPage - 1).coerceAtLeast(0)
                                scope.launch { pagerState.animateScrollToPage(target) }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = stringResource(R.string.preview_xtch_prev_page)
                            )
                        }
                        Text(
                            text = "${pagerState.currentPage + 1} / ${xtchInfo.pageCount}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                        IconButton(
                            enabled = pagerState.currentPage < xtchInfo.pageCount - 1,
                            onClick = {
                                val target = (pagerState.currentPage + 1).coerceAtMost(xtchInfo.pageCount - 1)
                                scope.launch { pagerState.animateScrollToPage(target) }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = stringResource(R.string.preview_xtch_next_page)
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun XtchDecodedPageImage(
    xtchPath: String,
    fileModified: Long,
    pageIndex: Int,
    modifier: Modifier = Modifier
) {
    val pageBitmap by produceState<Bitmap?>(
        initialValue = null,
        key1 = xtchPath,
        key2 = fileModified,
        key3 = pageIndex
    ) {
        value = withContext(Dispatchers.IO) {
            XtcBuilder.decodePageBitmap(File(xtchPath), pageIndex)
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        val bmp = pageBitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = stringResource(R.string.preview_xtch_pages_title, pageIndex + 1, pageIndex + 1),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            LoadingIndicator(modifier = Modifier.size(36.dp))
        }
    }
}

private fun XtchInfo.aspectRatio(): Float {
    return if (width > 0 && height > 0) width.toFloat() / height.toFloat() else 480f / 800f
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
