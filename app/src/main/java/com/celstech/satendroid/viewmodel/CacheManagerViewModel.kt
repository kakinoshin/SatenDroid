package com.celstech.satendroid.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.celstech.satendroid.utils.ReadingStateManager
import com.celstech.satendroid.utils.SavedStateInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * キャッシュマネージャのViewModel
 * 保存された読書状態を管理・削除する
 */
class CacheManagerViewModel(
    private val readingStateManager: ReadingStateManager
) : ViewModel() {

    // UI State
    private val _uiState = MutableStateFlow(CacheManagerUiState())
    val uiState: StateFlow<CacheManagerUiState> = _uiState.asStateFlow()

    // 選択モード
    private val _isSelectionMode = MutableStateFlow(false)
    val isSelectionMode: StateFlow<Boolean> = _isSelectionMode.asStateFlow()

    // 選択されたアイテム
    private val _selectedItems = MutableStateFlow<Set<String>>(emptySet())
    val selectedItems: StateFlow<Set<String>> = _selectedItems.asStateFlow()

    init {
        loadSavedStates()
    }

    /**
     * 保存された状態を読み込む
     */
    fun loadSavedStates() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            try {
                val states = readingStateManager.getAllSavedStates()
                
                // 統計情報を計算
                val totalStates = states.size
                val existingFiles = states.count { it.fileExists }
                val deletedFiles = totalStates - existingFiles
                val unreadCount = states.count { it.status == com.celstech.satendroid.ui.models.ReadingStatus.UNREAD }
                val readingCount = states.count { it.status == com.celstech.satendroid.ui.models.ReadingStatus.READING }
                val completedCount = states.count { it.status == com.celstech.satendroid.ui.models.ReadingStatus.COMPLETED }

                _uiState.value = _uiState.value.copy(
                    savedStates = states,
                    filteredStates = states,
                    isLoading = false,
                    totalStatesCount = totalStates,
                    existingFilesCount = existingFiles,
                    deletedFilesCount = deletedFiles,
                    unreadCount = unreadCount,
                    readingCount = readingCount,
                    completedCount = completedCount
                )

                println("DEBUG: Loaded ${states.size} saved states")
            } catch (e: Exception) {
                println("ERROR: Failed to load saved states: ${e.message}")
                e.printStackTrace()
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    /**
     * フィルターを適用
     */
    fun applyFilter(filter: CacheFilter) {
        val currentState = _uiState.value
        val filtered = when (filter) {
            CacheFilter.ALL -> currentState.savedStates
            CacheFilter.EXISTING_FILES -> currentState.savedStates.filter { it.fileExists }
            CacheFilter.DELETED_FILES -> currentState.savedStates.filter { !it.fileExists }
            CacheFilter.UNREAD -> currentState.savedStates.filter { it.status == com.celstech.satendroid.ui.models.ReadingStatus.UNREAD }
            CacheFilter.READING -> currentState.savedStates.filter { it.status == com.celstech.satendroid.ui.models.ReadingStatus.READING }
            CacheFilter.COMPLETED -> currentState.savedStates.filter { it.status == com.celstech.satendroid.ui.models.ReadingStatus.COMPLETED }
        }

        _uiState.value = currentState.copy(
            currentFilter = filter,
            filteredStates = filtered
        )
    }

    /**
     * 選択モードを切り替え
     */
    fun toggleSelectionMode() {
        _isSelectionMode.value = !_isSelectionMode.value
        if (!_isSelectionMode.value) {
            _selectedItems.value = emptySet()
        }
    }

    /**
     * アイテムの選択を切り替え
     */
    fun toggleItemSelection(fileHash: String) {
        val current = _selectedItems.value
        _selectedItems.value = if (current.contains(fileHash)) {
            current - fileHash
        } else {
            current + fileHash
        }
    }

    /**
     * 全選択
     */
    fun selectAll() {
        val currentState = _uiState.value
        _selectedItems.value = currentState.filteredStates.map { it.fileHash }.toSet()
    }

    /**
     * 全選択解除
     */
    fun deselectAll() {
        _selectedItems.value = emptySet()
    }

    /**
     * 選択されたアイテムを削除
     */
    fun deleteSelectedStates() {
        viewModelScope.launch {
            val hashes = _selectedItems.value.toList()
            if (hashes.isEmpty()) return@launch

            try {
                readingStateManager.manuallyDeleteStates(hashes)
                
                // UIを更新
                _selectedItems.value = emptySet()
                _isSelectionMode.value = false
                
                // データを再読み込み
                loadSavedStates()
                
                println("DEBUG: Deleted ${hashes.size} states")
            } catch (e: Exception) {
                println("ERROR: Failed to delete states: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    /**
     * 単一のアイテムを削除
     */
    fun deleteSingleState(fileHash: String) {
        viewModelScope.launch {
            try {
                readingStateManager.manuallyDeleteState(fileHash)
                loadSavedStates()
                println("DEBUG: Deleted state: ${fileHash.take(8)}...")
            } catch (e: Exception) {
                println("ERROR: Failed to delete state: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    /**
     * 削除済みファイルの状態を全削除
     */
    fun deleteDeletedFilesStates() {
        viewModelScope.launch {
            val currentState = _uiState.value
            val deletedFileHashes = currentState.savedStates
                .filter { !it.fileExists }
                .map { it.fileHash }

            if (deletedFileHashes.isEmpty()) return@launch

            try {
                readingStateManager.manuallyDeleteStates(deletedFileHashes)
                loadSavedStates()
                println("DEBUG: Deleted ${deletedFileHashes.size} states for deleted files")
            } catch (e: Exception) {
                println("ERROR: Failed to delete states: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    /**
     * 全状態を削除
     */
    fun deleteAllStates() {
        viewModelScope.launch {
            try {
                readingStateManager.manuallyDeleteAllStates()
                loadSavedStates()
                println("DEBUG: Deleted all states")
            } catch (e: Exception) {
                println("ERROR: Failed to delete all states: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    companion object {
        fun provideFactory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val readingStateManager = ReadingStateManager(context)
                    return CacheManagerViewModel(readingStateManager) as T
                }
            }
    }
}

/**
 * キャッシュマネージャのUI State
 */
data class CacheManagerUiState(
    val savedStates: List<SavedStateInfo> = emptyList(),
    val filteredStates: List<SavedStateInfo> = emptyList(),
    val isLoading: Boolean = false,
    val currentFilter: CacheFilter = CacheFilter.ALL,
    val totalStatesCount: Int = 0,
    val existingFilesCount: Int = 0,
    val deletedFilesCount: Int = 0,
    val unreadCount: Int = 0,
    val readingCount: Int = 0,
    val completedCount: Int = 0
)

/**
 * キャッシュフィルター
 */
enum class CacheFilter {
    ALL,
    EXISTING_FILES,
    DELETED_FILES,
    UNREAD,
    READING,
    COMPLETED
}
