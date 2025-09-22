package com.celstech.satendroid.download.exceptions

/**
 * ダウンロード関連の例外クラス
 */
sealed class DownloadException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    
    /**
     * ダウンロードがキャンセルされた場合
     */
    class CancelledException : DownloadException("Download was cancelled")
    
    /**
     * ネットワークエラー
     */
    class NetworkException(message: String, cause: Throwable? = null) : DownloadException(message, cause)
    
    /**
     * 認証エラー
     */
    class AuthenticationException(message: String, cause: Throwable? = null) : DownloadException(message, cause)
    
    /**
     * ファイルシステムエラー
     */
    class FileSystemException(message: String, cause: Throwable? = null) : DownloadException(message, cause)
    
    /**
     * その他のエラー
     */
    class GeneralException(message: String, cause: Throwable? = null) : DownloadException(message, cause)
}
