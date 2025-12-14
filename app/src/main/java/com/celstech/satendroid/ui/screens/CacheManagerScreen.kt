package com.celstech.satendroid.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.celstech.satendroid.ui.models.ReadingStatus
import com.celstech.satendroid.utils.FormatUtils
import com.celstech.satendroid.utils.SavedStateInfo
import com.celstech.satendroid.viewmodel.CacheFilter
import com.celstech.satendroid.viewmodel.CacheManagerViewModel
import java.io.File

/**
 * キャッシュマネージャ画面
 * 保存された読書状態を管理・削除する
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CacheManagerScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val viewModel: CacheManagerViewModel = viewModel(
        factory = CacheManagerViewModel.provideFactory(context)
    )
    val uiState by viewModel.uiState.collectAsState()
    val isSelectionMode by viewModel.isSelectionMode.collectAsState()
    val selectedItems by viewModel.selectedItems.collectAsState()

    var showDeleteAllDialog by remember { mutableStateOf(false) }
    var showDeleteDeletedFilesDialog by remember { mutableStateOf(false) }
    var showFilterMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            if (isSelectionMode) {
                SelectionModeTopBar(
                    selectedCount = selectedItems.size,
                    totalCount = uiState.filteredStates.size,
                    onExitSelectionMode = { viewModel.toggleSelectionMode() },
                    onSelectAll = { viewModel.selectAll() },
                    onDeselectAll = { viewModel.deselectAll() },
                    onDeleteSelected = { viewModel.deleteSelectedStates() }
                )
            } else {
                TopAppBar(
                    title = { Text("読書状態管理") },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.Default.ArrowBack, "戻る")
                        }
                    },
                    actions = {
                        // フィルターボタン
                        IconButton(onClick = { showFilterMenu = true }) {
                            Icon(Icons.Default.FilterList, "フィルター")
                        }
                        
                        DropdownMenu(
                            expanded = showFilterMenu,
                            onDismissRequest = { showFilterMenu = false }
                        ) {
                            FilterMenuItem("すべて", CacheFilter.ALL, uiState.currentFilter) {
                                viewModel.applyFilter(it)
                                showFilterMenu = false
                            }
                            FilterMenuItem("存在するファイル", CacheFilter.EXISTING_FILES, uiState.currentFilter) {
                                viewModel.applyFilter(it)
                                showFilterMenu = false
                            }
                            FilterMenuItem("削除済みファイル", CacheFilter.DELETED_FILES, uiState.currentFilter) {
                                viewModel.applyFilter(it)
                                showFilterMenu = false
                            }
                            Divider()
                            FilterMenuItem("未読", CacheFilter.UNREAD, uiState.currentFilter) {
                                viewModel.applyFilter(it)
                                showFilterMenu = false
                            }
                            FilterMenuItem("読書中", CacheFilter.READING, uiState.currentFilter) {
                                viewModel.applyFilter(it)
                                showFilterMenu = false
                            }
                            FilterMenuItem("読了", CacheFilter.COMPLETED, uiState.currentFilter) {
                                viewModel.applyFilter(it)
                                showFilterMenu = false
                            }
                        }

                        // 選択モードボタン
                        IconButton(onClick = { viewModel.toggleSelectionMode() }) {
                            Icon(Icons.Default.CheckCircle, "選択モード")
                        }

                        // メニューボタン
                        var showMenu by remember { mutableStateOf(false) }
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, "メニュー")
                        }
                        
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("削除済みファイルの状態を削除") },
                                onClick = {
                                    showMenu = false
                                    showDeleteDeletedFilesDialog = true
                                },
                                enabled = uiState.deletedFilesCount > 0
                            )
                            DropdownMenuItem(
                                text = { Text("全状態を削除") },
                                onClick = {
                                    showMenu = false
                                    showDeleteAllDialog = true
                                },
                                enabled = uiState.totalStatesCount > 0
                            )
                        }
                    }
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 統計情報カード
            StatisticsCard(uiState)

            // リスト
            if (uiState.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (uiState.filteredStates.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = when (uiState.currentFilter) {
                            CacheFilter.ALL -> "保存された読書状態がありません"
                            else -> "該当する読書状態がありません"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = uiState.filteredStates,
                        key = { it.fileHash }
                    ) { stateInfo ->
                        SavedStateCard(
                            stateInfo = stateInfo,
                            isSelected = selectedItems.contains(stateInfo.fileHash),
                            isSelectionMode = isSelectionMode,
                            onToggleSelection = { viewModel.toggleItemSelection(stateInfo.fileHash) },
                            onDelete = { viewModel.deleteSingleState(stateInfo.fileHash) }
                        )
                    }
                }
            }
        }
    }

    // 削除確認ダイアログ
    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            title = { Text("全状態を削除") },
            text = { Text("すべての読書状態を削除しますか？この操作は取り消せません。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteAllStates()
                        showDeleteAllDialog = false
                    }
                ) {
                    Text("削除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllDialog = false }) {
                    Text("キャンセル")
                }
            }
        )
    }

    if (showDeleteDeletedFilesDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDeletedFilesDialog = false },
            title = { Text("削除済みファイルの状態を削除") },
            text = { Text("削除済みファイル（${uiState.deletedFilesCount}件）の読書状態を削除しますか？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteDeletedFilesStates()
                        showDeleteDeletedFilesDialog = false
                    }
                ) {
                    Text("削除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDeletedFilesDialog = false }) {
                    Text("キャンセル")
                }
            }
        )
    }
}

@Composable
private fun StatisticsCard(uiState: com.celstech.satendroid.viewmodel.CacheManagerUiState) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "統計情報",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatItem("合計", uiState.totalStatesCount.toString())
                StatItem("存在", uiState.existingFilesCount.toString())
                StatItem("削除済", uiState.deletedFilesCount.toString())
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatItem("未読", uiState.unreadCount.toString(), "⚪")
                StatItem("読書中", uiState.readingCount.toString(), "🔵")
                StatItem("読了", uiState.completedCount.toString(), "✅")
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String, icon: String? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Text(text = icon, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionModeTopBar(
    selectedCount: Int,
    totalCount: Int,
    onExitSelectionMode: () -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onDeleteSelected: () -> Unit
) {
    TopAppBar(
        title = { Text("$selectedCount / $totalCount 選択中") },
        navigationIcon = {
            IconButton(onClick = onExitSelectionMode) {
                Icon(Icons.Default.Close, "選択モード終了")
            }
        },
        actions = {
            IconButton(
                onClick = onSelectAll,
                enabled = selectedCount < totalCount
            ) {
                Icon(Icons.Default.CheckCircle, "全選択")
            }
            
            IconButton(
                onClick = onDeselectAll,
                enabled = selectedCount > 0
            ) {
                Icon(Icons.Default.Cancel, "全選択解除")
            }
            
            IconButton(
                onClick = onDeleteSelected,
                enabled = selectedCount > 0
            ) {
                Icon(
                    Icons.Default.Delete,
                    "削除",
                    tint = if (selectedCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
            }
        }
    )
}

@Composable
private fun SavedStateCard(
    stateInfo: SavedStateInfo,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onToggleSelection: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = isSelectionMode) { onToggleSelection() },
        colors = CardDefaults.cardColors(
            containerColor = when {
                isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                !stateInfo.fileExists -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                else -> MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 選択チェックボックス
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelection() },
                    modifier = Modifier.padding(end = 12.dp)
                )
            }

            // ステータスアイコン
            Text(
                text = when (stateInfo.status) {
                    ReadingStatus.UNREAD -> "⚪"
                    ReadingStatus.READING -> "🔵"
                    ReadingStatus.COMPLETED -> "✅"
                },
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(end = 12.dp)
            )

            // ファイル情報
            Column(modifier = Modifier.weight(1f)) {
                // ファイル名
                Text(
                    text = stateInfo.fileName.ifEmpty { "不明なファイル" },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )

                // ファイルパス
                if (stateInfo.filePath.isNotEmpty()) {
                    Text(
                        text = stateInfo.filePath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        maxLines = 1
                    )
                }

                // 進捗情報
                Text(
                    text = stateInfo.progressText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (stateInfo.status) {
                        ReadingStatus.UNREAD -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        ReadingStatus.READING -> MaterialTheme.colorScheme.primary
                        ReadingStatus.COMPLETED -> MaterialTheme.colorScheme.tertiary
                    },
                    fontWeight = FontWeight.Medium
                )

                // 最終更新日時
                Text(
                    text = "更新: ${FormatUtils.formatDate(stateInfo.lastUpdated)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )

                // ファイル存在状態
                if (!stateInfo.fileExists) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "⚠️ ファイルが見つかりません",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // 削除ボタン
            if (!isSelectionMode) {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        "削除",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterMenuItem(
    text: String,
    filter: CacheFilter,
    currentFilter: CacheFilter,
    onClick: (CacheFilter) -> Unit
) {
    DropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text)
                if (filter == currentFilter) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        },
        onClick = { onClick(filter) }
    )
}
