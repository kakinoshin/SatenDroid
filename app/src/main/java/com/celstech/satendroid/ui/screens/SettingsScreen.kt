package com.celstech.satendroid.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.celstech.satendroid.utils.SimpleReadingDataManager
import com.celstech.satendroid.utils.FileReadingData
import com.celstech.satendroid.ui.components.InfoDialog
import com.celstech.satendroid.ui.models.ReadingStatus
import com.celstech.satendroid.settings.DownloadSettings
import androidx.compose.ui.platform.LocalContext
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    readingDataManager: SimpleReadingDataManager,
    directZipHandler: com.celstech.satendroid.utils.DirectZipImageHandler? = null,
    onBackPressed: () -> Unit
) {
    val context = LocalContext.current
    val downloadSettings = remember { DownloadSettings.getInstance(context) }
    
    val reverseSwipeDirection by readingDataManager.reverseSwipeDirection.collectAsState()
    val maxConcurrentDownloads by downloadSettings.maxConcurrentDownloads.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    var showConfirmClearDialog by remember { mutableStateOf(false) }
    var showAgeDataSection by remember { mutableStateOf(false) }
    var ageDataList by remember { mutableStateOf<List<FileReadingData>>(emptyList()) }
    var isLoadingAgeData by remember { mutableStateOf(false) }
    var showFileDeleteDialog by remember { mutableStateOf<FileReadingData?>(null) }
    var statistics by remember { mutableStateOf<com.celstech.satendroid.utils.DataStatistics?>(null) }

    // 年齢データの読み込み
    suspend fun loadAgeData() {
        isLoadingAgeData = true
        try {
            withContext(Dispatchers.IO) {
                statistics = readingDataManager.getStatistics()
                ageDataList = readingDataManager.getAllReadingData()
            }
        } finally {
            isLoadingAgeData = false
        }
    }

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
                            onCheckedChange = { readingDataManager.setReverseSwipeDirection(it) }
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

                    // 読書データ統計表示
                    statistics?.let { stats ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text(
                                            "総ファイル数",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Text(
                                            "${stats.totalFiles}",
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            "平均アクセス",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Text(
                                            "${
                                                String.format(
                                                    Locale.getDefault(),
                                                    "%.1f",
                                                    stats.averageAccessCount
                                                )
                                            }回",
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text("最近使用", style = MaterialTheme.typography.bodySmall)
                                        Text(
                                            "${stats.recentFiles}ファイル",
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            "古いファイル",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Text(
                                            "${stats.oldFiles}ファイル",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 年齢データ管理セクション
                    OutlinedButton(
                        onClick = {
                            showAgeDataSection = !showAgeDataSection
                            if (showAgeDataSection && ageDataList.isEmpty()) {
                                coroutineScope.launch {
                                    loadAgeData()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Storage, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("読書データの詳細管理")
                        Spacer(modifier = Modifier.weight(1f))
                        Icon(
                            if (showAgeDataSection) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null
                        )
                    }

                    if (showAgeDataSection) {
                        Spacer(modifier = Modifier.height(16.dp))

                        // データ再読み込みボタン
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    coroutineScope.launch {
                                        loadAgeData()
                                    }
                                },
                                enabled = !isLoadingAgeData,
                                modifier = Modifier.weight(1f)
                            ) {
                                if (isLoadingAgeData) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(Icons.Default.Info, contentDescription = null)
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("データを更新")
                            }

                            // クリーンアップボタン
                            Button(
                                onClick = {
                                    readingDataManager.performAutomaticCleanup()
                                    coroutineScope.launch {
                                        loadAgeData() // データを再読み込み
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("古いデータを削除")
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // データリスト
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(400.dp),
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            if (ageDataList.isEmpty() && !isLoadingAgeData) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("読書データがありません")
                                }
                            } else if (isLoadingAgeData) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator()
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.padding(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(ageDataList) { data ->
                                        ReadingDataItem(
                                            data = data,
                                            onDelete = { showFileDeleteDialog = data }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

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

    // 個別ファイル削除確認ダイアログ
    showFileDeleteDialog?.let { data ->
        InfoDialog(
            title = "読書データを削除",
            message = "「${File(data.filePath).name}」の読書データを削除しますか？",
            confirmText = "削除",
            dismissText = "キャンセル",
            onConfirm = {
                readingDataManager.clearFileData(data.filePath)
                coroutineScope.launch {
                    loadAgeData() // データを再読み込み
                }
                showFileDeleteDialog = null
            },
            onDismiss = { showFileDeleteDialog = null }
        )
    }

    if (showConfirmClearDialog) {
        InfoDialog(
            title = "読書データを削除",
            message = "全ての読書進捗データを削除しますか？この操作は元に戻せません。",
            confirmText = "削除",
            dismissText = "キャンセル",
            onConfirm = {
                readingDataManager.clearAllData()
                showConfirmClearDialog = false
                // データクリア後に統計とリストもクリア
                statistics = null
                ageDataList = emptyList()
            },
            onDismiss = { showConfirmClearDialog = false }
        )
    }
}

@Composable
fun ReadingDataItem(
    data: FileReadingData,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // ファイル名とステータス
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = File(data.filePath).name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // 読書進捗
                    Text(
                        text = data.progressText,
                        style = MaterialTheme.typography.bodySmall,
                        color = when (data.status) {
                            ReadingStatus.UNREAD -> MaterialTheme.colorScheme.onSurfaceVariant
                            ReadingStatus.READING -> MaterialTheme.colorScheme.primary
                            ReadingStatus.COMPLETED -> MaterialTheme.colorScheme.tertiary
                        }
                    )
                }

                // 削除ボタン
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "削除",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Age情報をコンパクトに表示
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = if (data.daysSinceLastAccess > 14) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                shape = RoundedCornerShape(6.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "作成: ${data.ageInDays}日前",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "アクセス: ${data.accessCount}回",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "最終: ${data.daysSinceLastAccess}日前",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (data.daysSinceLastAccess > 14) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }

            // 進捗バー（読書中の場合のみ）
            if (data.totalPages > 0 && data.status == ReadingStatus.READING) {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { data.progressRatio },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
    }
}
