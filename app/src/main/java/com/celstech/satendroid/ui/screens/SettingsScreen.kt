package com.celstech.satendroid.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.celstech.satendroid.utils.ReadingStateManager
import com.celstech.satendroid.ui.components.InfoDialog
import com.celstech.satendroid.settings.DownloadSettings
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    readingStateManager: ReadingStateManager,
    directZipHandler: com.celstech.satendroid.utils.DirectZipImageHandler? = null,
    onBackPressed: () -> Unit,
    onNavigateToCacheManager: () -> Unit = {}
) {
    val context = LocalContext.current
    val downloadSettings = remember { DownloadSettings.getInstance(context) }
    val coroutineScope = rememberCoroutineScope()
    
    val reverseSwipeDirection by readingStateManager.reverseSwipeDirection.collectAsState()
    val maxConcurrentDownloads by downloadSettings.maxConcurrentDownloads.collectAsState()

    var showConfirmClearDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定") },
                navigationIcon = {
                    IconButton(onClick = onBackPressed) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // スワイプ設定
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "操作設定",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("スワイプ方向を逆転")
                        Switch(
                            checked = reverseSwipeDirection,
                            onCheckedChange = { readingStateManager.setReverseSwipeDirection(it) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ダウンロード設定
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "ダウンロード設定",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // 最大同時ダウンロード数設定
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "最大同時ダウンロード数",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = "$maxConcurrentDownloads",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Slider(
                            value = maxConcurrentDownloads.toFloat(),
                            onValueChange = { value ->
                                downloadSettings.setMaxConcurrentDownloads(value.toInt())
                            },
                            valueRange = downloadSettings.getMinConcurrentDownloads().toFloat()..downloadSettings.getMaxConcurrentDownloadsLimit().toFloat(),
                            steps = downloadSettings.getMaxConcurrentDownloadsLimit() - downloadSettings.getMinConcurrentDownloads() - 1,
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${downloadSettings.getMinConcurrentDownloads()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${downloadSettings.getMaxConcurrentDownloadsLimit()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Text(
                            text = "同時にダウンロードするファイルの最大数を設定します。値が大きいほど高速ですが、ネットワークやストレージに負荷がかかります。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        
                        if (maxConcurrentDownloads != downloadSettings.getDefaultConcurrentDownloads()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    downloadSettings.resetToDefaults()
                                },
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text("デフォルトに戻す")
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // データ管理
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "データ管理",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // キャッシュマネージャへのボタン
                    Button(
                        onClick = onNavigateToCacheManager,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Storage, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("読書状態管理")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = { showConfirmClearDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("全ての読書データを削除")
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // メモリ管理
            if (directZipHandler != null) {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "メモリ管理",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedButton(
                            onClick = { directZipHandler.clearMemoryCacheAsync() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("メモリキャッシュをクリア")
                        }
                    }
                }
            }
        }
    }

    if (showConfirmClearDialog) {
        InfoDialog(
            title = "読書データを削除",
            message = "全ての読書進捗データを削除しますか？この操作は元に戻せません。",
            confirmText = "削除",
            dismissText = "キャンセル",
            onConfirm = {
                coroutineScope.launch {
                    readingStateManager.manuallyDeleteAllStates()
                }
                showConfirmClearDialog = false
            },
            onDismiss = { showConfirmClearDialog = false }
        )
    }
}
