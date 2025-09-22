package com.celstech.satendroid.worker

import android.content.Context
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf

import com.celstech.satendroid.MainActivity
import com.celstech.satendroid.download.downloader.DropboxDownloader
import com.celstech.satendroid.dropbox.DropboxAuthManager
import com.celstech.satendroid.ui.models.CloudType
import com.celstech.satendroid.ui.models.DownloadProgressInfo
import com.celstech.satendroid.ui.models.DownloadRequest
import com.celstech.satendroid.ui.models.DownloadResult
import com.celstech.satendroid.ui.models.DownloadStatus
import com.celstech.satendroid.utils.FormatUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * WorkManagerベースのダウンロードワーカー
 *
 * 設計方針:
 * - WorkManager専用のResult型を使用
 * - 非suspend関数でのプログレス更新
 * - 適切なエラーハンドリング
 * - 他のWorkManagerコンポーネントとの整合性確保
 */
class DownloadWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val KEY_DOWNLOAD_REQUEST = "download_request"
        const val KEY_PROGRESS = "progress"
        const val KEY_STATUS = "status"
        const val KEY_ERROR_MESSAGE = "error_message"
        const val KEY_RESULT_PATH = "result_path"

        // 通知関連
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "download_worker_channel"
        private const val CHANNEL_NAME = "Download Worker"
    }

    private val dropboxAuthManager = DropboxAuthManager(applicationContext)
    private val notificationManager =
        applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        return@withContext try {
            println("DEBUG: DownloadWorker - Starting doWork()")

            // ダウンロードリクエストを取得
            val downloadRequestJson = inputData.getString(KEY_DOWNLOAD_REQUEST)
            if (downloadRequestJson == null) {
                println("DEBUG: DownloadWorker - No download request provided")
                return@withContext Result.failure(
                    workDataOf(KEY_ERROR_MESSAGE to "No download request provided")
                )
            }

            val downloadRequest = Json.decodeFromString<DownloadRequest>(downloadRequestJson)
            println("DEBUG: DownloadWorker - Processing download: ${downloadRequest.fileName}")

            // フォアグラウンド情報を設定
            setForeground(createForegroundInfo(downloadRequest))

            // ダウンロード実行
            when (downloadRequest.cloudType) {
                CloudType.DROPBOX -> {
                    downloadWithDropbox(downloadRequest)
                }
                // 他のクラウドサービスは将来追加予定
                else -> {
                    println("DEBUG: DownloadWorker - Unsupported cloud type: ${downloadRequest.cloudType}")
                    Result.failure(
                        workDataOf(KEY_ERROR_MESSAGE to "Unsupported cloud type: ${downloadRequest.cloudType}")
                    )
                }
            }

        } catch (e: Exception) {
            println("DEBUG: DownloadWorker - Failed with exception: ${e.message}")
            e.printStackTrace()
            Result.failure(
                workDataOf(KEY_ERROR_MESSAGE to (e.message ?: "Unknown error"))
            )
        }
    }

    /**
     * Dropboxダウンロードを実行
     */
    private suspend fun downloadWithDropbox(request: DownloadRequest): Result {
        val downloader = DropboxDownloader(dropboxAuthManager)

        return try {
            println("DEBUG: DownloadWorker - Starting Dropbox download for: ${request.fileName}")

            val downloadResult = downloader.downloadFile(request) { progressInfo ->
                // プログレスを更新（CoroutineScope内で実行）
                CoroutineScope(Dispatchers.Main).launch {
                    updateProgress(progressInfo)
                }

                // 通知を更新（同期処理）
                updateNotification(progressInfo)
            }

            when (downloadResult) {
                is DownloadResult.Success -> {
                    println("DEBUG: DownloadWorker - Download completed successfully: ${request.fileName}")
                    showCompletionNotification(request, true)
                    Result.success(
                        workDataOf(
                            KEY_STATUS to DownloadStatus.COMPLETED.name,
                            KEY_RESULT_PATH to downloadResult.localFilePath,
                            KEY_PROGRESS to 1.0f
                        )
                    )
                }

                is DownloadResult.Failure -> {
                    println("DEBUG: DownloadWorker - Download failed: ${request.fileName}, error: ${downloadResult.error}")
                    showCompletionNotification(request, false, downloadResult.error)
                    Result.failure(
                        workDataOf(
                            KEY_STATUS to DownloadStatus.FAILED.name,
                            KEY_ERROR_MESSAGE to downloadResult.error
                        )
                    )
                }

                is DownloadResult.Cancelled -> {
                    println("DEBUG: DownloadWorker - Download cancelled: ${request.fileName}")
                    Result.failure(
                        workDataOf(KEY_STATUS to DownloadStatus.CANCELLED.name)
                    )
                }
            }
        } catch (e: Exception) {
            println("DEBUG: DownloadWorker - Dropbox download exception: ${e.message}")
            e.printStackTrace()
            showCompletionNotification(request, false, e.message)
            Result.failure(
                workDataOf(
                    KEY_STATUS to DownloadStatus.FAILED.name,
                    KEY_ERROR_MESSAGE to (e.message ?: "Unknown error")
                )
            )
        }
    }

    /**
     * プログレス更新（suspend版、エラーハンドリング付き）
     */
    private suspend fun updateProgress(progressInfo: DownloadProgressInfo) {
        val progress = if (progressInfo.totalBytes > 0) {
            (progressInfo.bytesDownloaded.toFloat() / progressInfo.totalBytes.toFloat())
        } else {
            0f
        }

        try {
            setProgress(
                workDataOf(
                    KEY_PROGRESS to progress,
                    KEY_STATUS to progressInfo.status.name
                )
            )
        } catch (e: Exception) {
            // エラーハンドリング：プログレス更新の失敗はダウンロードを止めない
            println("DEBUG: DownloadWorker - Failed to update progress: ${e.message}")
        }
    }

    /**
     * フォアグラウンド情報を作成
     */
    private fun createForegroundInfo(request: DownloadRequest): ForegroundInfo {
        createNotificationChannel()

        val intent = Intent(applicationContext, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Downloading ${request.fileName}")
            .setContentText("Starting download...")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pendingIntent)
            .setProgress(100, 0, false)
            .setOngoing(true)
            .build()

        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    /**
     * 通知を更新
     */
    private fun updateNotification(progressInfo: DownloadProgressInfo) {
        val intent = Intent(applicationContext, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val progress = if (progressInfo.totalBytes > 0) {
            ((progressInfo.bytesDownloaded.toFloat() / progressInfo.totalBytes.toFloat()) * 100).toInt()
        } else {
            0
        }

        val speedText = if (progressInfo.downloadSpeed > 0) {
            " • ${FormatUtils.formatSpeed(progressInfo.downloadSpeed)}"
        } else ""

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Downloading ${progressInfo.fileName}")
            .setContentText("${progress}% complete${speedText}")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pendingIntent)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * 完了通知を表示
     */
    private fun showCompletionNotification(
        request: DownloadRequest,
        success: Boolean,
        errorMessage: String? = null
    ) {
        val intent = Intent(applicationContext, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val (title, text, icon) = if (success) {
            Triple(
                "Download completed",
                "${request.fileName} downloaded successfully",
                android.R.drawable.stat_sys_download_done
            )
        } else {
            Triple(
                "Download failed",
                "${request.fileName} failed: ${errorMessage ?: "Unknown error"}",
                android.R.drawable.stat_notify_error
            )
        }

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(icon)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        // 完了通知は別のIDで表示
        notificationManager.notify(NOTIFICATION_ID + 1, notification)
    }

    /**
     * 通知チャンネルを作成
     * minSdk 28以降なので、バージョンチェック不要
     */
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows download progress for cloud files"
            setShowBadge(true)
            enableVibration(false)
            enableLights(false)
        }

        notificationManager.createNotificationChannel(channel)
    }
}
