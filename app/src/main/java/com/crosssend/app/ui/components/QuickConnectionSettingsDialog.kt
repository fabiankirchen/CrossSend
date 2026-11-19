package com.crosssend.app.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.crosssend.app.CrossSendApp
import com.crosssend.app.R
import com.crosssend.app.network.LocalDeviceHostValidator
import kotlinx.coroutines.launch

@Composable
fun QuickConnectionSettingsDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onConnectRequested: (() -> Unit)? = null
) {
    val app = CrossSendApp.instance
    val scope = rememberCoroutineScope()
    var ssidInput by remember { mutableStateOf(app.wifiManager.getApSsid()) }
    var urlInput by remember { mutableStateOf(app.webDavClient.getBaseUrl()) }
    var urlError by remember { mutableStateOf(false) }
    var isScanningLan by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier.imePadding(),
        title = { Text(stringResource(R.string.conn_dialog_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.conn_dialog_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(14.dp))

                // 1. WLAN SSID Input
                OutlinedTextField(
                    value = ssidInput,
                    onValueChange = { ssidInput = it },
                    label = { Text(stringResource(R.string.conn_ssid_label)) },
                    placeholder = { Text(stringResource(R.string.conn_ssid_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    FilterChip(
                        selected = ssidInput == "Cross",
                        onClick = { ssidInput = "Cross" },
                        label = { Text(stringResource(R.string.conn_chip_cross_universal)) }
                    )
                    FilterChip(
                        selected = ssidInput == "CrossPlay",
                        onClick = { ssidInput = "CrossPlay" },
                        label = { Text(stringResource(R.string.conn_chip_crossplay)) }
                    )
                    FilterChip(
                        selected = ssidInput == "CrossPoint",
                        onClick = { ssidInput = "CrossPoint" },
                        label = { Text(stringResource(R.string.conn_chip_crosspoint)) }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 2. WebDAV URL / IP Input
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = {
                        urlInput = it
                        urlError = false
                    },
                    label = { Text(stringResource(R.string.conn_url_label)) },
                    placeholder = { Text(stringResource(R.string.conn_url_placeholder)) },
                    isError = urlError,
                    supportingText = if (urlError) {
                        { Text(stringResource(R.string.error_invalid_local_device_url)) }
                    } else {
                        null
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    FilterChip(
                        selected = isScanningLan,
                        onClick = {
                            if (!isScanningLan) {
                                isScanningLan = true
                                scope.launch {
                                    val found = app.wifiManager.autoDetectCrossPoint()
                                    if (found != null) {
                                        val normalizedFound = LocalDeviceHostValidator.normalizeLocalDeviceUrl(found)
                                        if (normalizedFound != null && app.webDavClient.updateBaseUrl(normalizedFound)) {
                                            urlInput = normalizedFound
                                            urlError = false
                                            app.wifiManager.setConnectedOnLan(normalizedFound)
                                        }
                                    }
                                    isScanningLan = false
                                }
                            }
                        },
                        label = { Text(if (isScanningLan) stringResource(R.string.conn_scanning_lan) else stringResource(R.string.conn_scan_lan)) }
                    )
                    FilterChip(
                        selected = urlInput.contains("crossplay.local"),
                        onClick = {
                            urlInput = "http://crossplay.local"
                            urlError = false
                        },
                        label = { Text("crossplay.local") }
                    )
                    FilterChip(
                        selected = urlInput.contains("crosspoint.local"),
                        onClick = {
                            urlInput = "http://crosspoint.local"
                            urlError = false
                        },
                        label = { Text("crosspoint.local") }
                    )
                    FilterChip(
                        selected = urlInput.contains("192.168.4.1"),
                        onClick = {
                            urlInput = "http://192.168.4.1"
                            urlError = false
                        },
                        label = { Text(stringResource(R.string.conn_chip_ap_ip)) }
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(
                    onClick = {
                        val normalizedUrl = LocalDeviceHostValidator.normalizeLocalDeviceUrl(urlInput)
                        if (normalizedUrl == null) {
                            urlError = true
                            return@TextButton
                        }
                        val lanOk = app.wifiManager.setLanBaseUrl(normalizedUrl)
                        val webDavOk = app.webDavClient.updateBaseUrl(normalizedUrl)
                        if (!lanOk || !webDavOk) {
                            urlError = true
                            return@TextButton
                        }

                        val cleanSsid = ssidInput.trim().ifBlank { "CrossPoint" }
                        app.wifiManager.setApSsid(cleanSsid)
                        onDismiss()
                        onConnectRequested?.invoke()
                    }
                ) {
                    Text(stringResource(R.string.conn_button_connect), fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.conn_button_cancel))
            }
        }
    )
}
