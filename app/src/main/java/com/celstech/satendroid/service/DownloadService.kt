package com.celstech.satendroid.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.celstech.satendroid.MainActivity
import com.celstech.satendroid.R
import com.celstech.satendroid.download.downloader.DropboxDownloader
import com.celstech.satendroid.download.manager.DownloadQueueManager
import com.celstech.satendroid.dropbox.DropboxAuthManager
import com.celstech.satendroid.ui.models.DownloadQueueState
import com.celstech.satendroid.ui.models.DownloadRequest
import com.celstech.satendroid.utils.FormatUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * ダウンロード用フォアグラウンドサービス
 */
class DownloadService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val COMPLETION_NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "download_channel"
        private const val COMPLETION_CHANNEL_ID = "download_completion_channel"
        private const val CHANNEL_NAME = "Download Progress"
        private const val COMPLETION_CHANNEL_NAME = "Download Completion"
        
        const val ACTION_START_DOWNLOAD = "com.celstech.satendroid.START_DOWNLOAD"
        const val ACTION_CANCEL_DOWNLOAD = "com.celstech.satendroid.CANCEL_DOWNLOAD"
        const val ACTION_PAUSE_ALL = "com.celstech.satendroid.PAUSE_ALL"
        
        const val EXTRA_DOWNLOAD_REQUEST = "download_request"
        const val EXTRA_DOWNLOAD_ID = "download_id"

        /**
         * サービスを開始するためのヘルパーメソッド
         */
        fun startService(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * ダウンロードを開始するためのヘルパーメソッド
         */
        fun startDownload(context: Context, request: DownloadRequest) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START_DOWNLOAD
                putExtra(EXTRA_DOWNLOAD_REQUEST, request)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * ダウンロードをキャンセルするためのヘルパーメソッド
         */
        fun cancelDownload(context: Context, downloadId: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL_DOWNLOAD
                putExtra(EXTRA_DOWNLOAD_ID, downloadId)
            }
            context.startService(intent)
        }
    }

    // サービスのバインダー
    inner class DownloadBinder : Binder() {
        fun getService(): DownloadService = this@DownloadService
    }

    private val binder = DownloadBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // ダウンロード管理
    private lateinit var downloadQueueManager: DownloadQueueManager
    private lateinit var dropboxAuthManager: DropboxAuthManager
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        
        // 通知チャンネル作成
        createNotificationChannel()
        
        // NotificationManagerを取得
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        // DropboxAuthManagerを初期化
        dropboxAuthManager = DropboxAuthManager(this)
        
        // ダウンロードマネージャーを初期化
        downloadQueueManager = DownloadQueueManager(serviceScope)
        
        // Dropboxダウンローダーを登録
        val dropboxDownloader = DropboxDownloader(dropboxAuthManager)
        downloadQueueManager.registerDownloader(dropboxDownloader)
        
        // キューの状態を監視して通知を更新
        downloadQueueManager.queueState
            .onEach { queueState ->
                updateNotification(queueState)
            }
            .launchIn(serviceScope)
        
        // フォアグラウンドサービスとして開始
        startForeground(NOTIFICATION_ID, createInitialNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_DOWNLOAD -> {
                val request = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DOWNLOAD_REQUEST, DownloadRequest::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DOWNLOAD_REQUEST)
                }
                
                request?.let { 
                    serviceScope.launch {
                        downloadQueueManager.enqueueDownload(it)
                    }
                }
            }
            
            ACTION_CANCEL_DOWNLOAD -> {
                val downloadId = intent.getStringExtra(EXTRA_DOWNLOAD_ID)
                downloadId?.let {
                    serviceScope.launch {
                        downloadQueueManager.cancelDownload(it)
                    }
                }
            }
            
            ACTION_PAUSE_ALL -> {
                serviceScope.launch {
                    downloadQueueManager.pauseAll()
                }
            }
        }
        
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.launch {
            downloadQueueManager.cleanup()
        }
    }

    /**
     * ダウンロードキューマネージャーを取得
     */
    fun getDownloadQueueManager(): DownloadQueueManager = downloadQueueManager

    /**
     * 通知チャンネルを作成
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // プログレス通知チャンネル
            val progressChannel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows download progress for cloud files"
                setShowBadge(true)
                enableVibration(false)
                enableLights(false)
            }
            
            // 完了通知チャンネル
            val completionChannel = NotificationChannel(
                COMPLETION_CHANNEL_ID,
                COMPLETION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifies when downloads are completed"
                setShowBadge(true)
                enableVibration(true)
                enableLights(true)
                lightColor = android.graphics.Color.GREEN
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(progressChannel)
            notificationManager.createNotificationChannel(completionChannel)
        }
    }

    /**
     * 初期通知を作成
     */
    private fun createInitialNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SatenDroid Downloads")
            .setContentText("Download service is ready")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    /**
     * キューの状態に基づいて通知を更新
     */
    private fun updateNotification(queueState: DownloadQueueState) {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = when {
            queueState.activeDownloads > 0 -> {
                // アクティブなダウンロードがある場合
                val progress = (queueState.overallProgress * 100).toInt()
                val speedText = if (queueState.overallSpeed > 0) {
                    " • ${FormatUtils.formatSpeed(queueState.overallSpeed)}"
                } else ""
                
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("Downloading ${queueState.activeDownloads} file(s)")
                    .setContentText("${progress}% complete${speedText}")
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setProgress(100, progress, false)
                    .setContentIntent(pendingIntent)
                    .setOngoing(true)
                    .addAction(
                        android.R.drawable.ic_media_pause,
                        "Pause All",
                        createPauseAllPendingIntent()
                    )
                    .build()
            }
            
            queueState.queuedDownloads > 0 -> {
                // キューにアイテムがある場合
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("${queueState.queuedDownloads} file(s) queued")
                    .setContentText("Waiting to download")
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentIntent(pendingIntent)
                    .setOngoing(true)
                    .build()
            }
            
            queueState.completedDownloads > 0 && queueState.failedDownloads == 0 -> {
                // 完了したダウンロードがある場合（エラーなし）
                val completionNotification = NotificationCompat.Builder(this, COMPLETION_CHANNEL_ID)
                    .setContentTitle("✅ Downloads completed!")
                    .setContentText("${queueState.completedDownloads} file(s) downloaded successfully")
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setDefaults(NotificationCompat.DEFAULT_ALL)
                    .build()
                
                // 完了通知は別のIDで表示
                notificationManager.notify(COMPLETION_NOTIFICATION_ID, completionNotification)
                
                // プログレス通知は更新
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("Downloads completed")
                    .setContentText("${queueState.completedDownloads} file(s) ready")
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentIntent(pendingIntent)
                    .setOngoing(false)
                    .build()
            }
            
            queueState.failedDownloads > 0 -> {
                // 失敗したダウンロードがある場合
                val successText = if (queueState.completedDownloads > 0) {
                    "${queueState.completedDownloads} completed, "
                } else ""
                
                val errorNotification = NotificationCompat.Builder(this, COMPLETION_CHANNEL_ID)
                    .setContentTitle("⚠️ Download issues")
                    .setContentText("${successText}${queueState.failedDownloads} failed")
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setDefaults(NotificationCompat.DEFAULT_ALL)
                    .build()
                
                // エラー通知は別のIDで表示
                notificationManager.notify(COMPLETION_NOTIFICATION_ID, errorNotification)
                
                // プログレス通知は更新
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("Downloads finished with issues")
                    .setContentText("${successText}${queueState.failedDownloads} failed")
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentIntent(pendingIntent)
                    .setOngoing(false)
                    .build()
            }
            
            else -> {
                // デフォルト状態
                createInitialNotification()
            }
        }

        notificationManager.notify(NOTIFICATION_ID, notification)
        
        // アクティブなダウンロードがない場合は段階的にサービスを停止
        if (!queueState.isActive) {
            // 完了または失敗の通知を表示した後、5秒後にサービスを停止
            if (queueState.completedDownloads > 0 || queueState.failedDownloads > 0) {
                serviceScope.launch {
                    kotlinx.coroutines.delay(5000) // 5秒待機
                    if (!downloadQueueManager.queueState.value.isActive) {
                        stopSelf()
                    }
                }
            } else {
                stopSelf()
            }
        }
    }

    /**
     * 全ダウンロード一時停止用のPendingIntentを作成
     */
    private fun createPauseAllPendingIntent(): PendingIntent {
        val intent = Intent(this, DownloadService::class.java).apply {
            action = ACTION_PAUSE_ALL
        }
        return PendingIntent.getService(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
