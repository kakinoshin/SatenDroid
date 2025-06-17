package com.celstech.satendroid.ui.models

import java.io.File

/**
 * ローカルファイル関連のデータモデル
 */

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
