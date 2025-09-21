package com.celstech.satendroid.download.downloader

import com.celstech.satendroid.ui.models.CloudType
import com.celstech.satendroid.ui.models.DownloadProgressInfo
import com.celstech.satendroid.ui.models.DownloadRequest
import com.celstech.satendroid.ui.models.DownloadResult

/**
 * クラウドダウンローダーの抽象インターフェース
 * 各クラウドサービス固有の実装はこのインターフェースを実装する
 */
interface CloudDownloader {
    /**
     * サポートするクラウドタイプ
     */
    val cloudType: CloudType

    /**
     * 認証が必要かどうか
     */
    suspend fun isAuthenticationRequired(): Boolean

    /**
     * ファイルをダウンロードする
     * @param request ダウンロード要求
     * @param progressCallback 進捗コールバック
     * @return ダウンロード結果
     */
    suspend fun downloadFile(
        request: DownloadRequest,
        progressCallback: (DownloadProgressInfo) -> Unit
    ): DownloadResult

    /**
     * ダウンロードをキャンセルする
     * @param downloadId ダウンロードID
     */
    suspend fun cancelDownload(downloadId: String)

    /**
     * ダウンロードを一時停止する（サポートしている場合）
     * @param downloadId ダウンロードID
     * @return 一時停止に成功した場合true
     */
    suspend fun pauseDownload(downloadId: String): Boolean

    /**
     * ダウンロードを再開する（サポートしている場合）
     * @param downloadId ダウンロードID
     * @return 再開に成功した場合true
     */
    suspend fun resumeDownload(downloadId: String): Boolean

    /**
     * ダウンローダーの初期化
     */
    suspend fun initialize()

    /**
     * ダウンローダーのクリーンアップ
     */
    suspend fun cleanup()
}

/**
 * ダウンロード例外
 */
sealed class DownloadException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NetworkException(message: String, cause: Throwable? = null) : DownloadException(message, cause)
    class AuthenticationException(message: String, cause: Throwable? = null) : DownloadException(message, cause)
    class StorageException(message: String, cause: Throwable? = null) : DownloadException(message, cause)
    class FileNotFoundException(message: String, cause: Throwable? = null) : DownloadException(message, cause)
    class CancelledException(message: String = "Download was cancelled") : DownloadException(message)
}
