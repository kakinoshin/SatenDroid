package com.celstech.satendroid.download.downloader

import com.celstech.satendroid.dropbox.DropboxAuthManager
import com.celstech.satendroid.dropbox.DropboxAuthState
import com.celstech.satendroid.dropbox.SafeDropboxClient
import com.celstech.satendroid.download.exceptions.DownloadException
import com.celstech.satendroid.ui.models.CloudType
import com.celstech.satendroid.ui.models.DownloadProgressInfo
import com.celstech.satendroid.ui.models.DownloadRequest
import com.celstech.satendroid.ui.models.DownloadResult
import com.celstech.satendroid.ui.models.DownloadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

class DropboxDownloader(private val authManager: DropboxAuthManager) : CloudDownloader {

    override val cloudType: CloudType = CloudType.DROPBOX
    private val activeDownloads = ConcurrentHashMap<String, Boolean>()

    override suspend fun isAuthenticationRequired(): Boolean {
        // Check against the state machine instead of the old boolean function
        return authManager.authState.value !is DropboxAuthState.Authenticated
    }

    override suspend fun initialize() {}

    override suspend fun cleanup() {
        activeDownloads.clear()
    }

    override suspend fun downloadFile(
        request: DownloadRequest,
        progressCallback: (DownloadProgressInfo) -> Unit
    ): DownloadResult = withContext(Dispatchers.IO) {
        val authState = authManager.authState.value
        if (authState !is DropboxAuthState.Authenticated) {
            return@withContext DownloadResult.Failure("Dropbox authentication required")
        }

        activeDownloads[request.id] = true
        try {
            val localFile = File(request.localPath, request.fileName)
            localFile.parentFile?.mkdirs()

            val result = downloadFileInternal(
                client = authState.client,
                request = request,
                localFile = localFile,
                progressCallback = progressCallback
            )

            // Final progress update based on result
            val finalStatus = when (result) {
                is DownloadResult.Success -> DownloadStatus.COMPLETED
                is DownloadResult.Failure -> DownloadStatus.FAILED
                is DownloadResult.Cancelled -> DownloadStatus.CANCELLED
            }
            progressCallback(request.toProgressInfo(finalStatus, result.getErrorMessageOrNull()))

            result
        } catch (e: Exception) {
            val result = when (e) {
                is DownloadException.CancelledException -> DownloadResult.Cancelled
                else -> DownloadResult.Failure("Download failed: ${e.message}", e)
            }
            progressCallback(request.toProgressInfo(DownloadStatus.FAILED, result.getErrorMessageOrNull()))
            result
        } finally {
            activeDownloads.remove(request.id)
        }
    }

    private suspend fun downloadFileInternal(
        client: SafeDropboxClient,
        request: DownloadRequest,
        localFile: File,
        progressCallback: (DownloadProgressInfo) -> Unit
    ): DownloadResult {
        return try {
            FileOutputStream(localFile).use { outputStream ->
                // The new SafeDropboxClient handles the core download logic and exceptions
                client.download(request.remotePath, outputStream)
            }
            DownloadResult.Success(localFile.absolutePath)
        } catch (e: DownloadException.CancelledException) {
            localFile.delete()
            DownloadResult.Cancelled
        } catch (e: Exception) {
            localFile.delete()
            DownloadResult.Failure("Failed to download from Dropbox: ${e.message}", e)
        }
    }

    override suspend fun cancelDownload(downloadId: String) {
        activeDownloads[downloadId] = false
    }

    override suspend fun pauseDownload(downloadId: String): Boolean = false

    override suspend fun resumeDownload(downloadId: String): Boolean = false
}

private fun DownloadRequest.toProgressInfo(status: DownloadStatus, errorMessage: String? = null) = DownloadProgressInfo(
    downloadId = this.id,
    status = status,
    fileName = this.fileName,
    bytesDownloaded = if (status == DownloadStatus.COMPLETED) this.fileSize else 0L,
    totalBytes = this.fileSize,
    downloadSpeed = 0.0,
    estimatedTimeRemaining = 0L,
    errorMessage = errorMessage,
    completedTime = if (status == DownloadStatus.COMPLETED) System.currentTimeMillis() else null
)

private fun DownloadResult.getErrorMessageOrNull(): String? {
    return if (this is DownloadResult.Failure) this.error else null
}
