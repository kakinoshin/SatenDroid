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
import com.celstech.satendroid.utils.ZipImageHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * ローカルファイル管理のViewModel
 * Repository、Navigation、Selectionの各Managerを使用してスリム化
 */
class LocalFileViewModel(
    private val repository: LocalFileRepository,
    private val navigationManager: LocalFileNavigationManager,
    private val selectionManager: SelectionManager,
    private val zipImageHandler: ZipImageHandler,
    private val fileNavigationManager: FileNavigationManager
) : ViewModel() {

    // UI state
    private val _uiState = MutableStateFlow(LocalFileUiState())
    val uiState: StateFlow<LocalFileUiState> = _uiState.asStateFlow()

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
                zipImageHandler.onZipFileDeleted(zipUri, item.file)
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
                        zipImageHandler.onZipFileDeleted(zipUri, item.file)
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
        // フォルダ内のZIPファイルのキャッシュをクリアする処理
        // 実際の実装では、フォルダ内のZIPファイルを再帰的に検索して
        // 各ファイルのキャッシュをクリアする必要があります
        // ここでは簡略化して、すべてのキャッシュをクリア
        zipImageHandler.getCacheManager().clearCache()
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
    fun updateReadingStatus(zipFile: LocalItem.ZipFile, currentIndex: Int, totalCount: Int? = null) {
        viewModelScope.launch {
            try {
                val updatedZipFile = repository.updateReadingStatus(zipFile, currentIndex, totalCount)
                
                // UIStateのlocalItemsを更新
                val currentItems = _uiState.value.localItems
                val updatedItems = currentItems.map { item ->
                    if (item is LocalItem.ZipFile && item.path == zipFile.path) {
                        updatedZipFile
                    } else {
                        item
                    }
                }
                
                _uiState.value = _uiState.value.copy(localItems = updatedItems)
                
            } catch (e: Exception) {
                println("DEBUG: Failed to update reading status: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    fun markAsCompleted(zipFile: LocalItem.ZipFile) {
        updateReadingStatus(zipFile, zipFile.totalImageCount - 1, zipFile.totalImageCount)
    }

    fun markAsReading(zipFile: LocalItem.ZipFile, currentIndex: Int) {
        updateReadingStatus(zipFile, currentIndex, zipFile.totalImageCount)
    }

    fun markAsUnread(zipFile: LocalItem.ZipFile) {
        updateReadingStatus(zipFile, 0, zipFile.totalImageCount)
    }

    companion object {
        fun provideFactory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val repository = LocalFileRepository(context)
                val navigationManager = LocalFileNavigationManager()
                val selectionManager = SelectionManager()
                val zipImageHandler = ZipImageHandler(context)
                val fileNavigationManager = FileNavigationManager(context)
                return LocalFileViewModel(
                    repository, 
                    navigationManager, 
                    selectionManager, 
                    zipImageHandler, 
                    fileNavigationManager
                ) as T
            }
        }
    }
}
