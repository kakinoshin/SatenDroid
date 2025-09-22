package com.celstech.satendroid.download.manager

import android.content.Context
import com.celstech.satendroid.worker.WorkManagerDownloadQueueManager
import com.celstech.satendroid.ui.models.DownloadQueueState
import com.celstech.satendroid.ui.models.DownloadProgressInfo
import com.celstech.satendroid.ui.models.DownloadRequest
import com.celstech.satendroid.ui.models.DownloadQueue
import com.celstech.satendroid.ui.models.DownloadHistory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow

/**
 * WorkManagerベースのダウンロードサービスマネージャー
 * 
 * 既存のDownloadServiceManagerインターフェースを維持しながら、
 * 内部実装をWorkManagerに置き換えます。
 */
class WorkManagerDownloadServiceManager(
    private val context: Context,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
) {
    private val queueManager = WorkManagerDownloadQueueManager(context, coroutineScope)
    
    /**
     * ダウンロード進捗のFlow（従来互換性）
     */
    val downloadProgress: StateFlow<Map<String, DownloadProgressInfo>>
        get() = queueManager.downloadProgress
    
    /**
     * ダウンロードキューのFlow（未処理のみ）
     */
    val downloadQueue: Flow<DownloadQueue>
        get() = queueManager.downloadQueue
    
    /**
     * ダウンロード履歴のFlow（処理済みのみ）
     */
    val downloadHistory: Flow<DownloadHistory>
        get() = queueManager.downloadHistory
    
    /**
     * キューの状態のFlow
     */
    val queueState: StateFlow<DownloadQueueState>
        get() = queueManager.queueState
    
    /**
     * ダウンロードをキューに追加
     */
    suspend fun enqueueDownload(request: DownloadRequest) {
        queueManager.enqueueDownload(request)
    }
    
    /**
     * ダウンロードをキャンセル
     */
    suspend fun cancelDownload(downloadId: String) {
        queueManager.cancelDownload(downloadId)
    }
    
    /**
     * 失敗したダウンロードを再試行
     */
    suspend fun retryDownload(downloadId: String) {
        queueManager.retryDownload(downloadId)
    }
    
    /**
     * 全ダウンロードを一時停止
     */
    suspend fun pauseAll() {
        queueManager.pauseAll()
    }
    
    /**
     * 完了したダウンロードをクリア
     */
    suspend fun clearCompleted() {
        queueManager.clearCompleted()
    }
    
    /**
     * 履歴から指定したダウンロードを削除
     */
    suspend fun removeFromHistory(downloadId: String) {
        queueManager.removeFromHistory(downloadId)
    }
    
    /**
     * クリーンアップ
     */
    suspend fun cleanup() {
        queueManager.cleanup()
    }
    
    companion object {
        @Volatile
        private var INSTANCE: WorkManagerDownloadServiceManager? = null
        
        /**
         * シングルトンインスタンスを取得
         */
        fun getInstance(context: Context): WorkManagerDownloadServiceManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WorkManagerDownloadServiceManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
