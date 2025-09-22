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
 * ダウンロードの状態
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
 * ダウンロード進捗
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
    val completedDownloads: Int = 0,
    val failedDownloads: Int = 0,
    val totalBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
    val overallSpeed: Double = 0.0 // bytes per second
) {
    val totalDownloads: Int
        get() = activeDownloads + queuedDownloads + completedDownloads + failedDownloads

    val overallProgress: Float
        get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes.toFloat()) else 0f

    val isActive: Boolean
        get() = activeDownloads > 0 || queuedDownloads > 0
}
