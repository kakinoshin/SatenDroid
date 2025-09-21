package com.celstech.satendroid.download.downloader

import com.celstech.satendroid.dropbox.DropboxAuthManager
import com.celstech.satendroid.dropbox.DropboxAuthState
import com.celstech.satendroid.ui.models.CloudType
import com.celstech.satendroid.ui.models.DownloadProgressInfo
import com.celstech.satendroid.ui.models.DownloadRequest
import com.celstech.satendroid.ui.models.DownloadResult
import com.celstech.satendroid.ui.models.DownloadStatus
import com.celstech.satendroid.utils.FormatUtils
import com.dropbox.core.v2.DbxClientV2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * Dropbox用ダウンローダー実装
 */
class DropboxDownloader(
    private val authManager: DropboxAuthManager
) : CloudDownloader {

    override val cloudType: CloudType = CloudType.DROPBOX

    // アクティブなダウンロードを追跡
    private val activeDownloads = ConcurrentHashMap<String, Boolean>()

    override suspend fun isAuthenticationRequired(): Boolean {
        return !authManager.isAuthenticated()
    }

    override suspend fun initialize() {
        // 初期化処理（必要に応じて）
    }

    override suspend fun cleanup() {
        // クリーンアップ処理
        activeDownloads.clear()
    }

    override suspend fun downloadFile(
        request: DownloadRequest,
        progressCallback: (DownloadProgressInfo) -> Unit
    ): DownloadResult = withContext(Dispatchers.IO) {
        
        // 認証状態確認
        val authState = authManager.authState.value
        if (authState !is DropboxAuthState.Authenticated) {
            return@withContext DownloadResult.Failure("Dropbox authentication required")
        }

        val client = authState.client
        activeDownloads[request.id] = true

        try {
            // 初期進捗通知
            progressCallback(
                DownloadProgressInfo(
                    downloadId = request.id,
                    status = DownloadStatus.DOWNLOADING,
                    fileName = request.fileName,
                    bytesDownloaded = 0L,
                    totalBytes = request.fileSize,
                    downloadSpeed = 0.0,
                    estimatedTimeRemaining = 0L
                )
            )

            // ローカルファイルの準備
            val localFile = File(request.localPath, request.fileName)
            val parentDir = localFile.parentFile
            if (parentDir != null && !parentDir.exists()) {
                val created = parentDir.mkdirs()
                if (!created && !parentDir.exists()) {
                    return@withContext DownloadResult.Failure("Failed to create directory: ${parentDir.absolutePath}")
                }
            }

            // ダウンロード実行
            val result = downloadFileInternal(client, request, localFile, progressCallback)
            
            // 最終進捗通知
            when (result) {
                is DownloadResult.Success -> {
                    progressCallback(
                        DownloadProgressInfo(
                            downloadId = request.id,
                            status = DownloadStatus.COMPLETED,
                            fileName = request.fileName,
                            bytesDownloaded = request.fileSize,
                            totalBytes = request.fileSize,
                            downloadSpeed = 0.0,
                            estimatedTimeRemaining = 0L,
                            completedTime = System.currentTimeMillis()
                        )
                    )
                }
                is DownloadResult.Failure -> {
                    progressCallback(
                        DownloadProgressInfo(
                            downloadId = request.id,
                            status = DownloadStatus.FAILED,
                            fileName = request.fileName,
                            bytesDownloaded = 0L,
                            totalBytes = request.fileSize,
                            downloadSpeed = 0.0,
                            estimatedTimeRemaining = 0L,
                            errorMessage = result.error
                        )
                    )
                }
                is DownloadResult.Cancelled -> {
                    progressCallback(
                        DownloadProgressInfo(
                            downloadId = request.id,
                            status = DownloadStatus.CANCELLED,
                            fileName = request.fileName,
                            bytesDownloaded = 0L,
                            totalBytes = request.fileSize,
                            downloadSpeed = 0.0,
                            estimatedTimeRemaining = 0L
                        )
                    )
                }
            }

            result

        } catch (e: Exception) {
            val errorMessage = when (e) {
                is DownloadException.CancelledException -> {
                    progressCallback(
                        DownloadProgressInfo(
                            downloadId = request.id,
                            status = DownloadStatus.CANCELLED,
                            fileName = request.fileName,
                            bytesDownloaded = 0L,
                            totalBytes = request.fileSize,
                            downloadSpeed = 0.0,
                            estimatedTimeRemaining = 0L
                        )
                    )
                    return@withContext DownloadResult.Cancelled
                }
                else -> "Download failed: ${e.message}"
            }

            progressCallback(
                DownloadProgressInfo(
                    downloadId = request.id,
                    status = DownloadStatus.FAILED,
                    fileName = request.fileName,
                    bytesDownloaded = 0L,
                    totalBytes = request.fileSize,
                    downloadSpeed = 0.0,
                    estimatedTimeRemaining = 0L,
                    errorMessage = errorMessage
                )
            )

            DownloadResult.Failure(errorMessage, e)
        } finally {
            activeDownloads.remove(request.id)
        }
    }

    private suspend fun downloadFileInternal(
        client: DbxClientV2,
        request: DownloadRequest,
        localFile: File,
        progressCallback: (DownloadProgressInfo) -> Unit
    ): DownloadResult = withContext(Dispatchers.IO) {
        
        try {
            val downloader = client.files().download(request.remotePath)
            val outputStream = BufferedOutputStream(localFile.outputStream(), 65536)
            val buffer = ByteArray(65536) // 64KB buffer
            var bytesDownloaded = 0L
            var lastUpdateTime = 0L
            val updateInterval = 500L // Update UI every 500ms
            val startTime = System.currentTimeMillis()

            val inputStream = downloader.inputStream

            try {
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    // キャンセルチェック
                    if (!coroutineContext.isActive || activeDownloads[request.id] != true) {
                        throw DownloadException.CancelledException()
                    }

                    outputStream.write(buffer, 0, bytesRead)
                    bytesDownloaded += bytesRead

                    // 進捗更新（スロットリング付き）
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastUpdateTime > updateInterval) {
                        val elapsedTime = (currentTime - startTime) / 1000.0 // seconds
                        val speed = if (elapsedTime > 0) bytesDownloaded / elapsedTime else 0.0
                        val remaining = if (speed > 0) (request.fileSize - bytesDownloaded) / speed else 0.0

                        progressCallback(
                            DownloadProgressInfo(
                                downloadId = request.id,
                                status = DownloadStatus.DOWNLOADING,
                                fileName = request.fileName,
                                bytesDownloaded = bytesDownloaded,
                                totalBytes = request.fileSize,
                                downloadSpeed = speed,
                                estimatedTimeRemaining = remaining.toLong()
                            )
                        )
                        lastUpdateTime = currentTime
                    }
                }

                // ファイルサイズの検証
                if (bytesDownloaded != request.fileSize && request.fileSize > 0) {
                    return@withContext DownloadResult.Failure("File size mismatch: expected ${request.fileSize}, got $bytesDownloaded")
                }

                DownloadResult.Success(localFile.absolutePath)

            } finally {
                inputStream.close()
                outputStream.close()
            }

        } catch (e: DownloadException.CancelledException) {
            // キャンセルされた場合、部分ファイルを削除
            if (localFile.exists()) {
                localFile.delete()
            }
            throw e
        } catch (e: Exception) {
            // エラーの場合、部分ファイルを削除
            if (localFile.exists()) {
                localFile.delete()
            }
            throw DownloadException.NetworkException("Failed to download from Dropbox: ${e.message}", e)
        }
    }

    override suspend fun cancelDownload(downloadId: String) {
        activeDownloads[downloadId] = false
    }

    override suspend fun pauseDownload(downloadId: String): Boolean {
        // Dropboxは一時停止をサポートしていない
        return false
    }

    override suspend fun resumeDownload(downloadId: String): Boolean {
        // Dropboxは再開をサポートしていない
        return false
    }
}
