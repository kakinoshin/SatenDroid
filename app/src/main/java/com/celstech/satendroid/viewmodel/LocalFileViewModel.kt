package com.celstech.satendroid.viewmodel

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import com.celstech.satendroid.ui.models.HeaderSettings
import com.celstech.satendroid.ui.models.CloudProviderInfo
import com.celstech.satendroid.ui.models.ConnectionStatus
import com.celstech.satendroid.ui.models.PendingHeaderAction
import com.celstech.satendroid.utils.DirectZipImageHandler
import com.celstech.satendroid.utils.ReadingStateManager
import com.celstech.satendroid.utils.ReadingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ファイル操作のState Machine状態
 */
sealed class FileOperationState {
    object Idle : FileOperationState()
    data class ClosingFile(val filePath: String) : FileOperationState()
    object SavingData : FileOperationState()
    data class OpeningFile(val filePath: String) : FileOperationState()
}

/**
 * ファイル操作の待機キュー
 */
sealed class PendingFileOperation {
    data class CloseFile(val filePath: String, val currentPage: Int, val totalPages: Int) : PendingFileOperation()
    data class OpenFile(val filePath: String) : PendingFileOperation()
}

/**
 * ローカルファイル管理のViewModel - 再設計版
 *
 * 設計原則:
 * 1. ReadingStateManagerを唯一の状態管理・永続化責任者とする
 * 2. State Machineで操作順序を保証
 * 3. UIは参照のみ、更新はViewModelを経由
 * 4. 処理中はユーザーに通知
 */
