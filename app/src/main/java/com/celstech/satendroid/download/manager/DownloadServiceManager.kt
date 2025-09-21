package com.celstech.satendroid.download.manager

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import com.celstech.satendroid.service.DownloadService
import com.celstech.satendroid.ui.models.DownloadRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * DownloadServiceの管理を簡素化するシングルトンマネージャー
 * UIとサービスの依存関係を切り離し、確実なサービス接続を提供
 */
object DownloadServiceManager {
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val connectionMutex = Mutex()
    
    // サービス接続状態
    private var downloadService: DownloadService? = null
    private var queueManager: DownloadQueueManager? = null
    private var isConnected = false
    private var isConnecting = false
    
    // サービス接続完了を待つためのDeferred
    private var connectionDeferred: CompletableDeferred<DownloadQueueManager>? = null
    
    // 接続状態の公開
    private val _isServiceConnected = MutableStateFlow(false)
    val isServiceConnected = _isServiceConnected.asStateFlow()
    
    /**
     * サービス接続
     */
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            println("DEBUG: DownloadServiceManager - Service connected")
            val binder = service as DownloadService.DownloadBinder
            downloadService = binder.getService()
            queueManager = downloadService?.getDownloadQueueManager()
            isConnected = true
            isConnecting = false
            
            _isServiceConnected.value = true
            
            // 待機中のクライアントに通知
            connectionDeferred?.complete(queueManager!!)
            connectionDeferred = null
            
            println("DEBUG: DownloadServiceManager - QueueManager ready")
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            println("DEBUG: DownloadServiceManager - Service disconnected")
            downloadService = null
            queueManager = null
            isConnected = false
            isConnecting = false
            
            _isServiceConnected.value = false
            
            // 接続失敗を通知
            connectionDeferred?.completeExceptionally(
                RuntimeException("Service disconnected")
            )
            connectionDeferred = null
        }
    }
    
    /**
     * サービスを初期化し、DownloadQueueManagerを取得
     * @param context アプリケーションコンテキスト
     * @return DownloadQueueManager
     */
    suspend fun getQueueManager(context: Context): DownloadQueueManager {
        connectionMutex.withLock {
            // 既に接続済みの場合
            if (isConnected && queueManager != null) {
                return queueManager!!
            }
            
            // 接続中の場合は完了を待機
            if (isConnecting && connectionDeferred != null) {
                return connectionDeferred!!.await()
            }
            
            // 新規接続を開始
            return startServiceAndConnect(context)
        }
    }
    
    /**
     * サービスを開始して接続
     */
    private suspend fun startServiceAndConnect(context: Context): DownloadQueueManager {
        println("DEBUG: DownloadServiceManager - Starting service connection")
        
        isConnecting = true
        connectionDeferred = CompletableDeferred()
        
        try {
            // サービス開始
            val intent = Intent(context, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            
            // サービスにバインド
            val bindSuccess = context.bindService(
                intent,
                serviceConnection,
                Context.BIND_AUTO_CREATE
            )
            
            if (!bindSuccess) {
                isConnecting = false
                connectionDeferred?.completeExceptionally(
                    RuntimeException("Failed to bind to DownloadService")
                )
                throw RuntimeException("Failed to bind to DownloadService")
            }
            
            // 接続完了を待機
            return connectionDeferred!!.await()
            
        } catch (e: Exception) {
            isConnecting = false
            connectionDeferred?.completeExceptionally(e)
            connectionDeferred = null
            throw e
        }
    }
    
    /**
     * ダウンロードをキューに追加
     * サービス接続を自動的に処理
     */
    suspend fun enqueueDownload(context: Context, request: DownloadRequest) {
        try {
            val manager = getQueueManager(context)
            manager.enqueueDownload(request)
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
            val manager = getQueueManager(context)
            manager.cancelDownload(downloadId)
            println("DEBUG: DownloadServiceManager - Successfully cancelled: $downloadId")
        } catch (e: Exception) {
            println("DEBUG: DownloadServiceManager - Failed to cancel: ${e.message}")
            throw e
        }
    }
    
    /**
     * ダウンロードを一時停止
     */
    suspend fun pauseDownload(context: Context, downloadId: String) {
        try {
            val manager = getQueueManager(context)
            manager.pauseDownload(downloadId)
        } catch (e: Exception) {
            println("DEBUG: DownloadServiceManager - Failed to pause: ${e.message}")
            throw e
        }
    }
    
    /**
     * ダウンロードを再開
     */
    suspend fun resumeDownload(context: Context, downloadId: String) {
        try {
            val manager = getQueueManager(context)
            manager.resumeDownload(downloadId)
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
            val manager = getQueueManager(context)
            manager.retryDownload(downloadId)
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
            val manager = getQueueManager(context)
            manager.clearCompleted()
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
            val manager = getQueueManager(context)
            manager.pauseAll()
        } catch (e: Exception) {
            println("DEBUG: DownloadServiceManager - Failed to pause all: ${e.message}")
            throw e
        }
    }
    
    /**
     * 現在のQueueManagerを同期的に取得（既に接続済みの場合のみ）
     * UI層で既に接続が確立されている場合に使用
     */
    fun getCurrentQueueManager(): DownloadQueueManager? {
        return if (isConnected) queueManager else null
    }
    
    /**
     * サービス接続を切断
     * 通常はアプリ終了時に呼び出される
     */
    fun disconnect(context: Context) {
        scope.launch {
            connectionMutex.withLock {
                if (isConnected || isConnecting) {
                    try {
                        context.unbindService(serviceConnection)
                    } catch (e: Exception) {
                        println("DEBUG: DownloadServiceManager - Error disconnecting: ${e.message}")
                    }
                    
                    downloadService = null
                    queueManager = null
                    isConnected = false
                    isConnecting = false
                    _isServiceConnected.value = false
                    
                    connectionDeferred?.cancel()
                    connectionDeferred = null
                    
                    println("DEBUG: DownloadServiceManager - Disconnected")
                }
            }
        }
    }
}
