package com.crosssend.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.crosssend.app.R
import com.crosssend.app.network.WifiConnectionState
import com.crosssend.app.ui.theme.LocalStatusColors

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CrossSendBottomToolbar(
    connectionState: WifiConnectionState,
    isSyncing: Boolean,
    pendingUploadCount: Int,
    onConnect: () -> Unit,
    onSync: () -> Unit,
    onOpenSettings: () -> Unit,
    onSelectFeeds: () -> Unit,
    onAddUrl: () -> Unit,
    onPickFile: () -> Unit,
    onAddNote: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenConnectionSettings: () -> Unit = {},
    onPickSleepScreenClick: (() -> Unit)? = null
) {
    val isConnected = connectionState is WifiConnectionState.ConnectedToCrossPointAp ||
            connectionState is WifiConnectionState.ConnectedOnLocalNetwork
    val isSearchingOrConnecting = connectionState is WifiConnectionState.ConnectingToAp ||
            connectionState is WifiConnectionState.SearchingOnLan
    val isBusy = isSyncing || isSearchingOrConnecting

    var showActionSheet by remember { mutableStateOf(false) }
    val statusColors = LocalStatusColors.current

    val fabBgColor by animateColorAsState(
        targetValue = when {
            isBusy -> MaterialTheme.colorScheme.secondaryContainer
            isConnected && pendingUploadCount > 0 -> statusColors.pendingContainer
            isConnected -> statusColors.syncedContainer
            else -> MaterialTheme.colorScheme.primaryContainer
        },
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "fabBg"
    )

    val fabContentColor by animateColorAsState(
        targetValue = when {
            isBusy -> MaterialTheme.colorScheme.onSecondaryContainer
            isConnected && pendingUploadCount > 0 -> statusColors.onPendingContainer
            isConnected -> statusColors.onSyncedContainer
            else -> MaterialTheme.colorScheme.onPrimaryContainer
        },
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "fabContent"
    )

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Left Element: M3 Expressive Horizontal Floating Toolbar
        HorizontalFloatingToolbar(
            expanded = true,
            modifier = Modifier.height(60.dp),
            colors = FloatingToolbarDefaults.standardFloatingToolbarColors(
                toolbarContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        ) {
            // 1. + (Inhalte hinzufügen)
            IconButton(
                onClick = { showActionSheet = true },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.toolbar_add_content),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }

            // 2. RSS-Feeds
            IconButton(
                onClick = onSelectFeeds,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.RssFeed,
                    contentDescription = stringResource(R.string.toolbar_rss_feeds),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }

            // 3. Einstellungen & CrossPoint-Gerät (Zahnrad)
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = stringResource(R.string.toolbar_device_settings),
                        tint = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                    if (isConnected) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .align(Alignment.TopEnd)
                                .clip(CircleShape)
                                .background(statusColors.synced)
                        )
                    }
                }
            }
        }

        // Right Element: Material 3 Expressive FAB
        FloatingActionButton(
            onClick = {
                when {
                    isSyncing -> Unit
                    isSearchingOrConnecting -> onConnect()
                    isConnected -> onSync()
                    else -> onConnect()
                }
            },
            modifier = Modifier.size(60.dp),
            shape = RoundedCornerShape(18.dp),
            containerColor = fabBgColor,
            contentColor = fabContentColor,
            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp)
        ) {
            if (isSyncing) {
                CircularWavyProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = fabContentColor,
                    trackColor = fabContentColor.copy(alpha = 0.24f)
                )
            } else if (isSearchingOrConnecting) {
                LoadingIndicator(
                    modifier = Modifier.size(28.dp),
                    color = fabContentColor
                )
            } else if (isConnected && pendingUploadCount > 0) {
                BadgedBox(
                    badge = {
                        Badge {
                            Text("$pendingUploadCount")
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = stringResource(R.string.toolbar_sync_pending, pendingUploadCount),
                        modifier = Modifier.size(24.dp)
                    )
                }
            } else if (isConnected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = stringResource(R.string.toolbar_synced_tap),
                    modifier = Modifier.size(24.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Wifi,
                    contentDescription = stringResource(R.string.toolbar_connect_crosspoint),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }

    // Material 3 Modal Bottom Sheet for Actions
    if (showActionSheet) {
        ModalBottomSheet(
            onDismissRequest = { showActionSheet = false },
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
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 36.dp)
            ) {
                Text(
                    text = stringResource(R.string.toolbar_sheet_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 12.dp, start = 4.dp)
                )

                ActionSheetItem(
                    title = stringResource(R.string.toolbar_action_import_file),
                    subtitle = stringResource(R.string.toolbar_action_import_file_desc),
                    icon = Icons.Default.UploadFile,
                    iconContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                    onClick = {
                        showActionSheet = false
                        onPickFile()
                    }
                )

                ActionSheetItem(
                    title = stringResource(R.string.toolbar_action_add_url),
                    subtitle = stringResource(R.string.toolbar_action_add_url_desc),
                    icon = Icons.Default.Language,
                    iconContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                    onClick = {
                        showActionSheet = false
                        onAddUrl()
                    }
                )

                ActionSheetItem(
                    title = stringResource(R.string.toolbar_action_add_note),
                    subtitle = stringResource(R.string.toolbar_action_add_note_desc),
                    icon = Icons.AutoMirrored.Filled.NoteAdd,
                    iconContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
                    onClick = {
                        showActionSheet = false
                        onAddNote()
                    }
                )

                if (onPickSleepScreenClick != null) {
                    ActionSheetItem(
                        title = stringResource(R.string.toolbar_action_send_sleepscreen),
                        subtitle = stringResource(R.string.toolbar_action_send_sleepscreen_desc),
                        icon = Icons.Default.Image,
                        iconContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = {
                            showActionSheet = false
                            onPickSleepScreenClick()
                        }
                    )
                }

                ActionSheetItem(
                    title = stringResource(R.string.conn_dialog_title),
                    subtitle = stringResource(R.string.conn_dialog_desc),
                    icon = Icons.Default.Wifi,
                    iconContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    iconTint = MaterialTheme.colorScheme.onSurface,
                    onClick = {
                        showActionSheet = false
                        onOpenConnectionSettings()
                    }
                )
            }
        }
    }
}

@Composable
private fun ActionSheetItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconContainerColor: Color,
    iconTint: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(iconContainerColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
