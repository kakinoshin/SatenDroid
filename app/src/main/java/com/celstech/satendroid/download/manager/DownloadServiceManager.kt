package com.celstech.satendroid.download.manager

import android.content.Context
import com.celstech.satendroid.ui.models.DownloadRequest
import kotlinx.coroutines.flow.StateFlow

/**
 * DownloadServiceの管理を簡素化するシングルトンマネージャー
 * WorkManagerベースの実装に移行
 */
object DownloadServiceManager {
    
    @Volatile
    private var workManagerInstance: WorkManagerDownloadServiceManager? = null
    
    /**
     * WorkManagerベースのダウンロードマネージャーを取得
     */
    private fun getWorkManagerInstance(context: Context): WorkManagerDownloadServiceManager {
        return workManagerInstance ?: synchronized(this) {
            workManagerInstance ?: WorkManagerDownloadServiceManager.getInstance(context).also { 
                workManagerInstance = it 
            }
        }
    }
    
    /**
     * ダウンロード進捗のFlow
     */
    fun getDownloadProgress(context: Context) = getWorkManagerInstance(context).downloadProgress
    
    /**
     * キューの状態のFlow
     */
    fun getQueueState(context: Context) = getWorkManagerInstance(context).queueState
    
    /**
     * ダウンロードをキューに追加
     */
    suspend fun enqueueDownload(context: Context, request: DownloadRequest) {
        try {
            getWorkManagerInstance(context).enqueueDownload(request)
            println("DEBUG: DownloadServiceManager - Successfully enqueued: ${request.fileName}")
        } catch (e: Exception) {
            println("DEBUG: DownloadServiceManager - Failed to enqueue: ${e.message}")
            throw e
        }
    }
    
    /**
     * ダウンロードをキャンセル
     */
    suspend fun cancelDownload(context: Context, downloadId: String) {
        try {
            getWorkManagerInstance(context).cancelDownload(downloadId)
            println("DEBUG: DownloadServiceManager - Successfully cancelled: $downloadId")
        } catch (e: Exception) {
            println("DEBUG: DownloadServiceManager - Failed to cancel: ${e.message}")
            throw e
        }
    }
    
    /**
     * ダウンロードを一時停止（WorkManagerでは実質的にキャンセル）
     */
    suspend fun pauseDownload(context: Context, downloadId: String) {
        try {
            getWorkManagerInstance(context).cancelDownload(downloadId)
        } catch (e: Exception) {
            println("DEBUG: DownloadServiceManager - Failed to pause: ${e.message}")
            throw e
        }
    }
    
    /**
     * ダウンロードを再開（WorkManagerでは新たにエンキュー）
     */
    suspend fun resumeDownload(context: Context, downloadId: String) {
        try {
            getWorkManagerInstance(context).retryDownload(downloadId)
        } catch (e: Exception) {
            println("DEBUG: DownloadServiceManager - Failed to resume: ${e.message}")
            throw e
        }
    }
    
    /**
     * 失敗したダウンロードを再試行
     */
    suspend fun retryDownload(context: Context, downloadId: String) {
        try {
            getWorkManagerInstance(context).retryDownload(downloadId)
        } catch (e: Exception) {
            println("DEBUG: DownloadServiceManager - Failed to retry: ${e.message}")
            throw e
        }
    }
    
    /**
     * 完了したダウンロードをクリア
     */
    suspend fun clearCompleted(context: Context) {
        try {
            getWorkManagerInstance(context).clearCompleted()
        } catch (e: Exception) {
            println("DEBUG: DownloadServiceManager - Failed to clear completed: ${e.message}")
            throw e
        }
    }
    
    /**
     * 全ダウンロードを一時停止
     */
    suspend fun pauseAll(context: Context) {
        try {
            getWorkManagerInstance(context).pauseAll()
        } catch (e: Exception) {
            println("DEBUG: DownloadServiceManager - Failed to pause all: ${e.message}")
            throw e
        }
    }
    
    /**
     * サービス接続を切断（WorkManagerでは不要だが互換性のため）
     */
    suspend fun disconnect(context: Context) {
        workManagerInstance?.cleanup()
        workManagerInstance = null
        println("DEBUG: DownloadServiceManager - Cleaned up WorkManager instance")
    }
}
