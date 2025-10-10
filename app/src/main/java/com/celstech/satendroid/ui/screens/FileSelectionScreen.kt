package com.celstech.satendroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.celstech.satendroid.ui.components.AdaptiveHeader
import com.celstech.satendroid.ui.components.DeleteFileWithPermission
import com.celstech.satendroid.ui.components.LocalItemCard
import com.celstech.satendroid.ui.components.SwipeableHeader
import com.celstech.satendroid.ui.models.LocalItem
import com.celstech.satendroid.ui.models.ReadingFilterType
import com.celstech.satendroid.ui.models.HeaderState
import com.celstech.satendroid.viewmodel.LocalFileViewModel
import kotlinx.coroutines.launch
import java.io.File

/**
 * ファイル選択画面 - ローカルファイルの一覧表示と管理
 */
@Composable
fun FileSelectionScreen(
    initialPath: String = "",
    onFileSelected: (File) -> Unit,
    onDirectoryChanged: (String) -> Unit,
    onOpenFromDevice: () -> Unit,
    onOpenFromDropbox: () -> Unit,
    onOpenDownloadQueue: () -> Unit,
    onOpenSettings: () -> Unit,
    isLoading: Boolean
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val viewModel: LocalFileViewModel = viewModel(
        factory = LocalFileViewModel.provideFactory(context)
    )
    val uiState by viewModel.uiState.collectAsState()

    // 初期化と読み込み
    LaunchedEffect(Unit) {
        viewModel.scanDirectory(initialPath)
        viewModel.initializeCloudProviders()
    }

    // 画像ビューアーから戻ってきたときの処理（廃止予定）
    LaunchedEffect(Unit) {
        // 何もしない（StateFlowで自動更新されるため）
    }

    // ディレクトリパスが変更されたときに通知
    LaunchedEffect(uiState.currentPath) {
        onDirectoryChanged(uiState.currentPath)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 新しいスワイプ対応ヘッダー - Phase 2機能統合
        SwipeableHeader(
            headerState = uiState.headerState,
            onHeaderStateChange = { newState ->
                viewModel.setHeaderState(newState, "user_gesture")
            },
            onCollapseAfterDelay = {
                if (uiState.headerState == HeaderState.EXPANDED) {
                    coroutineScope.launch {
                        viewModel.autoCollapseHeader()
                    }
                }
            },
            swipeThreshold = uiState.headerSettings.swipeThreshold,
            autoCollapseDelay = if (uiState.headerSettings.autoCollapseEnabled) 
                uiState.headerSettings.autoCollapseDelay else 0L,
            enableTapToToggle = uiState.headerSettings.enableTapToToggle,
            enableVelocityDetection = uiState.headerSettings.enableVelocityDetection,
            content = {
                AdaptiveHeader(
                    headerState = uiState.headerState,
                    currentPath = uiState.currentPath,
                    filterType = uiState.filterType,
                    isLoading = isLoading || uiState.isRefreshing,
                    hasFiles = uiState.localItems.isNotEmpty(),
                    headerSettings = uiState.headerSettings,
                    cloudProviders = uiState.cloudProviders,
                    onSettingsClick = onOpenSettings,
                    onDeviceClick = onOpenFromDevice,
                    onDropboxClick = onOpenFromDropbox,
                    onDownloadQueueClick = onOpenDownloadQueue,
                    onFilterTypeChange = viewModel::setReadingFilter,
                    onRefreshClick = { viewModel.scanDirectory(uiState.currentPath) },
                    onCloudProviderClick = { provider ->
                        // Cloud Provider機能の実装
                        println("DEBUG: Cloud provider clicked: ${provider.name}")
                        when (provider.id) {
                            "dropbox" -> onOpenFromDropbox()
                            "googledrive" -> {
                                // Google Drive連携の実装
                                // 実際にはCloud Provider管理画面やAPIを呼び出し
                            }
                            "onedrive" -> {
                                // OneDrive連携の実装
                            }
                        }
                    },
                    onCloudProviderAuth = { providerId ->
                        // Cloud Provider認証
                        println("DEBUG: Starting authentication for provider: $providerId")
                        viewModel.authenticateCloudProvider(providerId)
                    }
                )
            }
        )

        // フィルター表示統計（コンパクト版、ファイルがある場合のみ）
        if (uiState.localItems.isNotEmpty() && uiState.filterType != ReadingFilterType.ALL) {
            val displayItems = viewModel.getDisplayItems()
            val zipFileCount = displayItems.filterIsInstance<LocalItem.ZipFile>().size
            val totalZipFileCount = uiState.localItems.filterIsInstance<LocalItem.ZipFile>().size

            if (zipFileCount != totalZipFileCount) {
                Text(
                    text = "📊 表示中: $zipFileCount/$totalZipFileCount ファイル",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
            }
        }

        // 選択モード時のツールバー（選択モード時のみ表示）
        if (uiState.isSelectionMode) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { viewModel.exitSelectionMode() }) {
                            Text("✕ キャンセル")
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Text(
                            text = "${uiState.selectedItems.size} 選択中",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (uiState.selectedItems.size == viewModel.getDisplayItems().size && viewModel.getDisplayItems()
                                .isNotEmpty()
                        ) {
                            TextButton(onClick = { viewModel.deselectAll() }) {
                                Text("すべて解除")
                            }
                        } else if (viewModel.getDisplayItems().isNotEmpty()) {
                            TextButton(onClick = { viewModel.selectAll() }) {
                                Text("すべて選択")
                            }
                        }

                        if (uiState.selectedItems.isNotEmpty()) {
                            Button(
                                onClick = { viewModel.setShowDeleteConfirmDialog(true) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Text("🗑️ 削除 (${uiState.selectedItems.size})")
                            }
                        }
                    }
                }
            }
        } else {
            // ナビゲーション情報（通常モード時、必要に応じて）
            if (uiState.currentPath.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { viewModel.navigateBack() },
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text("← 戻る")
                    }
                }
            }
        }

        when {
            isLoading -> {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Loading...")
                    }
                }
            }

            uiState.isRefreshing -> {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Scanning for files...")
                    }
                }
            }

            else -> {
                val displayItems = viewModel.getDisplayItems()

                when {
                    displayItems.isEmpty() -> {
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = "📁",
                                    style = MaterialTheme.typography.displayLarge,
                                    modifier = Modifier.padding(bottom = 16.dp)
                                )

                                val emptyMessage = when (uiState.filterType) {
                                    ReadingFilterType.ALL -> {
                                        if (uiState.currentPath.isEmpty()) "No ZIP files found" else "Empty folder"
                                    }

                                    ReadingFilterType.UNREAD -> "未読のファイルがありません"
                                    ReadingFilterType.READING -> "読書中のファイルがありません"
                                    ReadingFilterType.COMPLETED -> "既読のファイルがありません"
                                    ReadingFilterType.HIDE_COMPLETED -> "表示できるファイルがありません（既読を除く）"
                                }

                                Text(
                                    text = emptyMessage,
                                    style = MaterialTheme.typography.titleMedium,
                                    textAlign = TextAlign.Center
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                val emptyDescription = when (uiState.filterType) {
                                    ReadingFilterType.ALL -> {
                                        if (uiState.currentPath.isEmpty())
                                            "Download ZIP files from Dropbox or select from your device to get started"
                                        else
                                            "This folder doesn't contain any ZIP files"
                                    }

                                    ReadingFilterType.UNREAD -> "すべてのファイルが読書済みまたは読書中です"
                                    ReadingFilterType.READING -> "現在読書中のファイルがありません"
                                    ReadingFilterType.COMPLETED -> "まだ読了したファイルがありません"
                                    ReadingFilterType.HIDE_COMPLETED -> "未読・読書中のファイルがありません"
                                }

                                Text(
                                    text = emptyDescription,
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }

                    else -> {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(
                                items = displayItems,
                                key = { item -> item.path }
                            ) { item ->
                                LocalItemCard(
                                    item = item,
                                    viewModel = viewModel,
                                    isSelected = uiState.selectedItems.contains(item),
                                    isSelectionMode = uiState.isSelectionMode,
                                    onClick = {
                                        if (uiState.isSelectionMode) {
                                            viewModel.toggleItemSelection(item)
                                        } else {
                                            when (item) {
                                                is LocalItem.Folder -> viewModel.navigateToFolder(
                                                    item.path
                                                )

                                                is LocalItem.ZipFile -> {
                                                    onFileSelected(item.file)
                                                }
                                            }
                                        }
                                    },
                                    onLongClick = {
                                        if (!uiState.isSelectionMode) {
                                            viewModel.enterSelectionMode(item)
                                        }
                                    },
                                    onDeleteClick = {
                                        viewModel.setItemToDelete(item)
                                        viewModel.setShowDeleteConfirmDialog(true)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Delete confirmation dialog
        if (uiState.showDeleteConfirmDialog) {
            AlertDialog(
                onDismissRequest = {
                    viewModel.setShowDeleteConfirmDialog(false)
                    viewModel.setItemToDelete(null)
                },
                title = {
                    Text(
                        text = if (uiState.itemToDelete != null) "Delete Item?" else "Delete ${uiState.selectedItems.size} Items?"
                    )
                },
                text = {
                    Text(
                        text = if (uiState.itemToDelete != null) {
                            when (uiState.itemToDelete) {
                                is LocalItem.Folder -> "Are you sure you want to delete the folder '${uiState.itemToDelete!!.name}' and all its contents?"
                                is LocalItem.ZipFile -> "Are you sure you want to delete the file '${uiState.itemToDelete!!.name}'? This action cannot be undone."
                                else -> ""
                            }
                        } else {
                            "Are you sure you want to delete ${uiState.selectedItems.size} selected items? This action cannot be undone."
                        }
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (uiState.itemToDelete != null) {
                                when (val item = uiState.itemToDelete!!) {
                                    is LocalItem.Folder -> {
                                        viewModel.deleteFolder(item)
                                        viewModel.setItemToDelete(null)
                                        viewModel.setShowDeleteConfirmDialog(false)
                                    }

                                    is LocalItem.ZipFile -> {
                                        viewModel.setItemToDelete(item)
                                        viewModel.setShowDeleteConfirmDialog(false)
                                        viewModel.setShowDeleteZipWithPermissionDialog(true)
                                    }
                                }
                            } else {
                                viewModel.deleteSelectedItems()
                                viewModel.setShowDeleteConfirmDialog(false)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            viewModel.setShowDeleteConfirmDialog(false)
                            viewModel.setItemToDelete(null)
                        }
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }

        // ZIP file deletion with permission dialog
        if (uiState.showDeleteZipWithPermissionDialog && uiState.itemToDelete is LocalItem.ZipFile) {
            DeleteFileWithPermission(
                item = uiState.itemToDelete as LocalItem.ZipFile,
                onDeleteResult = { success ->
                    if (!success) {
                        println("DEBUG: Failed to delete ${uiState.itemToDelete!!.name}")
                    }
                    viewModel.setItemToDelete(null)
                    viewModel.setShowDeleteZipWithPermissionDialog(false)
                    viewModel.scanDirectory(uiState.currentPath)
                }
            )
        }
    }
}
