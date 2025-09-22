package com.celstech.satendroid.ui.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

/**
 * クラウドサービスの種類
 */
@Serializable
enum class CloudType {
    DROPBOX,
    GOOGLE_DRIVE,
    ONEDRIVE,
    LOCAL
}

/**
 * ダウンロードの優先度
 */
@Serializable
enum class DownloadPriority {
    HIGH,
    NORMAL,
    LOW
}

/**
 * キューのダウンロード状態（未処理のみ）
 */
enum class DownloadQueueStatus {
    QUEUED,          // キューに追加済み
    DOWNLOADING,     // ダウンロード中
    PAUSED          // 一時停止中
}

/**
 * 履歴のダウンロード状態（処理済みのみ）
 */
enum class DownloadHistoryStatus {
    COMPLETED,       // 完了
    FAILED,          // 失敗
    CANCELLED        // キャンセル済み
}

/**
 * 従来との互換性のための統合ステータス
 */
enum class DownloadStatus {
    QUEUED,          // キューに追加済み
    DOWNLOADING,     // ダウンロード中
    PAUSED,          // 一時停止中
    COMPLETED,       // 完了
    FAILED,          // 失敗
    CANCELLED        // キャンセル済み
}

/**
 * ダウンロード要求
 */
@Parcelize
@Serializable
data class DownloadRequest(
    val id: String,
    val cloudType: CloudType,
    val fileName: String,
    val remotePath: String,
    val localPath: String,
    val fileSize: Long,
    val priority: DownloadPriority = DownloadPriority.NORMAL,
    val metadata: Map<String, String> = emptyMap() // クラウド固有のメタデータ
) : Parcelable {
    companion object {
        fun generateId(): String = java.util.UUID.randomUUID().toString()
    }
}

/**
 * ダウンロード進捗情報
 */
data class DownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val downloadSpeed: Double, // bytes per second
    val estimatedTimeRemaining: Long // seconds
) {
    val progressPercentage: Float
        get() = if (totalBytes > 0) (bytesDownloaded.toFloat() / totalBytes.toFloat()) else 0f
}

/**
 * ダウンロードキューアイテム（未処理）
 */
data class DownloadQueueItem(
    val downloadId: String,
    val status: DownloadQueueStatus,
    val request: DownloadRequest,
    val progress: DownloadProgress?,
    val queuePosition: Int,
    val startTime: Long = System.currentTimeMillis(),
    val errorMessage: String? = null
) {
    val isActive: Boolean
        get() = status == DownloadQueueStatus.DOWNLOADING || status == DownloadQueueStatus.QUEUED

    val canPause: Boolean
        get() = status == DownloadQueueStatus.DOWNLOADING || status == DownloadQueueStatus.QUEUED

    val canResume: Boolean
        get() = status == DownloadQueueStatus.PAUSED

    val canCancel: Boolean
        get() = status == DownloadQueueStatus.DOWNLOADING || status == DownloadQueueStatus.QUEUED || status == DownloadQueueStatus.PAUSED
}

/**
 * ダウンロード履歴アイテム（処理済み）
 */
data class DownloadHistoryItem(
    val downloadId: String,
    val status: DownloadHistoryStatus,
    val request: DownloadRequest,
    val result: DownloadResult,
    val completedTime: Long,
    val startTime: Long,
    val errorMessage: String? = null
) {
    val canRetry: Boolean
        get() = status == DownloadHistoryStatus.FAILED

    val isCompleted: Boolean
        get() = status == DownloadHistoryStatus.COMPLETED

    val isFailed: Boolean
        get() = status == DownloadHistoryStatus.FAILED
}

/**
 * ダウンロードキュー（未処理のみ）
 */
data class DownloadQueue(
    val items: List<DownloadQueueItem> = emptyList()
) {
    val activeCount: Int
        get() = items.count { it.status == DownloadQueueStatus.DOWNLOADING }

    val queuedCount: Int
        get() = items.count { it.status == DownloadQueueStatus.QUEUED }

    val pausedCount: Int
        get() = items.count { it.status == DownloadQueueStatus.PAUSED }

    val totalCount: Int
        get() = items.size

    val isEmpty: Boolean
        get() = items.isEmpty()
}

/**
 * ダウンロード履歴（処理済みのみ）
 */
data class DownloadHistory(
    val items: List<DownloadHistoryItem> = emptyList()
) {
    val completedCount: Int
        get() = items.count { it.status == DownloadHistoryStatus.COMPLETED }

    val failedCount: Int
        get() = items.count { it.status == DownloadHistoryStatus.FAILED }

    val cancelledCount: Int
        get() = items.count { it.status == DownloadHistoryStatus.CANCELLED }

    val totalCount: Int
        get() = items.size

    val isEmpty: Boolean
        get() = items.isEmpty()
}

/**
 * 従来のダウンロード進捗（互換性のため）
 */
data class DownloadProgressInfo(
    val downloadId: String,
    val status: DownloadStatus,
    val fileName: String,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val downloadSpeed: Double, // bytes per second
    val estimatedTimeRemaining: Long, // seconds
    val errorMessage: String? = null,
    val startTime: Long = System.currentTimeMillis(),
    val completedTime: Long? = null
) {
    val progressPercentage: Float
        get() = if (totalBytes > 0) (bytesDownloaded.toFloat() / totalBytes.toFloat()) else 0f

    val isActive: Boolean
        get() = status == DownloadStatus.DOWNLOADING || status == DownloadStatus.QUEUED

    val isCompleted: Boolean
        get() = status == DownloadStatus.COMPLETED

    val isFailed: Boolean
        get() = status == DownloadStatus.FAILED

    val canRetry: Boolean
        get() = status == DownloadStatus.FAILED || status == DownloadStatus.CANCELLED

    val canPause: Boolean
        get() = status == DownloadStatus.DOWNLOADING || status == DownloadStatus.QUEUED

    val canResume: Boolean
        get() = status == DownloadStatus.PAUSED

    val canCancel: Boolean
        get() = status == DownloadStatus.DOWNLOADING || status == DownloadStatus.QUEUED || status == DownloadStatus.PAUSED
}

/**
 * ダウンロード結果
 */
sealed class DownloadResult {
    data class Success(val localFilePath: String) : DownloadResult()
    data class Failure(val error: String, val exception: Throwable? = null) : DownloadResult()
    object Cancelled : DownloadResult()
}

/**
 * ダウンロードキューの全体状態
 */
data class DownloadQueueState(
    val activeDownloads: Int = 0,
    val queuedDownloads: Int = 0,
    val pausedDownloads: Int = 0,
    val completedDownloads: Int = 0,
    val failedDownloads: Int = 0,
    val cancelledDownloads: Int = 0,
    val totalBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
    val overallSpeed: Double = 0.0 // bytes per second
) {
    val totalDownloads: Int
        get() = activeDownloads + queuedDownloads + pausedDownloads + completedDownloads + failedDownloads + cancelledDownloads

    val overallProgress: Float
        get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes.toFloat()) else 0f

    val isActive: Boolean
        get() = activeDownloads > 0 || queuedDownloads > 0
}
