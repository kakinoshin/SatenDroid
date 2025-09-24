package com.celstech.satendroid.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.celstech.satendroid.navigation.LocalFileNavigationManager
import com.celstech.satendroid.navigation.FileNavigationManager
import com.celstech.satendroid.repository.LocalFileRepository
import com.celstech.satendroid.selection.SelectionManager
import com.celstech.satendroid.ui.models.LocalFileUiState
import com.celstech.satendroid.ui.models.LocalItem
import com.celstech.satendroid.ui.models.ReadingFilterType
import com.celstech.satendroid.ui.models.ReadingStatus
import com.celstech.satendroid.ui.models.HeaderState
import com.celstech.satendroid.utils.DirectZipImageHandler
import com.celstech.satendroid.utils.SimpleReadingDataManager
import com.celstech.satendroid.utils.ReadingProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap

/**
 * ローカルファイル管理のViewModel（シンプル化版）
 *
 * 変更点:
 * - SimpleReadingDataManagerを使用
 * - 単一キャッシュレイヤー
 * - 即座保存
 */
class LocalFileViewModel(
    private val repository: LocalFileRepository,
    private val navigationManager: LocalFileNavigationManager,
    private val selectionManager: SelectionManager,
    val directZipHandler: DirectZipImageHandler,
    private val fileNavigationManager: FileNavigationManager,
    val readingDataManager: SimpleReadingDataManager // 新システム（public）
) : ViewModel() {

    // UI state
    private val _uiState = MutableStateFlow(LocalFileUiState())
    val uiState: StateFlow<LocalFileUiState> = _uiState.asStateFlow()

    // 単一キャッシュレイヤー（ReadingProgress形式）
    private val _readingStates = mutableStateMapOf<String, ReadingProgress>()
    val readingStates: SnapshotStateMap<String, ReadingProgress> = _readingStates

    /**
     * ファイルの読書状態をメモリキャッシュに読み込み
     */
    private fun loadReadingStatesForFiles(items: List<LocalItem>) {
        items.filterIsInstance<LocalItem.ZipFile>().forEach { zipFile ->
            try {
                val progress = readingDataManager.getReadingProgress(zipFile.file.absolutePath)
                _readingStates[zipFile.file.absolutePath] = progress
                println("DEBUG: ViewModel loaded reading state - File: ${zipFile.name}, Status: ${progress.status}, Position: ${progress.currentIndex}")
            } catch (e: Exception) {
                println("ERROR: Failed to load reading state for ${zipFile.name}: ${e.message}")
            }
        }
    }

    /**
     * 読書進捗を取得（高速メモリアクセス）
     */
    fun getReadingProgress(filePath: String): ReadingProgress {
        return _readingStates[filePath] ?: readingDataManager.getReadingProgress(filePath)
    }

    // Header state management
    fun setHeaderState(headerState: HeaderState) {
        _uiState.value = _uiState.value.copy(headerState = headerState)
    }

    fun toggleHeaderState() {
        val currentState = _uiState.value.headerState
        val newState = when (currentState) {
            HeaderState.COLLAPSED -> HeaderState.EXPANDED
            HeaderState.EXPANDED -> HeaderState.COLLAPSED
            HeaderState.TRANSITIONING -> currentState // アニメーション中は状態変更しない
        }
        setHeaderState(newState)
    }

    fun expandHeader() {
        setHeaderState(HeaderState.EXPANDED)
    }

    fun collapseHeader() {
        setHeaderState(HeaderState.COLLAPSED)
    }

    // Filter functionality
    /**
     * 読書状態フィルターを設定
     */
    fun setReadingFilter(filterType: ReadingFilterType) {
        val currentState = _uiState.value
        val newFilteredItems = applyReadingFilter(currentState.localItems, filterType)

        _uiState.value = currentState.copy(
            filterType = filterType,
            filteredLocalItems = newFilteredItems,
            // フィルター変更時にセレクションモードをリセット
            isSelectionMode = false,
            selectedItems = emptySet()
        )

        println("DEBUG: Filter applied - Type: $filterType, Items: ${newFilteredItems.size}/${currentState.localItems.size}")
    }

    /**
     * フィルターロジックの適用
     */
    private fun applyReadingFilter(
        items: List<LocalItem>,
        filterType: ReadingFilterType
    ): List<LocalItem> {
        return when (filterType) {
            ReadingFilterType.ALL -> items
            ReadingFilterType.UNREAD -> items.filter { item ->
                if (item is LocalItem.ZipFile) {
                    val progress = getReadingProgress(item.file.absolutePath)
                    progress.status == ReadingStatus.UNREAD
                } else true // フォルダは常に表示
            }

            ReadingFilterType.READING -> items.filter { item ->
                if (item is LocalItem.ZipFile) {
                    val progress = getReadingProgress(item.file.absolutePath)
                    progress.status == ReadingStatus.READING
                } else false // フォルダは非表示
            }

            ReadingFilterType.COMPLETED -> items.filter { item ->
                if (item is LocalItem.ZipFile) {
                    val progress = getReadingProgress(item.file.absolutePath)
                    progress.status == ReadingStatus.COMPLETED
                } else false // フォルダは非表示
            }

            ReadingFilterType.HIDE_COMPLETED -> items.filter { item ->
                if (item is LocalItem.ZipFile) {
                    val progress = getReadingProgress(item.file.absolutePath)
                    progress.status != ReadingStatus.COMPLETED // 既読以外を表示
                } else true // フォルダは常に表示
            }
        }
    }

    /**
     * 現在表示中のアイテムリストを取得（フィルター適用済み）
     */
    fun getDisplayItems(): List<LocalItem> {
        val currentState = _uiState.value
        return if (currentState.filterType == ReadingFilterType.ALL) {
            currentState.localItems
        } else {
            currentState.filteredLocalItems
        }
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
        val displayItems = getDisplayItems()
        val newSelectedItems = selectionManager.selectAll(displayItems)
        _uiState.value = _uiState.value.copy(selectedItems = newSelectedItems)
    }

    fun deselectAll() {
        val newSelectedItems = selectionManager.deselectAll()
        _uiState.value = _uiState.value.copy(selectedItems = newSelectedItems)
    }

    fun getSelectionStatus(): SelectionManager.SelectionStatus {
        val displayItems = getDisplayItems()
        return selectionManager.getSelectionStatus(
            _uiState.value.selectedItems,
            displayItems
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

                // メモリキャッシュに読書状態を読み込み
                loadReadingStatesForFiles(items)

                // 現在のフィルターを再適用
                val currentFilter = _uiState.value.filterType
                if (currentFilter != ReadingFilterType.ALL) {
                    val filteredItems = applyReadingFilter(items, currentFilter)
                    _uiState.value = _uiState.value.copy(filteredLocalItems = filteredItems)
                }

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
                // メモリキャッシュをクリア（読書データは age 機能で後から削除）
                _readingStates.remove(item.file.absolutePath)

                println("DEBUG: File deleted: ${item.file.name}")
            }
        }
        return result
    }

    fun deleteFolder(item: LocalItem.Folder) {
        viewModelScope.launch {
            try {
                val result = repository.deleteFolder(item)
                if (result) {
                    clearCacheForFolder(item.path)
                    println("DEBUG: ViewModel - Folder deleted successfully: ${item.name}")
                    // 削除後にディレクトリを再スキャンして表示を更新
                    scanDirectory(_uiState.value.currentPath)
                } else {
                    println("DEBUG: ViewModel - Failed to delete folder: ${item.name}")
                }
            } catch (e: Exception) {
                println("ERROR: ViewModel - Exception during folder deletion: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    fun deleteSelectedItems() {
        viewModelScope.launch {
            repository.deleteItems(_uiState.value.selectedItems)

            // 削除されたアイテムのメモリキャッシュをクリア（読書データは age 機能で後から削除）
            _uiState.value.selectedItems.forEach { item ->
                when (item) {
                    is LocalItem.ZipFile -> {
                        _readingStates.remove(item.file.absolutePath)

                        println("DEBUG: File deleted in batch: ${item.file.name}")
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
        // フォルダー削除時のデータクリアは不要（age機能で後から削除）
        // メモリキャッシュのみクリア
        viewModelScope.launch {
            try {
                println("DEBUG: Clearing memory cache for folder: $folderPath")

                // メモリキャッシュから該当ファイルを削除
                val keysToRemove = _readingStates.keys.filter { filePath ->
                    filePath.contains(folderPath)
                }
                keysToRemove.forEach { key ->
                    _readingStates.remove(key)
                }

                // DirectZipImageHandlerでもフォルダー削除処理を実行（メモリキャッシュのみ）
                directZipHandler.onFolderDeleted(folderPath)

                println("DEBUG: Memory cache cleared for folder: $folderPath")
            } catch (e: Exception) {
                println("ERROR: Failed to clear memory cache for folder $folderPath: ${e.message}")
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

    // Reading status management（シンプル化版）
    fun updateReadingStatus(zipFile: LocalItem.ZipFile, currentIndex: Int) {
        viewModelScope.launch {
            try {
                println("=== ViewModel.updateReadingStatus START (Simplified) ===")
                println("DEBUG: File: ${zipFile.name}")
                println("DEBUG: Current Index: $currentIndex")
                println("DEBUG: Total Images: ${zipFile.totalImageCount}")

                // 即座保存（バッチ処理なし）
                readingDataManager.saveReadingData(
                    filePath = zipFile.file.absolutePath,
                    currentPage = currentIndex,
                    totalPages = zipFile.totalImageCount
                )

                // メモリキャッシュ更新
                val newProgress = readingDataManager.getReadingProgress(zipFile.file.absolutePath)
                _readingStates[zipFile.file.absolutePath] = newProgress

                // フィルターが適用されている場合は再適用
                val currentState = _uiState.value
                if (currentState.filterType != ReadingFilterType.ALL) {
                    val filteredItems =
                        applyReadingFilter(currentState.localItems, currentState.filterType)
                    _uiState.value = currentState.copy(filteredLocalItems = filteredItems)
                }

                println("DEBUG: Updated - Status: ${newProgress.status}, Position: ${newProgress.currentIndex}")
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

    // ZIPファイルを開いたときの処理
    fun onZipFileOpened(zipFile: LocalItem.ZipFile) {
        viewModelScope.launch {
            try {
                // ファイルを開いた時点で読書中に設定
                readingDataManager.saveReadingData(
                    filePath = zipFile.file.absolutePath,
                    currentPage = 0,
                    totalPages = zipFile.totalImageCount
                )

                // メモリキャッシュ更新
                val newProgress = readingDataManager.getReadingProgress(zipFile.file.absolutePath)
                _readingStates[zipFile.file.absolutePath] = newProgress

                // フィルターが適用されている場合は再適用
                val currentState = _uiState.value
                if (currentState.filterType != ReadingFilterType.ALL) {
                    val filteredItems =
                        applyReadingFilter(currentState.localItems, currentState.filterType)
                    _uiState.value = currentState.copy(filteredLocalItems = filteredItems)
                }

                println("DEBUG: File opened - Status: ${newProgress.status}")

            } catch (e: Exception) {
                println("ERROR: Failed to update reading status on file open: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    // 画像ビューアーから戻ってきたときの処理
    fun onReturnFromImageViewer() {
        refreshCurrentDirectory()
    }

    // デバッグ用
    fun refreshCurrentDirectory() {
        scanDirectory(_uiState.value.currentPath)
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
                    val readingDataManager = SimpleReadingDataManager(context) // 新システム
                    return LocalFileViewModel(
                        repository,
                        navigationManager,
                        selectionManager,
                        directZipHandler,
                        fileNavigationManager,
                        readingDataManager
                    ) as T
                }
            }
    }
}
