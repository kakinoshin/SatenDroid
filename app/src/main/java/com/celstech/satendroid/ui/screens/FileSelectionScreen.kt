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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.celstech.satendroid.ui.components.DeleteFileWithPermission
import com.celstech.satendroid.ui.components.LocalItemCard
import com.celstech.satendroid.ui.models.LocalItem
import com.celstech.satendroid.ui.models.ReadingFilterType
import com.celstech.satendroid.viewmodel.LocalFileViewModel
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
    onOpenSettings: () -> Unit,
    isLoading: Boolean,
    onReturnFromViewer: (() -> Unit)? = null,
    readingStatusUpdate: Pair<File, Int>? = null,
    fileCompletionUpdate: File? = null
) {
    val context = LocalContext.current
    val viewModel: LocalFileViewModel = viewModel(
        factory = LocalFileViewModel.provideFactory(context)
    )
    val uiState by viewModel.uiState.collectAsState()

    // SimpleReadingDataManagerで初期化と読み込み
    LaunchedEffect(Unit) {
        viewModel.scanDirectory(initialPath)
    }

    // 画像ビューアーから戻ってきたときの処理
    LaunchedEffect(onReturnFromViewer) {
        onReturnFromViewer?.let {
            viewModel.onReturnFromImageViewer()
            it() // コールバックを実行
        }
    }

    // 読書状態の更新処理（改良版）
    LaunchedEffect(readingStatusUpdate) {
        readingStatusUpdate?.let { (file, currentPage) ->
            println("DEBUG: FileSelectionScreen - Updating reading status for ${file.name} at page ${currentPage + 1}")

            // ファイルパスからLocalItem.ZipFileを検索
            val zipFileItem = uiState.localItems.find { item ->
                item is LocalItem.ZipFile && item.file.absolutePath == file.absolutePath
            } as? LocalItem.ZipFile

            if (zipFileItem != null) {
                // 総画像数を確実に設定
                val totalImageCount = if (zipFileItem.totalImageCount > 0) {
                    zipFileItem.totalImageCount
                } else {
                    // SimpleReadingDataManagerから取得を試行
                    viewModel.readingDataManager.getTotalPages(file.absolutePath).takeIf { it > 0 }
                        ?: (currentPage + 1)
                }

                // totalImageCountが正しくない場合は更新
                if (zipFileItem.totalImageCount != totalImageCount) {
                    // LocalItem.ZipFileを更新（immutableなので新しいインスタンスを作成）
                    val updatedZipFile = zipFileItem.copy(totalImageCount = totalImageCount)

                    // ViewModelに更新を通知
                    viewModel.updateReadingStatus(updatedZipFile, currentPage)
                } else {
                    viewModel.updateReadingStatus(zipFileItem, currentPage)
                }

                println("DEBUG: Reading status updated successfully - Total images: $totalImageCount")
            } else {
                println("DEBUG: Could not find LocalItem.ZipFile for ${file.name}")
                println(
                    "DEBUG: Available items: ${
                        uiState.localItems.filterIsInstance<LocalItem.ZipFile>().map { it.name }
                    }"
                )
            }
        }
    }

    // ファイル完了マーク処理（次のファイルに移動時に既読にマーク）（改良版）
    LaunchedEffect(fileCompletionUpdate) {
        fileCompletionUpdate?.let { file ->
            println("DEBUG: FileSelectionScreen - Marking file as completed: ${file.name}")

            // ファイルパスからLocalItem.ZipFileを検索
            val zipFileItem = uiState.localItems.find { item ->
                item is LocalItem.ZipFile && item.file.absolutePath == file.absolutePath
            } as? LocalItem.ZipFile

            if (zipFileItem != null) {
                // 総画像数を確実に取得
                val totalImageCount = if (zipFileItem.totalImageCount > 0) {
                    zipFileItem.totalImageCount
                } else {
                    // SimpleReadingDataManagerから取得を試行
                    viewModel.readingDataManager.getTotalPages(file.absolutePath).takeIf { it > 0 }
                        ?: 1
                }

                // 最後のページまで読んだとして既読にマーク
                val lastPage = if (totalImageCount > 0) totalImageCount - 1 else 0

                // totalImageCountが正しくない場合は更新
                if (zipFileItem.totalImageCount != totalImageCount) {
                    val updatedZipFile = zipFileItem.copy(totalImageCount = totalImageCount)
                    viewModel.updateReadingStatus(updatedZipFile, lastPage)
                } else {
                    viewModel.updateReadingStatus(zipFileItem, lastPage)
                }

                println("DEBUG: File marked as completed successfully - Total images: $totalImageCount, Last page: $lastPage")
            } else {
                println("DEBUG: Could not find LocalItem.ZipFile for completion marking: ${file.name}")
                println(
                    "DEBUG: Available items: ${
                        uiState.localItems.filterIsInstance<LocalItem.ZipFile>().map { it.name }
                    }"
                )
            }
        }
    }

    // ディレクトリパスが変更されたときに通知
    LaunchedEffect(uiState.currentPath) {
        onDirectoryChanged(uiState.currentPath)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "SatenDroid",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Text(
                    text = "ZIP Image Viewer",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                )
            }

            TextButton(
                onClick = onOpenSettings,
                enabled = !isLoading
            ) {
                Text("⚙️ 設定")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onOpenFromDevice,
                modifier = Modifier.weight(1f),
                enabled = !isLoading
            ) {
                Text("📱 Open from Device")
            }

            OutlinedButton(
                onClick = onOpenFromDropbox,
                modifier = Modifier.weight(1f),
                enabled = !isLoading
            ) {
                Text("☁️ Open from Dropbox")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Compact Filter UI - Only show when files exist
        if (uiState.localItems.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Filter display
                val filterDisplayText = when (uiState.filterType) {
                    ReadingFilterType.ALL -> "すべて"
                    ReadingFilterType.HIDE_COMPLETED -> "既読を非表示"
                    ReadingFilterType.UNREAD -> "未読のみ"
                    ReadingFilterType.READING -> "読書中のみ"
                    ReadingFilterType.COMPLETED -> "既読のみ"
                }

                Text(
                    text = "📁 フィルター: $filterDisplayText",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )

                // Filter menu
                var showFilterMenu by remember { mutableStateOf(false) }

                Box {
                    IconButton(onClick = { showFilterMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.FilterList,
                            contentDescription = "フィルター",
                            tint = if (uiState.filterType != ReadingFilterType.ALL) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }

                    DropdownMenu(
                        expanded = showFilterMenu,
                        onDismissRequest = { showFilterMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("すべて") },
                            onClick = {
                                viewModel.setReadingFilter(ReadingFilterType.ALL)
                                showFilterMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("既読を非表示") },
                            onClick = {
                                viewModel.setReadingFilter(ReadingFilterType.HIDE_COMPLETED)
                                showFilterMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("未読のみ") },
                            onClick = {
                                viewModel.setReadingFilter(ReadingFilterType.UNREAD)
                                showFilterMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("読書中のみ") },
                            onClick = {
                                viewModel.setReadingFilter(ReadingFilterType.READING)
                                showFilterMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("既読のみ") },
                            onClick = {
                                viewModel.setReadingFilter(ReadingFilterType.COMPLETED)
                                showFilterMenu = false
                            }
                        )
                    }
                }
            }

            // Display count (compact)
            if (uiState.filterType != ReadingFilterType.ALL) {
                val displayItems = viewModel.getDisplayItems()
                val zipFileCount = displayItems.filterIsInstance<LocalItem.ZipFile>().size
                val totalZipFileCount =
                    uiState.localItems.filterIsInstance<LocalItem.ZipFile>().size

                Text(
                    text = "📊 表示中: $zipFileCount/$totalZipFileCount ファイル",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Navigation bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (uiState.isSelectionMode) {
                // Selection mode toolbar
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { viewModel.exitSelectionMode() }) {
                        Text("✕ Cancel")
                    }

                    Text(
                        text = "${uiState.selectedItems.size} selected",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    if (uiState.selectedItems.size == viewModel.getDisplayItems().size && viewModel.getDisplayItems()
                            .isNotEmpty()
                    ) {
                        TextButton(onClick = { viewModel.deselectAll() }) {
                            Text("Deselect All")
                        }
                    } else if (viewModel.getDisplayItems().isNotEmpty()) {
                        TextButton(onClick = { viewModel.selectAll() }) {
                            Text("Select All")
                        }
                    }

                    if (uiState.selectedItems.isNotEmpty()) {
                        Button(
                            onClick = { viewModel.setShowDeleteConfirmDialog(true) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Text("🗑️ Delete (${uiState.selectedItems.size})")
                        }
                    }
                }
            } else {
                // Normal navigation bar
                if (uiState.currentPath.isNotEmpty()) {
                    Button(
                        onClick = { viewModel.navigateBack() },
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text("← Back")
                    }
                }

                Text(
                    text = if (uiState.currentPath.isEmpty()) "📁 Local Files" else "📁 ${uiState.currentPath}",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )

                TextButton(
                    onClick = { viewModel.scanDirectory(uiState.currentPath) },
                    enabled = !uiState.isRefreshing
                ) {
                    Text("🔄 Refresh")
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

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
                                                    // ZIPファイルを開く前に読書状態を更新
                                                    viewModel.onZipFileOpened(item)
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
