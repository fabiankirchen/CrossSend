package com.crosssend.app.ui.share

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.crosssend.app.R
import com.crosssend.app.data.model.DocumentType
import com.crosssend.app.data.model.RenderMode
import com.crosssend.app.data.settings.AppSettings
import com.crosssend.app.data.settings.ShareFlowMode
import com.crosssend.app.network.WifiConnectionState
import com.crosssend.app.ui.theme.LocalStatusColors
import kotlinx.coroutines.delay

data class ShareSheetPayload(
    val displayTitle: String,
    val subtitle: String?,
    val shareUrl: String?,
    val domainHost: String?,
    val suggestedDocType: DocumentType,
    val fileCount: Int = 0
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ShareBottomSheet(
    payload: ShareSheetPayload,
    settings: AppSettings,
    connectionState: WifiConnectionState,
    initialRenderMode: RenderMode,
    modifier: Modifier = Modifier,
    hasExistingDomainRule: Boolean = false,
    onOpenInAppBrowser: (() -> Unit)? = null,
    onConfirm: (renderMode: RenderMode?, targetFolder: String, rememberForDomain: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val isUrlShare = !payload.shareUrl.isNullOrBlank()
    val haptics = LocalHapticFeedback.current
    val statusColors = LocalStatusColors.current

    val isConnected = connectionState is WifiConnectionState.ConnectedToCrossPointAp ||
        connectionState is WifiConnectionState.ConnectedOnLocalNetwork
    val isSearching = connectionState is WifiConnectionState.ConnectingToAp ||
        connectionState is WifiConnectionState.SearchingOnLan

    val folderChoices = remember(settings, payload.suggestedDocType) {
        buildList {
            add(settings.defaultFolderFor(payload.suggestedDocType))
            add(settings.articlesFolder)
            add(settings.booksFolder)
            add(settings.textsFolder)
        }.distinct()
    }

    var selectedRenderMode by remember(initialRenderMode) { mutableStateOf(initialRenderMode) }
    var selectedFolder by remember(payload.suggestedDocType, settings) {
        mutableStateOf(settings.defaultFolderFor(payload.suggestedDocType))
    }
    var showFolderChips by remember { mutableStateOf(false) }
    var rememberDomainRule by remember(hasExistingDomainRule) { mutableStateOf(hasExistingDomainRule) }
    var countdownActive by remember(settings.shareFlowMode) {
        mutableStateOf(settings.shareFlowMode == ShareFlowMode.AUTO_SEND_COUNTDOWN)
    }
    var countdownProgress by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(countdownActive) {
        if (!countdownActive) return@LaunchedEffect
        val totalSteps = 20
        for (step in 1..totalSteps) {
            delay(100L)
            countdownProgress = 1f - (step.toFloat() / totalSteps)
        }
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        onConfirm(
            if (isUrlShare) selectedRenderMode else null,
            selectedFolder,
            rememberDomainRule
        )
    }

    val cancelCountdown = {
        countdownActive = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
        ),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header row: Connection status pill + title
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.share_target_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape = CircleShape,
                    color = when {
                        isConnected -> statusColors.syncedContainer
                        isSearching -> MaterialTheme.colorScheme.secondaryContainer
                        else -> statusColors.pendingContainer
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        when {
                            isConnected -> Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = statusColors.synced,
                                modifier = Modifier.size(14.dp)
                            )
                            isSearching -> LoadingIndicator(
                                modifier = Modifier.size(14.dp),
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            else -> Icon(
                                imageVector = Icons.Default.CloudUpload,
                                contentDescription = null,
                                tint = statusColors.pending,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        Text(
                            text = when (connectionState) {
                                is WifiConnectionState.ConnectedToCrossPointAp ->
                                    stringResource(R.string.share_status_connected_ap)
                                is WifiConnectionState.ConnectedOnLocalNetwork ->
                                    stringResource(R.string.share_status_connected_lan)
                                is WifiConnectionState.ConnectingToAp,
                                is WifiConnectionState.SearchingOnLan ->
                                    stringResource(R.string.share_status_connecting)
                                else -> stringResource(R.string.share_status_offline_queue)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = when {
                                isConnected -> statusColors.onSyncedContainer
                                isSearching -> MaterialTheme.colorScheme.onSecondaryContainer
                                else -> statusColors.onPendingContainer
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Content preview card
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val typeIcon = when {
                        isUrlShare -> Icons.Default.Language
                        payload.suggestedDocType == DocumentType.BOOK -> Icons.AutoMirrored.Filled.MenuBook
                        payload.suggestedDocType == DocumentType.TEXT -> Icons.AutoMirrored.Filled.Notes
                        else -> Icons.AutoMirrored.Filled.Article
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(42.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = typeIcon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = payload.displayTitle,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        val subtitle = payload.subtitle
                        if (!subtitle.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // Format selector + per-domain preference (URL shares only)
            if (isUrlShare) {
                val modes = listOf(RenderMode.READER, RenderMode.ORIGINAL_LAYOUT)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    modes.forEachIndexed { index, mode ->
                        val selected = selectedRenderMode == mode
                        SegmentedButton(
                            selected = selected,
                            onClick = {
                                cancelCountdown()
                                selectedRenderMode = mode
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size)
                        ) {
                            Text(
                                text = stringResource(
                                    if (mode == RenderMode.ORIGINAL_LAYOUT) {
                                        R.string.preview_mode_original
                                    } else {
                                        R.string.preview_mode_reader
                                    }
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                val domainHost = payload.domainHost
                if (!domainHost.isNullOrBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                cancelCountdown()
                                rememberDomainRule = !rememberDomainRule
                            }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.share_remember_domain_label, domainHost),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp)
                        )
                        Switch(
                            checked = rememberDomainRule,
                            onCheckedChange = {
                                cancelCountdown()
                                rememberDomainRule = it
                            }
                        )
                    }
                }
            }

            // Clickable target folder pill + quick switcher chips
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        cancelCountdown()
                        if (folderChoices.size > 1) {
                            showFolderChips = !showFolderChips
                        }
                    }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.share_target_folder_label, selectedFolder),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            AnimatedVisibility(visible = showFolderChips && folderChoices.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    folderChoices.forEach { folder ->
                        val isSelected = selectedFolder == folder
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                cancelCountdown()
                                selectedFolder = folder
                            },
                            label = { Text(folder, style = MaterialTheme.typography.labelMedium) },
                            leadingIcon = if (isSelected) {
                                {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            } else null
                        )
                    }
                }
            }

            // Optional 2s auto-send countdown banner
            AnimatedVisibility(visible = countdownActive) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.share_countdown_sending),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                        TextButton(onClick = cancelCountdown) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.share_countdown_stop))
                        }
                    }
                    LinearWavyProgressIndicator(
                        progress = { countdownProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Primary action row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isUrlShare && onOpenInAppBrowser != null) {
                    OutlinedButton(
                        onClick = {
                            cancelCountdown()
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onOpenInAppBrowser()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Public,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.share_action_in_app_browser),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Button(
                    onClick = {
                        cancelCountdown()
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onConfirm(
                            if (isUrlShare) selectedRenderMode else null,
                            selectedFolder,
                            rememberDomainRule
                        )
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        imageVector = if (isConnected) Icons.AutoMirrored.Filled.Send else Icons.Default.CloudUpload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(
                            if (isConnected) R.string.share_action_send_now else R.string.share_action_queue_later
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
