package com.celstech.satendroid.download.manager

import android.content.Context
import com.celstech.satendroid.worker.WorkManagerDownloadQueueManager
import com.celstech.satendroid.ui.models.DownloadQueueState
import com.celstech.satendroid.ui.models.DownloadProgressInfo
import com.celstech.satendroid.ui.models.DownloadRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow

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
     * ダウンロード進捗のFlow
     */
    val downloadProgress: StateFlow<Map<String, DownloadProgressInfo>>
        get() = queueManager.downloadProgress
    
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