class LocalFileViewModel(
    private val repository: LocalFileRepository,
    private val navigationManager: LocalFileNavigationManager,
    private val selectionManager: SelectionManager,
    val directZipHandler: DirectZipImageHandler,
    private val fileNavigationManager: FileNavigationManager,
    val readingStateManager: ReadingStateManager
) : ViewModel() {

    // UI state
    private val _uiState = MutableStateFlow(LocalFileUiState())
    val uiState: StateFlow<LocalFileUiState> = _uiState.asStateFlow()

    // State Machine状態
    private val _fileOperationState = MutableStateFlow<FileOperationState>(FileOperationState.Idle)
    val fileOperationState: StateFlow<FileOperationState> = _fileOperationState.asStateFlow()

    // 待機キュー
    private val pendingOperations = mutableListOf<PendingFileOperation>()
    private val operationMutex = Mutex()

    // エラーメッセージ
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /**
     * 読書状態を取得（UIから呼び出される）
     */
    fun getReadingState(filePath: String): ReadingState {
        return readingStateManager.getState(filePath)
    }

    /**
     * 読書進捗をStateとして取得（Compose用）
     * StateFlowを監視することでキャッシュ更新時に自動再描画
     */
    @Composable
    fun getReadingProgressState(filePath: String): State<com.celstech.satendroid.utils.ReadingProgress?> {
        // 初期状態を取得
        val initialState = remember(filePath) {
            val state = readingStateManager.getState(filePath)
            com.celstech.satendroid.utils.ReadingProgress(
                status = state.status,
                currentIndex = state.currentPage
            )
        }
        
        // キャッシュ更新トリガーを監視してStateを生成
        return produceState(
            initialValue = initialState,
            key1 = filePath
        ) {
            // StateFlowを監視
            readingStateManager.cacheUpdateTrigger.collect { _ ->
                val state = readingStateManager.getState(filePath)
                value = com.celstech.satendroid.utils.ReadingProgress(
                    status = state.status,
                    currentIndex = state.currentPage
                )
            }
        }
    }

    /**
     * ページを更新（RAM上のみ、保存はしない）
     */
    fun updateCurrentPage(filePath: String, page: Int, totalPages: Int) {
        readingStateManager.updateCurrentPage(filePath, page, totalPages)
        
        // フィルターが適用されている場合は再適用
        val currentState = _uiState.value
        if (currentState.filterType != ReadingFilterType.ALL) {
            val filteredItems = applyReadingFilter(currentState.localItems, currentState.filterType)
            _uiState.value = currentState.copy(filteredLocalItems = filteredItems)
        }
    }

    /**
     * ファイルを閉じる（State Machine制御）
     */
    fun closeFile(filePath: String, currentPage: Int, totalPages: Int) {
        viewModelScope.launch {
            operationMutex.withLock {
                // すでに処理中の場合は待機キューに追加
                if (_fileOperationState.value !is FileOperationState.Idle) {
                    pendingOperations.add(PendingFileOperation.CloseFile(filePath, currentPage, totalPages))
                    println("DEBUG: File close operation queued: $filePath")
                    return@launch
                }

                executeCloseFile(filePath, currentPage, totalPages)
            }
        }
    }

    /**
     * ファイルを閉じる処理を実行
     */
    private suspend fun executeCloseFile(filePath: String, currentPage: Int, totalPages: Int) {
        try {
            // State: Idle → ClosingFile
            _fileOperationState.value = FileOperationState.ClosingFile(filePath)
            println("DEBUG: State Machine: Idle → ClosingFile($filePath)")

            // 現在のページ情報をRAMに更新
            readingStateManager.updateCurrentPage(filePath, currentPage, totalPages)

            // State: ClosingFile → SavingData
            _fileOperationState.value = FileOperationState.SavingData
            println("DEBUG: State Machine: ClosingFile → SavingData")

            // 同期保存を実行
            val result = readingStateManager.saveStateSync(filePath)

            result.onSuccess {
                println("DEBUG: File closed and saved successfully: $filePath")
                _errorMessage.value = null
            }.onFailure { error ->
                println("ERROR: Failed to save reading state: ${error.message}")
                _errorMessage.value = "保存に失敗しました: ${error.message}"
            }

            // State: SavingData → Idle
            _fileOperationState.value = FileOperationState.Idle
            println("DEBUG: State Machine: SavingData → Idle")

            // 待機キューの処理
            processNextOperation()

        } catch (e: Exception) {
            println("ERROR: Exception during closeFile: ${e.message}")
            e.printStackTrace()
            _errorMessage.value = "エラーが発生しました: ${e.message}"
            _fileOperationState.value = FileOperationState.Idle
        }
    }

    /**
     * ファイルを開く（State Machine制御）
     */
    fun openFile(filePath: String) {
        viewModelScope.launch {
            operationMutex.withLock {
                // すでに処理中の場合は待機キューに追加
                if (_fileOperationState.value !is FileOperationState.Idle) {
                    pendingOperations.add(PendingFileOperation.OpenFile(filePath))
                    println("DEBUG: File open operation queued: $filePath")
                    return@launch
                }

                executeOpenFile(filePath)
            }
        }
    }

    /**
     * ファイルを開く処理を実行
     */
    private suspend fun executeOpenFile(filePath: String) {
        try {
            // State: Idle → OpeningFile
            _fileOperationState.value = FileOperationState.OpeningFile(filePath)
            println("DEBUG: State Machine: Idle → OpeningFile($filePath)")

            // 既存データをメモリキャッシュに読み込み
            readingStateManager.loadStateToCache(filePath)
            
            val state = readingStateManager.getState(filePath)
            println("DEBUG: File opened - Status: ${state.status}, Page: ${state.currentPage}/${state.totalPages}")

            // State: OpeningFile → Idle
            _fileOperationState.value = FileOperationState.Idle
            println("DEBUG: State Machine: OpeningFile → Idle")

            // 待機キューの処理
            processNextOperation()

        } catch (e: Exception) {
            println("ERROR: Exception during openFile: ${e.message}")
            e.printStackTrace()
            _errorMessage.value = "ファイルを開けませんでした: ${e.message}"
            _fileOperationState.value = FileOperationState.Idle
        }
    }

    /**
     * 次の待機操作を処理
     */
    private suspend fun processNextOperation() {
        operationMutex.withLock {
            if (pendingOperations.isNotEmpty() && _fileOperationState.value is FileOperationState.Idle) {
                val nextOp = pendingOperations.removeAt(0)
                println("DEBUG: Processing next operation from queue: $nextOp")

                when (nextOp) {
                    is PendingFileOperation.CloseFile -> {
                        executeCloseFile(nextOp.filePath, nextOp.currentPage, nextOp.totalPages)
                    }
                    is PendingFileOperation.OpenFile -> {
                        executeOpenFile(nextOp.filePath)
                    }
                }
            }
        }
    }

    /**
     * エラーメッセージをクリア
     */
    fun clearErrorMessage() {
        _errorMessage.value = null
    }

    // Header state management
    fun setHeaderState(headerState: HeaderState, trigger: String = "manual") {
        val currentState = _uiState.value
        
        if (currentState.isHeaderLocked) {
            println("DEBUG: Header state change blocked - header is locked")
            return
        }
        
        if (currentState.isHeaderAnimating && headerState != HeaderState.TRANSITIONING) {
            _uiState.value = currentState.copy(
                pendingHeaderAction = PendingHeaderAction.ChangeState(headerState)
            )
            return
        }
        
        _uiState.value = currentState.copy(
            headerState = headerState,
            lastGestureTimestamp = System.currentTimeMillis(),
            isHeaderAnimating = headerState == HeaderState.TRANSITIONING,
            pendingHeaderAction = null
        )
        
        println("DEBUG: Header state changed to $headerState (trigger: $trigger)")
    }

    fun toggleHeaderState() {
        val currentState = _uiState.value.headerState
        val newState = when (currentState) {
            HeaderState.COLLAPSED, HeaderState.PREVIEW_COLLAPSE -> HeaderState.EXPANDED
            HeaderState.EXPANDED, HeaderState.PREVIEW_EXPAND -> HeaderState.COLLAPSED
            HeaderState.TRANSITIONING -> return
        }
        setHeaderState(newState, "toggle")
    }

    fun expandHeader() = setHeaderState(HeaderState.EXPANDED, "expand")
    fun collapseHeader() = setHeaderState(HeaderState.COLLAPSED, "collapse")
    fun autoCollapseHeader() = setHeaderState(HeaderState.COLLAPSED, "auto_collapse")
    
    fun updateHeaderSettings(newSettings: HeaderSettings) {
        val currentState = _uiState.value
        val validatedSettings = newSettings.validate()
        _uiState.value = currentState.copy(headerSettings = validatedSettings)
        println("DEBUG: Header settings updated")
    }
    
    fun setHeaderLocked(locked: Boolean) {
        _uiState.value = _uiState.value.copy(isHeaderLocked = locked)
    }
    
    fun executePendingHeaderAction() {
        val currentState = _uiState.value
        val pendingAction = currentState.pendingHeaderAction
        
        if (pendingAction != null && !currentState.isHeaderAnimating) {
            when (pendingAction) {
                is PendingHeaderAction.ChangeState -> {
                    setHeaderState(pendingAction.targetState, "pending_execution")
                }
                is PendingHeaderAction.UpdateSettings -> {
                    updateHeaderSettings(pendingAction.newSettings)
                }
                is PendingHeaderAction.RefreshProviders -> {
                    refreshCloudProviders(pendingAction.forceRefresh)
                }
            }
        }
    }
    
    // Cloud Provider管理
    fun refreshCloudProviders(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            try {
                val currentState = _uiState.value
                val currentProviders = currentState.cloudProviders
                
                val refreshInterval = currentState.headerSettings.providerStatusRefreshInterval
                val shouldRefresh = forceRefresh || currentProviders.isEmpty() || 
                    currentProviders.any { provider ->
                        provider.lastSyncTime?.let { lastSync ->
                            System.currentTimeMillis() - lastSync > refreshInterval
                        } ?: true
                    }
                
                if (!shouldRefresh) {
                    println("DEBUG: Cloud providers refresh skipped - not needed")
                    return@launch
                }
                
                val updatedProviders = updateCloudProvidersStatus(currentProviders)
                _uiState.value = currentState.copy(cloudProviders = updatedProviders)
                println("DEBUG: Cloud providers refreshed - ${updatedProviders.size} providers")
                
            } catch (e: Exception) {
                println("ERROR: Failed to refresh cloud providers: ${e.message}")
                e.printStackTrace()
            }
        }
    }
    
    private suspend fun updateCloudProvidersStatus(
        currentProviders: List<CloudProviderInfo>
    ): List<CloudProviderInfo> {
        return listOf(
            CloudProviderInfo(
                id = "dropbox",
                name = "Dropbox",
                isAuthenticated = true,
                connectionStatus = ConnectionStatus.CONNECTED,
                lastSyncTime = System.currentTimeMillis()
            ),
            CloudProviderInfo(
                id = "googledrive",
                name = "Google Drive",
                isAuthenticated = false,
                connectionStatus = ConnectionStatus.DISCONNECTED,
                lastSyncTime = null
            ),
            CloudProviderInfo(
                id = "onedrive",
                name = "OneDrive",
                isAuthenticated = true,
                connectionStatus = ConnectionStatus.SYNCING,
                lastSyncTime = System.currentTimeMillis() - 60000L
            )
        )
    }
    
    fun authenticateCloudProvider(providerId: String) {
        viewModelScope.launch {
            try {
                val currentState = _uiState.value
                val updatedProviders = currentState.cloudProviders.map { provider ->
                    if (provider.id == providerId) {
                        provider.copy(
                            isAuthenticated = true,
                            connectionStatus = ConnectionStatus.CONNECTING
                        )
                    } else provider
                }
                
                _uiState.value = currentState.copy(cloudProviders = updatedProviders)
                kotlinx.coroutines.delay(2000)
                
                val finalProviders = updatedProviders.map { provider ->
                    if (provider.id == providerId) {
                        provider.copy(
                            connectionStatus = ConnectionStatus.CONNECTED,
                            lastSyncTime = System.currentTimeMillis()
                        )
                    } else provider
                }
                
                _uiState.value = _uiState.value.copy(cloudProviders = finalProviders)
                println("DEBUG: Cloud provider $providerId authenticated successfully")
                
            } catch (e: Exception) {
                println("ERROR: Failed to authenticate cloud provider $providerId: ${e.message}")
            }
        }
    }
    
    fun disconnectCloudProvider(providerId: String) {
        viewModelScope.launch {
            val currentState = _uiState.value
            val updatedProviders = currentState.cloudProviders.map { provider ->
                if (provider.id == providerId) {
                    provider.copy(
                        isAuthenticated = false,
                        connectionStatus = ConnectionStatus.DISCONNECTED,
                        lastSyncTime = null,
                        errorMessage = null
                    )
                } else provider
            }
            
            _uiState.value = currentState.copy(cloudProviders = updatedProviders)
            println("DEBUG: Cloud provider $providerId disconnected")
        }
    }
    
    fun initializeCloudProviders() {
        refreshCloudProviders(forceRefresh = true)
    }

    // Filter functionality
    fun setReadingFilter(filterType: ReadingFilterType) {
        val currentState = _uiState.value
        val newFilteredItems = applyReadingFilter(currentState.localItems, filterType)

        _uiState.value = currentState.copy(
            filterType = filterType,
            filteredLocalItems = newFilteredItems,
            isSelectionMode = false,
            selectedItems = emptySet()
        )

        println("DEBUG: Filter applied - Type: $filterType, Items: ${newFilteredItems.size}/${currentState.localItems.size}")
    }

    private fun applyReadingFilter(
        items: List<LocalItem>,
        filterType: ReadingFilterType
    ): List<LocalItem> {
        return when (filterType) {
            ReadingFilterType.ALL -> items
            ReadingFilterType.UNREAD -> items.filter { item ->
                if (item is LocalItem.ZipFile) {
                    val state = getReadingState(item.file.absolutePath)
                    state.status == ReadingStatus.UNREAD
                } else true
            }
            ReadingFilterType.READING -> items.filter { item ->
                if (item is LocalItem.ZipFile) {
                    val state = getReadingState(item.file.absolutePath)
                    state.status == ReadingStatus.READING
                } else false
            }
            ReadingFilterType.COMPLETED -> items.filter { item ->
                if (item is LocalItem.ZipFile) {
                    val state = getReadingState(item.file.absolutePath)
                    state.status == ReadingStatus.COMPLETED
                } else false
            }
            ReadingFilterType.HIDE_COMPLETED -> items.filter { item ->
                if (item is LocalItem.ZipFile) {
                    val state = getReadingState(item.file.absolutePath)
                    state.status != ReadingStatus.COMPLETED
                } else true
            }
        }
    }

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

                // メモリキャッシュに読書状態を一括読み込み
                items.filterIsInstance<LocalItem.ZipFile>().forEach { zipFile ->
                    readingStateManager.loadStateToCache(zipFile.file.absolutePath)
                }

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
                readingStateManager.clearFileState(item.file.absolutePath)
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
                    readingStateManager.clearFolderStates(item.path)
                    directZipHandler.onFolderDeleted(item.path)
                    println("DEBUG: ViewModel - Folder deleted successfully: ${item.name}")
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

            _uiState.value.selectedItems.forEach { item ->
                when (item) {
                    is LocalItem.ZipFile -> {
                        readingStateManager.clearFileState(item.file.absolutePath)
                        println("DEBUG: File deleted in batch: ${item.file.name}")
                    }
                    is LocalItem.Folder -> {
                        readingStateManager.clearFolderStates(item.path)
                        directZipHandler.onFolderDeleted(item.path)
                    }
                }
            }

            _uiState.value = _uiState.value.copy(
                selectedItems = emptySet(),
                isSelectionMode = false
            )
            scanDirectory(_uiState.value.currentPath)
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

    // 画像ビューアーから戻ってきたときの処理
    fun onReturnFromImageViewer() {
        refreshCurrentDirectory()
    }

    fun refreshCurrentDirectory() {
        scanDirectory(_uiState.value.currentPath)
    }

    override fun onCleared() {
        super.onCleared()
        readingStateManager.clearMemoryCache()
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
                    val readingStateManager = ReadingStateManager(context)
                    return LocalFileViewModel(
                        repository,
                        navigationManager,
                        selectionManager,
                        directZipHandler,
                        fileNavigationManager,
                        readingStateManager
                    ) as T
                }
            }
    }
}
