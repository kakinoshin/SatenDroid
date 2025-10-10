package com.celstech.satendroid.ui.models

import android.graphics.Bitmap
import kotlinx.serialization.Serializable
import java.io.File

/**
 * ローカルファイル関連のデータモデル
 * Phase 2: ヘッダー設定とCloud Provider統合
 */

// 読書状況を表すenum（シリアライズ対応）
@Serializable
enum class ReadingStatus {
    UNREAD,     // 未読
    READING,    // 読書中（開いたが最後まで見ていない）
    COMPLETED   // 既読（最後まで見た）
}

// 読書状態フィルターのタイプ
@Serializable
enum class ReadingFilterType {
    ALL,             // すべて
    UNREAD,          // 未読のみ
    READING,         // 読書中のみ 
    COMPLETED,       // 既読のみ
    HIDE_COMPLETED   // 既読を非表示（未読+読書中）
}

// UI State - Phase 2で拡張
data class LocalFileUiState(
    val localItems: List<LocalItem> = emptyList(),
    val filteredLocalItems: List<LocalItem> = emptyList(),
    val isRefreshing: Boolean = false,
    val currentPath: String = "",
    val pathHistory: List<String> = emptyList(),
    val isSelectionMode: Boolean = false,
    val selectedItems: Set<LocalItem> = emptySet(),
    val showDeleteConfirmDialog: Boolean = false,
    val itemToDelete: LocalItem? = null,
    val showDeleteZipWithPermissionDialog: Boolean = false,
    val filterType: ReadingFilterType = ReadingFilterType.ALL,
    
    // Phase 2: 拡張されたヘッダー状態管理
    val headerState: HeaderState = HeaderState.COLLAPSED,
    val headerSettings: HeaderSettings = HeaderSettings(),
    val cloudProviders: List<CloudProviderInfo> = emptyList(),
    val lastGestureTimestamp: Long = 0L,
    val isHeaderLocked: Boolean = false, // 一時的にヘッダー操作を無効化
    
    // パフォーマンス関連
    val isHeaderAnimating: Boolean = false,
    val pendingHeaderAction: PendingHeaderAction? = null
)

// ヘッダーの遅延実行アクション
sealed class PendingHeaderAction {
    data class ChangeState(val targetState: HeaderState, val delay: Long = 0L) : PendingHeaderAction()
    data class UpdateSettings(val newSettings: HeaderSettings) : PendingHeaderAction()
    data class RefreshProviders(val forceRefresh: Boolean = false) : PendingHeaderAction()
}

// Data models
sealed class LocalItem {
    abstract val name: String
    abstract val path: String
    abstract val lastModified: Long

    data class Folder(
        override val name: String,
        override val path: String,
        override val lastModified: Long,
        val zipCount: Int = 0,
        val hasCloudSync: Boolean = false, // Phase 2: Cloud同期状態
        val syncStatus: CloudSyncStatus = CloudSyncStatus.NOT_SYNCED
    ) : LocalItem()

    data class ZipFile(
        override val name: String,
        override val path: String,
        override val lastModified: Long,
        val size: Long,
        val file: File,
        val thumbnail: Bitmap? = null,
        val totalImageCount: Int = 0,
        val cloudBackupInfo: CloudBackupInfo? = null // Phase 2: Cloudバックアップ情報
    ) : LocalItem()
}

/**
 * Cloud同期状態
 * Phase 2: Dynamic Cloud Provider Management
 */
enum class CloudSyncStatus {
    NOT_SYNCED,
    SYNCED,
    SYNCING,
    SYNC_ERROR,
    OUTDATED
}

/**
 * Cloudバックアップ情報
 */
data class CloudBackupInfo(
    val providerId: String,
    val lastBackupTime: Long,
    val backupSize: Long,
    val isAutoBackupEnabled: Boolean = false,
    val syncStatus: CloudSyncStatus = CloudSyncStatus.NOT_SYNCED
)

/**
 * ファイル操作の結果
 * Phase 2: エラー処理の強化
 */
sealed class FileOperationResult {
    object Success : FileOperationResult()
    data class Error(
        val message: String, 
        val exception: Throwable? = null,
        val isRetryable: Boolean = false
    ) : FileOperationResult()
    object InProgress : FileOperationResult()
    object Cancelled : FileOperationResult()
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
        val availableLength =
            maxLength - (if (extension.isNotEmpty()) extension.length + 1 else 0) - 3 // "..."分を引く

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
    
    /**
     * Cloud Provider名の短縮表示
     * Phase 2: Cloud Provider表示最適化
     */
    fun truncateProviderName(providerName: String, maxLength: Int = 15): String {
        return if (providerName.length <= maxLength) {
            providerName
        } else {
            "${providerName.take(maxLength - 3)}..."
        }
    }
}

/**
 * ヘッダー状態の履歴管理
 * Phase 2: 状態履歴とパフォーマンス最適化
 */
data class HeaderStateHistory(
    val states: List<HeaderStateEntry> = emptyList(),
    val maxHistorySize: Int = 10
) {
    data class HeaderStateEntry(
        val state: HeaderState,
        val timestamp: Long,
        val trigger: String // "user_swipe", "auto_collapse", "tap", etc.
    )
    
    fun addEntry(state: HeaderState, trigger: String): HeaderStateHistory {
        val newEntry = HeaderStateEntry(state, System.currentTimeMillis(), trigger)
        val updatedStates = (states + newEntry).takeLast(maxHistorySize)
        return copy(states = updatedStates)
    }
    
    fun getLastStateChange(): HeaderStateEntry? = states.lastOrNull()
    
    fun getFrequentStates(): Map<HeaderState, Int> {
        return states.groupingBy { it.state }.eachCount()
    }
}
