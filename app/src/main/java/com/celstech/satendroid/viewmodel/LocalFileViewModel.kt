package com.celstech.satendroid.viewmodel

import android.content.Context
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class LocalFileViewModel(private val context: Context) : ViewModel() {
    // UI state
    private val _uiState = MutableStateFlow(LocalFileUiState())
    val uiState: StateFlow<LocalFileUiState> = _uiState.asStateFlow()

    // Navigation
    fun navigateToFolder(folderPath: String) {
        val currentState = _uiState.value
        _uiState.value = currentState.copy(
            pathHistory = currentState.pathHistory + currentState.currentPath,
            currentPath = folderPath,
            isSelectionMode = false,
            selectedItems = emptySet()
        )
        scanDirectory(folderPath)
    }

    fun navigateBack() {
        val currentState = _uiState.value
        if (currentState.pathHistory.isNotEmpty()) {
            val previousPath = currentState.pathHistory.last()
            _uiState.value = currentState.copy(
                pathHistory = currentState.pathHistory.dropLast(1),
                currentPath = previousPath,
                isSelectionMode = false,
                selectedItems = emptySet()
            )
            scanDirectory(previousPath)
        }
    }

    // Selection mode
    fun enterSelectionMode(initialItem: LocalItem) {
        _uiState.value = _uiState.value.copy(
            isSelectionMode = true,
            selectedItems = setOf(initialItem)
        )
    }

    fun exitSelectionMode() {
        _uiState.value = _uiState.value.copy(
            isSelectionMode = false,
            selectedItems = emptySet()
        )
    }

    fun toggleItemSelection(item: LocalItem) {
        val currentState = _uiState.value
        val newSelectedItems = if (currentState.selectedItems.contains(item)) {
            currentState.selectedItems - item
        } else {
            currentState.selectedItems + item
        }
        _uiState.value = currentState.copy(selectedItems = newSelectedItems)
    }

    fun selectAll() {
        _uiState.value = _uiState.value.copy(
            selectedItems = _uiState.value.localItems.toSet()
        )
    }

    fun deselectAll() {
        _uiState.value = _uiState.value.copy(selectedItems = emptySet())
    }

    // Scan directory
    fun scanDirectory(path: String = "") {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true)
            
            try {
                val allItems = mutableListOf<LocalItem>()
                val baseDirectories = mutableListOf<File>()
                
                // App-specific external files directory
                val appDownloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                if (appDownloadsDir != null && appDownloadsDir.exists()) {
                    baseDirectories.add(appDownloadsDir)
                }
                
                // Public Downloads directory
                try {
                    val publicDownloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    if (publicDownloadsDir != null && publicDownloadsDir.exists()) {
                        baseDirectories.add(publicDownloadsDir)
                    }
                } catch (e: Exception) {
                    println("DEBUG: Cannot access public downloads directory: ${e.message}")
                }
                
                // Scan the specified path within each base directory
                for (baseDir in baseDirectories) {
                    val targetDir = if (path.isEmpty()) baseDir else File(baseDir, path)
                    
                    if (!targetDir.exists() || !targetDir.isDirectory) continue
                    
                    println("DEBUG: Scanning directory: ${targetDir.absolutePath}")
                    
                    val children = targetDir.listFiles() ?: continue
                    
                    for (child in children) {
                        when {
                            child.isDirectory -> {
                                val subFiles = child.listFiles() ?: emptyArray()
                                var hasZipFiles = false
                                var hasSubfolders = false
                                var zipCount = 0
                                
                                for (subFile in subFiles) {
                                    when {
                                        subFile.isFile && subFile.extension.lowercase() == "zip" -> {
                                            hasZipFiles = true
                                            zipCount++
                                        }
                                        subFile.isDirectory -> {
                                            val subDirFiles = subFile.listFiles()
                                            if (!subDirFiles.isNullOrEmpty()) {
                                                hasSubfolders = true
                                            }
                                        }
                                    }
                                }
                                
                                if (hasZipFiles || hasSubfolders) {
                                    val relativePath = if (path.isEmpty()) 
                                        child.name 
                                    else 
                                        "$path/${child.name}"
                                    
                                    allItems.add(
                                        LocalItem.Folder(
                                            name = child.name,
                                            path = relativePath,
                                            lastModified = child.lastModified(),
                                            zipCount = zipCount
                                        )
                                    )
                                }
                            }
                            child.isFile && child.extension.lowercase() == "zip" -> {
                                val relativePath = if (path.isEmpty()) 
                                    child.name 
                                else 
                                    "$path/${child.name}"
                                
                                allItems.add(
                                    LocalItem.ZipFile(
                                        name = child.name,
                                        path = relativePath,
                                        lastModified = child.lastModified(),
                                        size = child.length(),
                                        file = child
                                    )
                                )
                            }
                        }
                    }
                }
                
                // Remove duplicates and sort
                val sortedItems = allItems
                    .distinctBy { it.path }
                    .sortedWith(compareBy<LocalItem> { it !is LocalItem.Folder }.thenBy { it.name })
                
                _uiState.value = _uiState.value.copy(
                    localItems = sortedItems,
                    isRefreshing = false
                )
                
                println("DEBUG: Found ${sortedItems.size} items in path '$path'")
                
            } catch (e: Exception) {
                println("DEBUG: Error scanning directory '$path': ${e.message}")
                e.printStackTrace()
                _uiState.value = _uiState.value.copy(isRefreshing = false)
            }
        }
    }

    // Delete operations
    fun deleteFile(item: LocalItem.ZipFile): Boolean {
        return try {
            item.file.delete()
        } catch (e: Exception) {
            println("DEBUG: Failed to delete file: ${e.message}")
            false
        }
    }

    fun deleteFolder(item: LocalItem.Folder): Boolean {
        return try {
            val baseDirectories = mutableListOf<File>()
            
            val appDownloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            if (appDownloadsDir != null && appDownloadsDir.exists()) {
                baseDirectories.add(appDownloadsDir)
            }
            
            try {
                val publicDownloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (publicDownloadsDir != null && publicDownloadsDir.exists()) {
                    baseDirectories.add(publicDownloadsDir)
                }
            } catch (e: Exception) {
                println("DEBUG: Cannot access public downloads directory: ${e.message}")
            }
            
            var deleted = false
            for (baseDir in baseDirectories) {
                val folderFile = File(baseDir, item.path)
                if (folderFile.exists() && folderFile.isDirectory) {
                    deleted = folderFile.deleteRecursively() || deleted
                }
            }
            
            deleted
        } catch (e: Exception) {
            println("DEBUG: Failed to delete folder: ${e.message}")
            false
        }
    }

    fun deleteSelectedItems() {
        viewModelScope.launch {
            var successCount = 0
            var failCount = 0
            
            _uiState.value.selectedItems.forEach { item ->
                val success = when (item) {
                    is LocalItem.ZipFile -> deleteFile(item)
                    is LocalItem.Folder -> deleteFolder(item)
                }
                
                if (success) successCount++ else failCount++
            }
            
            println("DEBUG: Deleted $successCount items, failed to delete $failCount items")
            
            // Clear selection and refresh
            _uiState.value = _uiState.value.copy(
                selectedItems = emptySet(),
                isSelectionMode = false
            )
            scanDirectory(_uiState.value.currentPath)
        }
    }

    fun setItemToDelete(item: LocalItem?) {
        _uiState.value = _uiState.value.copy(itemToDelete = item)
    }

    fun setShowDeleteConfirmDialog(show: Boolean) {
        _uiState.value = _uiState.value.copy(showDeleteConfirmDialog = show)
    }

    fun setShowDeleteZipWithPermissionDialog(show: Boolean) {
        _uiState.value = _uiState.value.copy(showDeleteZipWithPermissionDialog = show)
    }

    companion object {
        fun provideFactory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return LocalFileViewModel(context) as T
            }
        }
    }
}

// UI State
data class LocalFileUiState(
    val localItems: List<LocalItem> = emptyList(),
    val isRefreshing: Boolean = false,
    val currentPath: String = "",
    val pathHistory: List<String> = emptyList(),
    val isSelectionMode: Boolean = false,
    val selectedItems: Set<LocalItem> = emptySet(),
    val showDeleteConfirmDialog: Boolean = false,
    val itemToDelete: LocalItem? = null,
    val showDeleteZipWithPermissionDialog: Boolean = false
)

// Data models
sealed class LocalItem {
    abstract val name: String
    abstract val path: String
    abstract val lastModified: Long
    
    data class Folder(
        override val name: String,
        override val path: String,
        override val lastModified: Long,
        val zipCount: Int = 0
    ) : LocalItem()
    
    data class ZipFile(
        override val name: String,
        override val path: String,
        override val lastModified: Long,
        val size: Long,
        val file: File
    ) : LocalItem()
}
