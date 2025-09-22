package com.celstech.satendroid.worker

import android.content.Context
import androidx.work.*
import com.celstech.satendroid.settings.DownloadSettings
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
 * 同時実行数制限機能付き
 */
class WorkManagerDownloadQueueManager(
    private val context: Context,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val DOWNLOAD_WORK_TAG = "download_work"
        private const val CONCURRENT_WORK_CHAIN_PREFIX = "concurrent_download_"
    }

    private val workManager = WorkManager.getInstance(context)
    private val downloadSettings = DownloadSettings.getInstance(context)
    
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
    
    // 同時実行制御用
    private val activeSlots = mutableSetOf<Int>()
    private val pendingQueue = mutableListOf<DownloadRequest>()

    init {
        // 既存のワーク状態を監視
        observeWorkStates()
        
        // 設定変更を監視してキューを調整
        observeSettingsChanges()
    }

    /**
     * ダウンロードをキューに追加
     */
    suspend fun enqueueDownload(request: DownloadRequest) {
        println("DEBUG: WorkManagerDownloadQueueManager - Enqueuing download: ${request.fileName}")
        
        // リクエストを保存
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
        
        // 同時実行数制限を考慮してエンキュー
        enqueueWithConcurrencyLimit(request)
        
        updateQueueState()
    }
    
    /**
     * 同時実行数制限を考慮したエンキュー
     */
    private suspend fun enqueueWithConcurrencyLimit(request: DownloadRequest) {
        val maxConcurrent = downloadSettings.getMaxConcurrentDownloads()
        
        // 利用可能なスロットを探す
        val availableSlot = findAvailableSlot(maxConcurrent)
        
        if (availableSlot != null) {
            // スロットが利用可能な場合、すぐに実行
            executeDownload(request, availableSlot)
        } else {
            // スロットが満杯の場合、ペンディングキューに追加
            synchronized(pendingQueue) {
                pendingQueue.add(request)
            }
            println("DEBUG: WorkManagerDownloadQueueManager - Added to pending queue: ${request.fileName}")
        }
    }
    
    /**
     * 利用可能なスロットを探す
     */
    private fun findAvailableSlot(maxConcurrent: Int): Int? {
        synchronized(activeSlots) {
            for (slot in 0 until maxConcurrent) {
                if (slot !in activeSlots) {
                    activeSlots.add(slot)
                    return slot
                }
            }
            return null
        }
    }
    
    /**
     * ダウンロードを実際に実行
     */
    private suspend fun executeDownload(request: DownloadRequest, slot: Int) {
        println("DEBUG: WorkManagerDownloadQueueManager - Executing download in slot $slot: ${request.fileName}")
        
        // ダウンロードリクエストをJSON文字列に変換
        val requestJson = Json.encodeToString(request)

        // WorkManagerのコンストレイントを設定
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(false)
            .build()

        // ワークリクエストを作成（スロット番号をタグに含める）
        val workRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf("download_request" to requestJson))
            .setConstraints(constraints)
            .addTag(DOWNLOAD_WORK_TAG)
            .addTag("download_${request.id}")
            .addTag("slot_$slot")
            .build()

        // ワークIDをマッピングに追加
        workIdMapping[request.id] = workRequest.id

        // スロット固有のワーク名で実行（同時実行数制限）
        workManager.enqueueUniqueWork(
            "${CONCURRENT_WORK_CHAIN_PREFIX}$slot",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            workRequest
        )
    }
    
    /**
     * スロットを解放してペンディングキューを処理
     */
    private suspend fun releaseSlotAndProcessQueue(slot: Int) {
        synchronized(activeSlots) {
            activeSlots.remove(slot)
            println("DEBUG: WorkManagerDownloadQueueManager - Released slot $slot")
        }
        
        // ペンディングキューから次のタスクを処理
        val nextRequest = synchronized(pendingQueue) {
            if (pendingQueue.isNotEmpty()) {
                pendingQueue.removeAt(0)
            } else {
                null
            }
        }
        
        if (nextRequest != null) {
            println("DEBUG: WorkManagerDownloadQueueManager - Processing next from queue: ${nextRequest.fileName}")
            enqueueWithConcurrencyLimit(nextRequest)
        }
    }

    /**
     * ダウンロードをキャンセル
     */
    suspend fun cancelDownload(downloadId: String) {
        val workId = workIdMapping[downloadId] ?: return
        
        // WorkManagerでキャンセル
        workManager.cancelWorkById(workId)
        
        // ペンディングキューからも削除
        synchronized(pendingQueue) {
            pendingQueue.removeAll { it.id == downloadId }
        }
        
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
        
        // ペンディングキューをクリア
        synchronized(pendingQueue) {
            pendingQueue.clear()
        }
        
        // アクティブスロットをクリア
        synchronized(activeSlots) {
            activeSlots.clear()
        }
        
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
     * 設定変更を監視
     */
    private fun observeSettingsChanges() {
        coroutineScope.launch {
            downloadSettings.maxConcurrentDownloads.collect { newMaxConcurrent ->
                handleConcurrencySettingChange(newMaxConcurrent)
            }
        }
    }
    
    /**
     * 同時実行数設定変更への対応
     */
    private suspend fun handleConcurrencySettingChange(newMaxConcurrent: Int) {
        println("DEBUG: WorkManagerDownloadQueueManager - Concurrency setting changed to: $newMaxConcurrent")
        
        // 現在のアクティブスロット数が新しい制限を超えている場合の処理
        val currentActiveCount = synchronized(activeSlots) { activeSlots.size }
        
        if (currentActiveCount > newMaxConcurrent) {
            // 超過分のワークをキャンセル（最新のスロットから）
            val slotsToCancel = synchronized(activeSlots) {
                activeSlots.filter { it >= newMaxConcurrent }.toList()
            }
            
            slotsToCancel.forEach { slot ->
                // そのスロットで実行中のワークをキャンセル
                cancelWorkBySlot(slot)
            }
        } else if (currentActiveCount < newMaxConcurrent) {
            // ペンディングキューから実行可能なタスクを処理
            processQueueForIncreasedCapacity()
        }
    }
    
    /**
     * 特定のスロットのワークをキャンセル
     */
    private suspend fun cancelWorkBySlot(slot: Int) {
        try {
            workManager.cancelAllWorkByTag("slot_$slot")
            releaseSlotAndProcessQueue(slot)
        } catch (e: Exception) {
            println("DEBUG: WorkManagerDownloadQueueManager - Error cancelling work for slot $slot: ${e.message}")
        }
    }
    
    /**
     * 増加した容量に対してペンディングキューを処理
     */
    private suspend fun processQueueForIncreasedCapacity() {
        val maxConcurrent = downloadSettings.getMaxConcurrentDownloads()
        val currentActive = synchronized(activeSlots) { activeSlots.size }
        val availableSlots = maxConcurrent - currentActive
        
        repeat(availableSlots) {
            val nextRequest = synchronized(pendingQueue) {
                if (pendingQueue.isNotEmpty()) {
                    pendingQueue.removeAt(0)
                } else {
                    null
                }
            }
            
            if (nextRequest != null) {
                enqueueWithConcurrencyLimit(nextRequest)
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
            val progress = workInfo.progress.getFloat("progress", 0f)
            val bytesDownloaded = (currentProgress.totalBytes * progress).toLong()

            // エラーメッセージを取得
            val errorMessage = workInfo.outputData.getString("error_message")

            // 進捗情報を更新
            progressMap[downloadId] = currentProgress.copy(
                status = newStatus,
                bytesDownloaded = bytesDownloaded,
                errorMessage = errorMessage,
                completedTime = if (newStatus == DownloadStatus.COMPLETED) System.currentTimeMillis() else null
            )
            
            // 完了またはキャンセル時にスロットを解放
            if (newStatus in listOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED, DownloadStatus.CANCELLED)) {
                // スロット番号を取得してスロットを解放
                val slotTag = workInfo.tags.find { it.startsWith("slot_") }
                if (slotTag != null) {
                    val slot = slotTag.substringAfter("slot_").toIntOrNull()
                    if (slot != null) {
                        coroutineScope.launch {
                            releaseSlotAndProcessQueue(slot)
                        }
                    }
                }
            }
        }

        _downloadProgress.value = progressMap
        updateQueueState()
    }

    /**
     * キューの全体状態を更新
     */
    private fun updateQueueState() {
        val progressValues = _downloadProgress.value.values
        val pendingCount = synchronized(pendingQueue) { pendingQueue.size }

        val activeCount = progressValues.count { it.status == DownloadStatus.DOWNLOADING }
        val queuedCount = progressValues.count { it.status == DownloadStatus.QUEUED } + pendingCount
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
        
        // ペンディングキューをクリア
        synchronized(pendingQueue) {
            pendingQueue.clear()
        }
        
        // アクティブスロットをクリア
        synchronized(activeSlots) {
            activeSlots.clear()
        }
        
        // マッピングをクリア
        workIdMapping.clear()
        downloadRequests.clear()
        
        // WorkManagerは自動的にクリーンアップされるため、特別な処理は不要
        // Note: CoroutineScopeのキャンセルは呼び出し側の責任
    }
}
