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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
    
    // ダウンロード進捗の状態（従来互換性）
    private val _downloadProgress = MutableStateFlow<Map<String, DownloadProgressInfo>>(emptyMap())
    val downloadProgress = _downloadProgress.asStateFlow()
    
    // アクティブなダウンロードのみのマップを管理
    private val activeDownloadsMap = mutableMapOf<String, DownloadProgressInfo>()
    // 履歴アイテムのマップを管理
    private val historyItemsMap = mutableMapOf<String, DownloadProgressInfo>()

    // 分離されたキューと履歴のFlow
    private val _downloadQueue: Flow<DownloadQueue> = _downloadProgress.map { _ ->
        try {
            val queueItems = activeDownloadsMap.values
                .filter { progress ->
                    progress.status in listOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED)
                }
                .mapIndexed { index, progress ->
                    try {
                        DownloadQueueItem(
                            downloadId = progress.downloadId,
                            status = when (progress.status) {
                                DownloadStatus.QUEUED -> DownloadQueueStatus.QUEUED
                                DownloadStatus.DOWNLOADING -> DownloadQueueStatus.DOWNLOADING
                                DownloadStatus.PAUSED -> DownloadQueueStatus.PAUSED
                                else -> DownloadQueueStatus.QUEUED // fallback
                            },
                            request = synchronized(downloadRequests) {
                                downloadRequests[progress.downloadId]
                            } ?: DownloadRequest(
                                id = progress.downloadId,
                                cloudType = CloudType.LOCAL,
                                fileName = progress.fileName,
                                remotePath = "",
                                localPath = "",
                                fileSize = progress.totalBytes
                            ),
                            progress = if (progress.status == DownloadStatus.DOWNLOADING) {
                                DownloadProgress(
                                    bytesDownloaded = progress.bytesDownloaded,
                                    totalBytes = progress.totalBytes,
                                    downloadSpeed = progress.downloadSpeed,
                                    estimatedTimeRemaining = progress.estimatedTimeRemaining
                                )
                            } else null,
                            queuePosition = index,
                            startTime = if (progress.startTime > 0) progress.startTime else System.currentTimeMillis(),
                            errorMessage = progress.errorMessage
                        )
                    } catch (e: Exception) {
                        println("DEBUG: Error creating DownloadQueueItem for ${progress.downloadId}: ${e.message}")
                        null
                    }
                }.filterNotNull()
            DownloadQueue(queueItems)
        } catch (e: Exception) {
            println("DEBUG: Error in downloadQueue Flow: ${e.message}")
            DownloadQueue(emptyList())
        }
    }

    private val _downloadHistory: Flow<DownloadHistory> = _downloadProgress.map { _ ->
        try {
            val historyItems = historyItemsMap.values
                .mapNotNull { progress ->
                    try {
                        val request = synchronized(downloadRequests) {
                            downloadRequests[progress.downloadId]
                        } ?: return@mapNotNull null
                        
                        val result = when (progress.status) {
                            DownloadStatus.COMPLETED -> DownloadResult.Success("")
                            DownloadStatus.FAILED -> DownloadResult.Failure(progress.errorMessage ?: "Unknown error")
                            DownloadStatus.CANCELLED -> DownloadResult.Cancelled
                            else -> return@mapNotNull null
                        }
                        
                        // 安全な時間設定（完了時に一度だけ設定）
                        val completedTime = progress.completedTime ?: System.currentTimeMillis()
                        val startTime = if (progress.startTime > 0) progress.startTime else completedTime
                        
                        DownloadHistoryItem(
                            downloadId = progress.downloadId,
                            status = when (progress.status) {
                                DownloadStatus.COMPLETED -> DownloadHistoryStatus.COMPLETED
                                DownloadStatus.FAILED -> DownloadHistoryStatus.FAILED
                                DownloadStatus.CANCELLED -> DownloadHistoryStatus.CANCELLED
                                else -> return@mapNotNull null
                            },
                            request = request,
                            result = result,
                            completedTime = completedTime,
                            startTime = startTime,
                            errorMessage = progress.errorMessage
                        )
                    } catch (e: Exception) {
                        println("DEBUG: Error creating DownloadHistoryItem for ${progress.downloadId}: ${e.message}")
                        null
                    }
                }
            DownloadHistory(historyItems)
        } catch (e: Exception) {
            println("DEBUG: Error in downloadHistory Flow: ${e.message}")
            DownloadHistory(emptyList())
        }
    }

    // 公開プロパティ
    val downloadQueue: Flow<DownloadQueue> get() = _downloadQueue
    val downloadHistory: Flow<DownloadHistory> get() = _downloadHistory
    
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
        
        // 初期進捗状態をアクティブマップに追加
        val progressInfo = DownloadProgressInfo(
            downloadId = request.id,
            status = DownloadStatus.QUEUED,
            fileName = request.fileName,
            bytesDownloaded = 0L,
            totalBytes = request.fileSize,
            downloadSpeed = 0.0,
            estimatedTimeRemaining = 0L
        )
        
        activeDownloadsMap[request.id] = progressInfo
        
        // 互換性のため全体マップにも追加
        val progressMap = _downloadProgress.value.toMutableMap()
        progressMap[request.id] = progressInfo
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
        
        // アクティブマップから削除
        val currentProgress = activeDownloadsMap[downloadId]
        if (currentProgress != null) {
            val cancelledProgress = currentProgress.copy(status = DownloadStatus.CANCELLED)
            activeDownloadsMap.remove(downloadId)
            historyItemsMap[downloadId] = cancelledProgress
        }
        
        // 進捗状態を更新
        val progressMap = _downloadProgress.value.toMutableMap()
        val existingProgress = progressMap[downloadId]
        if (existingProgress != null) {
            progressMap[downloadId] = existingProgress.copy(status = DownloadStatus.CANCELLED)
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
        
        // アクティブマップの状態を更新
        val progressMap = _downloadProgress.value.toMutableMap()
        activeDownloadsMap.entries.forEach { entry ->
            if (entry.value.status == DownloadStatus.DOWNLOADING || entry.value.status == DownloadStatus.QUEUED) {
                val cancelledProgress = entry.value.copy(status = DownloadStatus.CANCELLED)
                historyItemsMap[entry.key] = cancelledProgress
                progressMap[entry.key] = cancelledProgress
            }
        }
        activeDownloadsMap.clear()
        _downloadProgress.value = progressMap
        
        // マッピングをクリア
        workIdMapping.clear()
        downloadRequests.clear()
        
        updateQueueState()
    }

    /**
     * 失敗したダウンロードを再試行（履歴から削除してキューに追加）
     */
    suspend fun retryDownload(downloadId: String) {
        val originalRequest = downloadRequests[downloadId] ?: return
        val progressInfo = historyItemsMap[downloadId] ?: return
        if (progressInfo.status != DownloadStatus.FAILED) return

        // 新しいIDで再エンキュー
        val retryRequest = originalRequest.copy(id = DownloadRequest.generateId())
        enqueueDownload(retryRequest)
        
        // 古い失敗したエントリを履歴から削除
        removeFromHistory(downloadId)
        
        updateQueueState()
    }

    /**
     * 完了したダウンロードをクリア（履歴からマッピングも削除）
     */
    suspend fun clearCompleted() {
        val progressMap = _downloadProgress.value.toMutableMap()
        val completedIds = historyItemsMap.entries
            .filter { it.value.status == DownloadStatus.COMPLETED }
            .map { it.key }

        completedIds.forEach { 
            historyItemsMap.remove(it)
            progressMap.remove(it)
            downloadRequests.remove(it)
            workIdMapping.remove(it)
        }
        _downloadProgress.value = progressMap

        updateQueueState()
    }

    /**
     * 履歴から指定したダウンロードを削除（マッピングも削除）
     */
    suspend fun removeFromHistory(downloadId: String) {
        val progressInfo = historyItemsMap[downloadId]
        
        if (progressInfo?.status in listOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED, DownloadStatus.CANCELLED)) {
            historyItemsMap.remove(downloadId)
            val progressMap = _downloadProgress.value.toMutableMap()
            progressMap.remove(downloadId)
            _downloadProgress.value = progressMap
            downloadRequests.remove(downloadId)
            workIdMapping.remove(downloadId)
            updateQueueState()
        }
    }

    /**
     * ワークの状態を監視（全てのワークを監視）
     */
    private fun observeWorkStates() {
        // WorkManagerのワーク状態をFlowで監視
        coroutineScope.launch {
            while (true) {
                try {
                    val workInfos = workManager.getWorkInfosByTag(DOWNLOAD_WORK_TAG).get()
                    updateProgressFromWorkInfos(workInfos)
                    moveCompletedItemsToHistory() // 完了したアイテムを履歴に移動
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

            val currentProgress = activeDownloadsMap[downloadId] ?: return@forEach

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
            val updatedProgress = currentProgress.copy(
                status = newStatus,
                bytesDownloaded = bytesDownloaded,
                errorMessage = errorMessage,
                completedTime = if (newStatus == DownloadStatus.COMPLETED) System.currentTimeMillis() else null
            )
            
            activeDownloadsMap[downloadId] = updatedProgress
            progressMap[downloadId] = updatedProgress
        }

        _downloadProgress.value = progressMap
        updateQueueState()
    }

    /**
     * 完了したアイテムを履歴に移動（監視対象から除外）
     */
    private suspend fun moveCompletedItemsToHistory() {
        val completedItems = activeDownloadsMap.values.filter { progress ->
            progress.status in listOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED, DownloadStatus.CANCELLED)
        }

        completedItems.forEach { completedProgress ->
            // 履歴マップに移動
            historyItemsMap[completedProgress.downloadId] = completedProgress
            
            // アクティブマップから削除
            activeDownloadsMap.remove(completedProgress.downloadId)
            
            // スロット解放処理（完了時のみ）
            val workId = workIdMapping[completedProgress.downloadId]
            if (workId != null) {
                try {
                    val workInfo = workManager.getWorkInfoById(workId).get()
                    val slotTag = workInfo.tags.find { it.startsWith("slot_") }
                    if (slotTag != null) {
                        val slot = slotTag.substringAfter("slot_").toIntOrNull()
                        if (slot != null) {
                            releaseSlotAndProcessQueue(slot)
                        }
                    }
                } catch (e: Exception) {
                    println("DEBUG: Error getting work info for slot release: ${e.message}")
                }
            }

            println("DEBUG: Moved to history: ${completedProgress.downloadId} - ${completedProgress.status}")
        }

        // 全体のprogressMapも更新（互換性のため）
        val progressMap = _downloadProgress.value.toMutableMap()
        activeDownloadsMap.forEach { (id, progress) ->
            progressMap[id] = progress
        }
        historyItemsMap.forEach { (id, progress) ->
            progressMap[id] = progress
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
        val pausedCount = progressValues.count { it.status == DownloadStatus.PAUSED }
        val completedCount = progressValues.count { it.status == DownloadStatus.COMPLETED }
        val failedCount = progressValues.count { it.status == DownloadStatus.FAILED }
        val cancelledCount = progressValues.count { it.status == DownloadStatus.CANCELLED }

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
            pausedDownloads = pausedCount,
            completedDownloads = completedCount,
            failedDownloads = failedCount,
            cancelledDownloads = cancelledCount,
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
