package com.celstech.satendroid.download.manager

import com.celstech.satendroid.download.downloader.CloudDownloader
import com.celstech.satendroid.ui.models.CloudType
import com.celstech.satendroid.ui.models.DownloadProgressInfo
import com.celstech.satendroid.ui.models.DownloadQueueState
import com.celstech.satendroid.ui.models.DownloadRequest
import com.celstech.satendroid.ui.models.DownloadResult
import com.celstech.satendroid.ui.models.DownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * ダウンロードキューを管理するクラス
 */
class DownloadQueueManager(
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val maxConcurrentDownloads: Int = 2
) {
    
    // ダウンローダーのマップ
    private val downloaders = mutableMapOf<CloudType, CloudDownloader>()
    
    // ダウンロードキューの状態
    private val _downloadQueue = MutableStateFlow<List<DownloadRequest>>(emptyList())
    
    // ダウンロード進捗の状態
    private val _downloadProgress = MutableStateFlow<Map<String, DownloadProgressInfo>>(emptyMap())
    val downloadProgress = _downloadProgress.asStateFlow()
    
    // 全体の状態
    private val _queueState = MutableStateFlow(DownloadQueueState())
    val queueState = _queueState.asStateFlow()
    
    // アクティブなダウンロードジョブ
    private val activeJobs = ConcurrentHashMap<String, Job>()
    
    // 同期用Mutex
    private val queueMutex = Mutex()
    
    /**
     * ダウンローダーを登録
     */
    fun registerDownloader(downloader: CloudDownloader) {
        downloaders[downloader.cloudType] = downloader
    }
    
    /**
     * ダウンロードをキューに追加
     */
    suspend fun enqueueDownload(request: DownloadRequest) {
        queueMutex.withLock {
            val currentQueue = _downloadQueue.value.toMutableList()
            currentQueue.add(request)
            _downloadQueue.value = currentQueue
            
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
            
            updateQueueState()
            processQueue()
        }
    }
    
    /**
     * ダウンロードをキャンセル
     */
    suspend fun cancelDownload(downloadId: String) {
        queueMutex.withLock {
            // アクティブなジョブをキャンセル
            activeJobs[downloadId]?.cancel()
            activeJobs.remove(downloadId)
            
            // 対応するダウンローダーにキャンセルを通知
            val progressInfo = _downloadProgress.value[downloadId]
            if (progressInfo != null) {
                val downloader = getDownloaderForRequest()
                downloader?.cancelDownload(downloadId)
                
                // 進捗状態を更新
                val progressMap = _downloadProgress.value.toMutableMap()
                progressMap[downloadId] = progressInfo.copy(status = DownloadStatus.CANCELLED)
                _downloadProgress.value = progressMap
            }
            
            // キューから削除
            val currentQueue = _downloadQueue.value.toMutableList()
            currentQueue.removeAll { it.id == downloadId }
            _downloadQueue.value = currentQueue
            
            updateQueueState()
            processQueue()
        }
    }
    
    /**
     * ダウンロードを一時停止
     */
    suspend fun pauseDownload(downloadId: String) {
        val progressInfo = _downloadProgress.value[downloadId] ?: return
        val downloader = getDownloaderForRequest() ?: return
        
        if (downloader.pauseDownload(downloadId)) {
            val progressMap = _downloadProgress.value.toMutableMap()
            progressMap[downloadId] = progressInfo.copy(status = DownloadStatus.PAUSED)
            _downloadProgress.value = progressMap
            updateQueueState()
        }
    }
    
    /**
     * ダウンロードを再開
     */
    suspend fun resumeDownload(downloadId: String) {
        val progressInfo = _downloadProgress.value[downloadId] ?: return
        val downloader = getDownloaderForRequest() ?: return
        
        if (downloader.resumeDownload(downloadId)) {
            val progressMap = _downloadProgress.value.toMutableMap()
            progressMap[downloadId] = progressInfo.copy(status = DownloadStatus.QUEUED)
            _downloadProgress.value = progressMap
            updateQueueState()
            processQueue()
        }
    }
    
    /**
     * 失敗したダウンロードを再試行
     */
    suspend fun retryDownload(downloadId: String) {
        queueMutex.withLock {
            val progressInfo = _downloadProgress.value[downloadId] ?: return@withLock
            if (progressInfo.status != DownloadStatus.FAILED) return@withLock
            
            // 進捗状態をキューに戻す
            val progressMap = _downloadProgress.value.toMutableMap()
            progressMap[downloadId] = progressInfo.copy(
                status = DownloadStatus.QUEUED,
                bytesDownloaded = 0L,
                downloadSpeed = 0.0,
                estimatedTimeRemaining = 0L,
                errorMessage = null
            )
            _downloadProgress.value = progressMap
            
            updateQueueState()
            processQueue()
        }
    }
    
    /**
     * 完了したダウンロードをクリア
     */
    suspend fun clearCompleted() {
        queueMutex.withLock {
            val progressMap = _downloadProgress.value.toMutableMap()
            val completedIds = progressMap.entries
                .filter { it.value.status == DownloadStatus.COMPLETED }
                .map { it.key }
            
            completedIds.forEach { progressMap.remove(it) }
            _downloadProgress.value = progressMap
            
            val currentQueue = _downloadQueue.value.toMutableList()
            currentQueue.removeAll { completedIds.contains(it.id) }
            _downloadQueue.value = currentQueue
            
            updateQueueState()
        }
    }
    
    /**
     * 全ダウンロードを一時停止
     */
    suspend fun pauseAll() {
        _downloadProgress.value.values
            .filter { it.canPause }
            .forEach { pauseDownload(it.downloadId) }
    }
    
    /**
     * キューを処理（最大同時ダウンロード数まで開始）
     */
    private fun processQueue() {
        if (activeJobs.size >= maxConcurrentDownloads) return
        
        val queuedItems = _downloadProgress.value.values
            .filter { it.status == DownloadStatus.QUEUED }
            .sortedBy { it.startTime } // FIFO
        
        val itemsToStart = queuedItems.take(maxConcurrentDownloads - activeJobs.size)
        
        itemsToStart.forEach { progressInfo ->
            val request = _downloadQueue.value.find { it.id == progressInfo.downloadId } ?: return@forEach
            startDownload(request)
        }
    }
    
    /**
     * 個別ダウンロードを開始
     */
    private fun startDownload(request: DownloadRequest) {
        val downloader = downloaders[request.cloudType] ?: return
        
        val job = coroutineScope.launch {
            try {
                // ダウンロード開始状態に更新
                val initialProgressMap = _downloadProgress.value.toMutableMap()
                val initialProgress = initialProgressMap[request.id]
                if (initialProgress != null) {
                    initialProgressMap[request.id] = initialProgress.copy(
                        status = DownloadStatus.DOWNLOADING
                    )
                    _downloadProgress.value = initialProgressMap
                    updateQueueState()
                }

                val result = downloader.downloadFile(request) { progress ->
                    // 進捗更新
                    val callbackProgressMap = _downloadProgress.value.toMutableMap()
                    callbackProgressMap[request.id] = progress
                    _downloadProgress.value = callbackProgressMap
                    updateQueueState()
                }
                
                // 結果に応じて最終状態を更新
                val finalStatus = when (result) {
                    is DownloadResult.Success -> DownloadStatus.COMPLETED
                    is DownloadResult.Failure -> DownloadStatus.FAILED
                    is DownloadResult.Cancelled -> DownloadStatus.CANCELLED
                }
                
                val finalProgressMap = _downloadProgress.value.toMutableMap()
                val finalProgress = finalProgressMap[request.id]
                if (finalProgress != null) {
                    finalProgressMap[request.id] = finalProgress.copy(
                        status = finalStatus,
                        errorMessage = if (result is DownloadResult.Failure) result.error else null,
                        completedTime = if (finalStatus == DownloadStatus.COMPLETED) System.currentTimeMillis() else null
                    )
                    _downloadProgress.value = finalProgressMap
                }
                
            } catch (e: Exception) {
                // エラー処理
                val errorProgressMap = _downloadProgress.value.toMutableMap()
                val errorProgress = errorProgressMap[request.id]
                if (errorProgress != null) {
                    errorProgressMap[request.id] = errorProgress.copy(
                        status = DownloadStatus.FAILED,
                        errorMessage = e.message ?: "Unknown error"
                    )
                    _downloadProgress.value = errorProgressMap
                }
            } finally {
                activeJobs.remove(request.id)
                updateQueueState()
                processQueue() // 次のダウンロードを開始
            }
        }
        
        activeJobs[request.id] = job
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
     * リクエストに対応するダウンローダーを取得
     */
    private fun getDownloaderForRequest(): CloudDownloader? {
        // 現在はDropboxのみサポート
        return downloaders[CloudType.DROPBOX]
    }
    
    /**
     * クリーンアップ
     */
    suspend fun cleanup() {
        // 全ジョブをキャンセル
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        
        // ダウンローダーをクリーンアップ
        downloaders.values.forEach { it.cleanup() }
        downloaders.clear()
    }
}
