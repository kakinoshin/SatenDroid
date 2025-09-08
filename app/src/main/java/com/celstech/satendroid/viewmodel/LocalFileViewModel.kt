package com.celstech.satendroid.viewmodel

import android.content.Context
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.celstech.satendroid.navigation.LocalFileNavigationManager
import com.celstech.satendroid.navigation.FileNavigationManager
import com.celstech.satendroid.repository.LocalFileRepository
import com.celstech.satendroid.selection.SelectionManager
import com.celstech.satendroid.ui.models.LocalFileUiState
import com.celstech.satendroid.ui.models.LocalItem
import com.celstech.satendroid.utils.DirectZipImageHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.celstech.satendroid.utils.ReadingProgress
import java.io.File

/**
 * ローカルファイル管理のViewModel
 * Repository、Navigation、Selectionの各Managerを使用してスリム化
 * 直接ZIPアクセス版 - DirectZipImageHandlerを使用
 */
class LocalFileViewModel(
    private val repository: LocalFileRepository,
    private val navigationManager: LocalFileNavigationManager,
    private val selectionManager: SelectionManager,
    val directZipHandler: DirectZipImageHandler, // FileSelectionScreenからアクセス可能にするためpublicに変更
    private val fileNavigationManager: FileNavigationManager
) : ViewModel() {

    // UI state
    private val _uiState = MutableStateFlow(LocalFileUiState())
    val uiState: StateFlow<LocalFileUiState> = _uiState.asStateFlow()

    // Reading states for all files (State management for performance)
    private val _readingStates = mutableStateMapOf<String, ReadingProgress>()
    val readingStates: SnapshotStateMap<String, ReadingProgress> = _readingStates

    /**
     * Load reading states for all ZIP files into memory state
     */
    private fun loadReadingStatesForFiles(items: List<LocalItem>) {
        viewModelScope.launch {
            items.filterIsInstance<LocalItem.ZipFile>().forEach { zipFile ->
                try {
                    val progress = repository.getReadingProgressSync(zipFile.file.absolutePath)
                    _readingStates[zipFile.file.absolutePath] = progress
                    println("DEBUG: ViewModel loaded reading state - File: ${zipFile.name}, Status: ${progress.status}, Position: ${progress.currentIndex}")
                } catch (e: Exception) {
                    // Initialize with default state if reading fails
                    _readingStates[zipFile.file.absolutePath] = ReadingProgress(
                        status = com.celstech.satendroid.ui.models.ReadingStatus.UNREAD,
                        currentIndex = 0
                    )
                }
            }
        }
    }

    /**
     * Get reading progress for a file (high-performance memory access)
     */
    fun getReadingProgress(filePath: String): ReadingProgress {
        return _readingStates[filePath] ?: ReadingProgress(
            status = com.celstech.satendroid.ui.models.ReadingStatus.UNREAD,
            currentIndex = 0
        )
    }

    // Navigation
    fun navigateToFolder(folderPath: String) {
        val currentState = _uiState.value
        val navigationResult = navigationManager.navigateToFolder(
            currentState.currentPath,
            currentState.pathHistory,
            folderPath
        )

        _uiState.value = currentState.copy(
            pathHistory = navigationResult.newHistory,
            currentPath = navigationResult.newPath,
            isSelectionMode = false,
            selectedItems = emptySet()
        )
        scanDirectory(navigationResult.newPath)
    }

    fun navigateBack() {
        val currentState = _uiState.value
        val navigationResult = navigationManager.navigateBack(currentState.pathHistory)

        if (navigationResult != null) {
            _uiState.value = currentState.copy(
                pathHistory = navigationResult.newHistory,
                currentPath = navigationResult.newPath,
                isSelectionMode = false,
                selectedItems = emptySet()
            )
            scanDirectory(navigationResult.newPath)
        }
    }

    fun getDisplayPath(): String {
        return navigationManager.formatDisplayPath(_uiState.value.currentPath)
    }

    // Selection mode
    fun enterSelectionMode(initialItem: LocalItem) {
        val selectionState = selectionManager.enterSelectionMode(initialItem)
        _uiState.value = _uiState.value.copy(
            isSelectionMode = selectionState.isSelectionMode,
            selectedItems = selectionState.selectedItems
        )
    }

    fun exitSelectionMode() {
        val selectionState = selectionManager.exitSelectionMode()
        _uiState.value = _uiState.value.copy(
            isSelectionMode = selectionState.isSelectionMode,
            selectedItems = selectionState.selectedItems
        )
    }

    fun toggleItemSelection(item: LocalItem) {
        val currentState = _uiState.value
        val newSelectedItems = selectionManager.toggleItemSelection(
            currentState.selectedItems,
            item
        )
        _uiState.value = currentState.copy(selectedItems = newSelectedItems)
    }

    fun selectAll() {
        val newSelectedItems = selectionManager.selectAll(_uiState.value.localItems)
        _uiState.value = _uiState.value.copy(selectedItems = newSelectedItems)
    }

    fun deselectAll() {
        val newSelectedItems = selectionManager.deselectAll()
        _uiState.value = _uiState.value.copy(selectedItems = newSelectedItems)
    }

    fun getSelectionStatus(): SelectionManager.SelectionStatus {
        val currentState = _uiState.value
        return selectionManager.getSelectionStatus(
            currentState.selectedItems,
            currentState.localItems
        )
    }

    // Scan directory
    fun scanDirectory(path: String = "") {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true)

            try {
                val items = repository.scanDirectory(path)

                _uiState.value = _uiState.value.copy(
                    localItems = items,
                    isRefreshing = false
                )

                // Load reading states for all ZIP files (high-performance State management)
                loadReadingStatesForFiles(items)

                println("DEBUG: Found ${items.size} items in path '$path'")

            } catch (e: Exception) {
                println("DEBUG: Error scanning directory '$path': ${e.message}")
                e.printStackTrace()
                _uiState.value = _uiState.value.copy(isRefreshing = false)
            }
        }
    }

    // Delete operations
    fun deleteFile(item: LocalItem.ZipFile): Boolean {
        var result = false
        viewModelScope.launch {
            result = repository.deleteFile(item)
            if (result) {
                // キャッシュからも削除
                val zipUri = item.file.toUri()
                directZipHandler.onZipFileDeleted(zipUri, item.file)
            }
        }
        return result
    }

    fun deleteFolder(item: LocalItem.Folder): Boolean {
        var result = false
        viewModelScope.launch {
            result = repository.deleteFolder(item)
            // フォルダ内のZIPファイルのキャッシュもクリア
            if (result) {
                // フォルダ内のZIPファイルを検索してキャッシュをクリア
                clearCacheForFolder(item.path)
            }
        }
        return result
    }

    fun deleteSelectedItems() {
        viewModelScope.launch {
            val (successCount, failCount) = repository.deleteItems(_uiState.value.selectedItems)

            // 成功した削除項目のキャッシュをクリア
            _uiState.value.selectedItems.forEach { item ->
                when (item) {
                    is LocalItem.ZipFile -> {
                        val zipUri = item.file.toUri()
                        directZipHandler.onZipFileDeleted(zipUri, item.file)
                    }

                    is LocalItem.Folder -> {
                        clearCacheForFolder(item.path)
                    }
                }
            }

            // Clear selection and refresh
            _uiState.value = _uiState.value.copy(
                selectedItems = emptySet(),
                isSelectionMode = false
            )
            scanDirectory(_uiState.value.currentPath)
        }
    }

    private fun clearCacheForFolder(folderPath: String) {
        // フォルダー内のZIPファイルのキャッシュのみをクリア（全データ削除を防止）
        viewModelScope.launch {
            try {
                println("DEBUG: Clearing cache for folder: $folderPath")
                
                // UnifiedReadingDataManagerのフォルダー内ファイルクリア機能を使用
                directZipHandler.getUnifiedDataManager().clearReadingDataForFolder(folderPath)
                
                // DirectZipImageHandlerでもフォルダー削除処理を実行
                directZipHandler.onFolderDeleted(folderPath)
                
                println("DEBUG: Cache cleared for folder: $folderPath")
            } catch (e: Exception) {
                println("ERROR: Failed to clear cache for folder $folderPath: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    // Dialog state management
    fun setItemToDelete(item: LocalItem?) {
        _uiState.value = _uiState.value.copy(itemToDelete = item)
    }

    fun setShowDeleteConfirmDialog(show: Boolean) {
        _uiState.value = _uiState.value.copy(showDeleteConfirmDialog = show)
    }

    fun setShowDeleteZipWithPermissionDialog(show: Boolean) {
        _uiState.value = _uiState.value.copy(showDeleteZipWithPermissionDialog = show)
    }

    // Reading status management
    fun updateReadingStatus(zipFile: LocalItem.ZipFile, currentIndex: Int) {
        viewModelScope.launch {
            try {
                println("=== ViewModel.updateReadingStatus START ===")
                println("DEBUG: ViewModel - Input parameters:")
                println("DEBUG:   File: ${zipFile.name}")
                println("DEBUG:   File Path: ${zipFile.file.absolutePath}")
                println("DEBUG:   Current Index: $currentIndex")
                println("DEBUG:   ZipFile.totalImageCount: ${zipFile.totalImageCount}")
                
                // デバッグ: 更新前の全データを表示
                directZipHandler.getUnifiedDataManager().debugPrintFileData(zipFile.file.absolutePath)
                
                // 現在の読書状態を確認（更新前）
                val beforeStatus = repository.getReadingStatusSync(zipFile.file.absolutePath)
                val beforePosition = repository.getReadingPositionSync(zipFile.file.absolutePath)
                println("DEBUG: ViewModel - Before update:")
                println("DEBUG:   Before Status: $beforeStatus")
                println("DEBUG:   Before Position: $beforePosition")

                // Repository経由で統一データ管理システムに更新
                repository.updateReadingStatus(zipFile, currentIndex)

                // State immediately update (high-performance UI updates)
                val newProgress = repository.getReadingProgressSync(zipFile.file.absolutePath)
                _readingStates[zipFile.file.absolutePath] = newProgress

                println("DEBUG: ViewModel - After update:")
                println("DEBUG:   New Status: ${newProgress.status}")
                println("DEBUG:   New Position: ${newProgress.currentIndex}")
                println("DEBUG:   State Cache Updated: ${_readingStates.containsKey(zipFile.file.absolutePath)}")
                
                // デバッグ: 更新後の全データを表示
                directZipHandler.getUnifiedDataManager().debugPrintFileData(zipFile.file.absolutePath)
                println("=== ViewModel.updateReadingStatus END ===")

            } catch (e: Exception) {
                println("ERROR: ViewModel.updateReadingStatus failed: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    fun markAsCompleted(zipFile: LocalItem.ZipFile) {
        updateReadingStatus(zipFile, zipFile.totalImageCount - 1)
    }

    fun markAsReading(zipFile: LocalItem.ZipFile, currentIndex: Int) {
        updateReadingStatus(zipFile, currentIndex)
    }

    fun markAsUnread(zipFile: LocalItem.ZipFile) {
        updateReadingStatus(zipFile, 0)
    }

    // テスト用: 読書状態を強制的に更新
    fun testUpdateReadingStatus(
        zipFile: LocalItem.ZipFile,
        status: com.celstech.satendroid.ui.models.ReadingStatus
    ) {
        viewModelScope.launch {
            try {
                val position = when (status) {
                    com.celstech.satendroid.ui.models.ReadingStatus.UNREAD -> 0
                    com.celstech.satendroid.ui.models.ReadingStatus.READING -> {
                        if (zipFile.totalImageCount > 0) zipFile.totalImageCount / 2 else 0
                    }

                    com.celstech.satendroid.ui.models.ReadingStatus.COMPLETED -> {
                        if (zipFile.totalImageCount > 0) zipFile.totalImageCount - 1 else 0
                    }
                }

                // Repository経由で統一データ管理システムに更新
                repository.updateReadingStatus(zipFile, position)

                // Update State immediately (high-performance UI updates)
                val newProgress = repository.getReadingProgressSync(zipFile.file.absolutePath)
                _readingStates[zipFile.file.absolutePath] = newProgress

                println("DEBUG: ViewModel test updated reading status - File: ${zipFile.name}, Position: $position, Status: ${newProgress.status}")

            } catch (e: Exception) {
                println("DEBUG: Failed to test update reading status: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    // デバッグ用: 現在のディレクトリを再スキャン
    fun refreshCurrentDirectory() {
        scanDirectory(_uiState.value.currentPath)
    }

    // ZIPファイルを開いたときに呼び出す（読書状態を自動更新）
    fun onZipFileOpened(zipFile: LocalItem.ZipFile) {
        viewModelScope.launch {
            try {
                // ファイルを開いた時点で「読書中」に設定（まだ何も見ていなくても）
                // 少なくとも1ページ目を見たとして扱う
                repository.updateReadingStatus(zipFile, 0)

                // Update State immediately (high-performance UI updates)
                val newProgress = repository.getReadingProgressSync(zipFile.file.absolutePath)
                _readingStates[zipFile.file.absolutePath] = newProgress

                println("DEBUG: ViewModel marked file as opened - File: ${zipFile.name}, Status: ${newProgress.status}")

            } catch (e: Exception) {
                println("DEBUG: Failed to update reading status on file open: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    // 画像ビューアーから戻ってきたときに呼び出す（読書状態を再取得）
    fun onReturnFromImageViewer() {
        refreshCurrentDirectory()
    }

    override fun onCleared() {
        super.onCleared()
        // ViewModelが破棄される際にメモリキャッシュをクリア
        directZipHandler.clearMemoryCacheAsync()
    }

    companion object {
        fun provideFactory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val repository = LocalFileRepository(context)
                    val navigationManager = LocalFileNavigationManager()
                    val selectionManager = SelectionManager()
                    val directZipHandler = DirectZipImageHandler(context)
                    val fileNavigationManager = FileNavigationManager(context)
                    return LocalFileViewModel(
                        repository,
                        navigationManager,
                        selectionManager,
                        directZipHandler,
                        fileNavigationManager
                    ) as T
                }
            }
    }
}
