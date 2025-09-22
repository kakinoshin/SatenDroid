package com.celstech.satendroid.worker

import android.content.Context
import androidx.work.*
import com.celstech.satendroid.ui.models.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.*

/**
 * WorkManagerベースのダウンロードキュー管理
 * 元のDownloadQueueManagerと同じインターフェースを維持
 */
class WorkManagerDownloadQueueManager(
    private val context: Context,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val DOWNLOAD_WORK_TAG = "download_work"
        private const val MAX_CONCURRENT_DOWNLOADS = 2
    }

    private val workManager = WorkManager.getInstance(context)
    
    // ダウンロード進捗の状態
    private val _downloadProgress = MutableStateFlow<Map<String, DownloadProgressInfo>>(emptyMap())
    val downloadProgress = _downloadProgress.asStateFlow()
    
    // 全体の状態
    private val _queueState = MutableStateFlow(DownloadQueueState())
    val queueState = _queueState.asStateFlow()

    // ワークのUUIDとダウンロードIDのマッピング
    private val workIdMapping = mutableMapOf<String, UUID>()
    
    // ダウンロードリクエストの保存（リトライ用）
    private val downloadRequests = mutableMapOf<String, DownloadRequest>()

    init {
        // 既存のワーク状態を監視
        observeWorkStates()
    }

    /**
     * ダウンロードをキューに追加
     */
    suspend fun enqueueDownload(request: DownloadRequest) {
        // ダウンロードリクエストをJSON文字列に変換
        val requestJson = Json.encodeToString(request)

        // WorkManagerのコンストレイントを設定
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(false)
            .build()

        // ワークリクエストを作成
        val workRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_DOWNLOAD_REQUEST to requestJson))
            .setConstraints(constraints)
            .addTag(DOWNLOAD_WORK_TAG)
            .addTag("download_${request.id}")
            .build()

        // ワークIDをマッピングに追加
        workIdMapping[request.id] = workRequest.id
        
        // リクエストを保存（リトライ用）
        downloadRequests[request.id] = request

        // 初期進捗状態を追加
        val progressMap = _downloadProgress.value.toMutableMap()
        progressMap[request.id] = DownloadProgressInfo(
            downloadId = request.id,
            status = DownloadStatus.QUEUED,
            fileName = request.fileName,
            bytesDownloaded = 0L,
            totalBytes = request.fileSize,
            downloadSpeed = 0.0,
            estimatedTimeRemaining = 0L
        )
        _downloadProgress.value = progressMap

        // WorkManagerに登録（同時実行数制限付き）
        workManager.enqueueUniqueWork(
            "download_${request.id}",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )

        updateQueueState()
    }

    /**
     * ダウンロードをキャンセル
     */
    suspend fun cancelDownload(downloadId: String) {
        val workId = workIdMapping[downloadId] ?: return
        
        // WorkManagerでキャンセル
        workManager.cancelWorkById(workId)
        
        // 進捗状態を更新
        val progressMap = _downloadProgress.value.toMutableMap()
        val currentProgress = progressMap[downloadId]
        if (currentProgress != null) {
            progressMap[downloadId] = currentProgress.copy(status = DownloadStatus.CANCELLED)
            _downloadProgress.value = progressMap
        }
        
        // マッピングから削除
        workIdMapping.remove(downloadId)
        downloadRequests.remove(downloadId)
        
        updateQueueState()
    }

    /**
     * 全ダウンロードを一時停止（実際にはキャンセル）
     */
    suspend fun pauseAll() {
        // WorkManagerでは一時停止がないため、キャンセルを実行
        workManager.cancelAllWorkByTag(DOWNLOAD_WORK_TAG)
        
        // 進捗状態を更新
        val progressMap = _downloadProgress.value.toMutableMap()
        progressMap.entries.forEach { entry ->
            if (entry.value.status == DownloadStatus.DOWNLOADING || entry.value.status == DownloadStatus.QUEUED) {
                progressMap[entry.key] = entry.value.copy(status = DownloadStatus.CANCELLED)
            }
        }
        _downloadProgress.value = progressMap
        
        // マッピングをクリア
        workIdMapping.clear()
        downloadRequests.clear()
        
        updateQueueState()
    }

    /**
     * 失敗したダウンロードを再試行
     */
    suspend fun retryDownload(downloadId: String) {
        val originalRequest = downloadRequests[downloadId] ?: return
        val progressInfo = _downloadProgress.value[downloadId] ?: return
        if (progressInfo.status != DownloadStatus.FAILED) return

        // 新しいIDで再エンキュー
        val retryRequest = originalRequest.copy(id = DownloadRequest.generateId())
        enqueueDownload(retryRequest)
        
        // 古い失敗したエントリを削除
        val progressMap = _downloadProgress.value.toMutableMap()
        progressMap.remove(downloadId)
        _downloadProgress.value = progressMap
        
        downloadRequests.remove(downloadId)
        workIdMapping.remove(downloadId)
        
        updateQueueState()
    }

    /**
     * 完了したダウンロードをクリア
     */
    suspend fun clearCompleted() {
        val progressMap = _downloadProgress.value.toMutableMap()
        val completedIds = progressMap.entries
            .filter { it.value.status == DownloadStatus.COMPLETED }
            .map { it.key }

        completedIds.forEach { progressMap.remove(it) }
        _downloadProgress.value = progressMap

        updateQueueState()
    }

    /**
     * ワークの状態を監視
     */
    private fun observeWorkStates() {
        // WorkManagerのワーク状態をFlowで監視
        coroutineScope.launch {
            // すべてのダウンロードワークを継続的に監視
            while (true) {
                try {
                    val workInfos = workManager.getWorkInfosByTag(DOWNLOAD_WORK_TAG).get()
                    updateProgressFromWorkInfos(workInfos)
                    delay(1000) // 1秒間隔で更新
                } catch (e: Exception) {
                    println("DEBUG: Error observing work states: ${e.message}")
                    delay(5000) // エラー時は5秒待機
                }
            }
        }
    }

    /**
     * WorkInfoからプログレス情報を更新
     */
    private fun updateProgressFromWorkInfos(workInfos: List<WorkInfo>) {
        val progressMap = _downloadProgress.value.toMutableMap()

        workInfos.forEach { workInfo ->
            val downloadId = workIdMapping.entries
                .find { it.value == workInfo.id }?.key ?: return@forEach

            val currentProgress = progressMap[downloadId] ?: return@forEach

            // WorkInfoからステータスを更新
            val newStatus = when (workInfo.state) {
                WorkInfo.State.ENQUEUED -> DownloadStatus.QUEUED
                WorkInfo.State.RUNNING -> DownloadStatus.DOWNLOADING
                WorkInfo.State.SUCCEEDED -> DownloadStatus.COMPLETED
                WorkInfo.State.FAILED -> DownloadStatus.FAILED
                WorkInfo.State.CANCELLED -> DownloadStatus.CANCELLED
                WorkInfo.State.BLOCKED -> DownloadStatus.QUEUED
            }

            // プログレスデータを取得
            val progress = workInfo.progress.getFloat(DownloadWorker.KEY_PROGRESS, 0f)
            val bytesDownloaded = (currentProgress.totalBytes * progress).toLong()

            // エラーメッセージを取得
            val errorMessage = workInfo.outputData.getString(DownloadWorker.KEY_ERROR_MESSAGE)

            // 進捗情報を更新
            progressMap[downloadId] = currentProgress.copy(
                status = newStatus,
                bytesDownloaded = bytesDownloaded,
                errorMessage = errorMessage,
                completedTime = if (newStatus == DownloadStatus.COMPLETED) System.currentTimeMillis() else null
            )
        }

        _downloadProgress.value = progressMap
        updateQueueState()
    }

    /**
     * キューの全体状態を更新
     */
    private fun updateQueueState() {
        val progressValues = _downloadProgress.value.values

        val activeCount = progressValues.count { it.status == DownloadStatus.DOWNLOADING }
        val queuedCount = progressValues.count { it.status == DownloadStatus.QUEUED }
        val completedCount = progressValues.count { it.status == DownloadStatus.COMPLETED }
        val failedCount = progressValues.count { it.status == DownloadStatus.FAILED }

        val totalBytes = progressValues.sumOf { it.totalBytes }
        val downloadedBytes = progressValues.sumOf {
            if (it.status == DownloadStatus.COMPLETED) it.totalBytes else it.bytesDownloaded
        }

        val overallSpeed = progressValues
            .filter { it.status == DownloadStatus.DOWNLOADING }
            .sumOf { it.downloadSpeed }

        _queueState.value = DownloadQueueState(
            activeDownloads = activeCount,
            queuedDownloads = queuedCount,
            completedDownloads = completedCount,
            failedDownloads = failedCount,
            totalBytes = totalBytes,
            downloadedBytes = downloadedBytes,
            overallSpeed = overallSpeed
        )
    }

    /**
     * クリーンアップ
     */
    suspend fun cleanup() {
        // 全ジョブをキャンセル
        workManager.cancelAllWorkByTag(DOWNLOAD_WORK_TAG)
        
        // マッピングをクリア
        workIdMapping.clear()
        downloadRequests.clear()
        
        // WorkManagerは自動的にクリーンアップされるため、特別な処理は不要
        // Note: CoroutineScopeのキャンセルは呼び出し側の責任
    }
}
