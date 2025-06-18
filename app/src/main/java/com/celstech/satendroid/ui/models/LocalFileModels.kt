package com.celstech.satendroid.ui.models

import android.graphics.Bitmap
import java.io.File

/**
 * ローカルファイル関連のデータモデル
 */

// 読書状況を表すenum
enum class ReadingStatus {
    UNREAD,     // 未読
    READING,    // 読書中（開いたが最後まで見ていない）
    COMPLETED   // 既読（最後まで見た）
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
        val file: File,
        val readingStatus: ReadingStatus = ReadingStatus.UNREAD,
        val thumbnail: Bitmap? = null,
        val currentImageIndex: Int = 0,
        val totalImageCount: Int = 0
    ) : LocalItem()
}

/**
 * ファイル名を表示用に加工するユーティリティ
 */
object FileNameUtils {
    /**
     * 長いファイル名を先頭と末尾が見えるように短縮する
     * @param fileName 元のファイル名
     * @param maxLength 最大表示文字数
     * @return 短縮されたファイル名
     */
    fun truncateFileName(fileName: String, maxLength: Int = 30): String {
        if (fileName.length <= maxLength) return fileName
        
        // 拡張子を取得
        val extension = fileName.substringAfterLast('.', "")
        val nameWithoutExtension = fileName.substringBeforeLast('.')
        
        // 拡張子も含めた場合の調整
        val availableLength = maxLength - (if (extension.isNotEmpty()) extension.length + 1 else 0) - 3 // "..."分を引く
        
        if (availableLength <= 0) return fileName
        
        val frontLength = availableLength / 2
        val backLength = availableLength - frontLength
        
        val front = nameWithoutExtension.take(frontLength)
        val back = nameWithoutExtension.takeLast(backLength)
        
        return if (extension.isNotEmpty()) {
            "$front...$back.$extension"
        } else {
            "$front...$back"
        }
    }
}
