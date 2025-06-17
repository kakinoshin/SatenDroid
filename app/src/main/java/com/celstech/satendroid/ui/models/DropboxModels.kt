package com.celstech.satendroid.ui.models

/**
 * Dropboxアイテムを表現するsealedクラス
 */
sealed class DropboxItem {
    abstract val name: String
    abstract val path: String

    data class Folder(
        override val name: String,
        override val path: String
    ) : DropboxItem()

    data class ZipFile(
        override val name: String,
        override val path: String,
        val size: Long
    ) : DropboxItem()
}

/**
 * ダウンロード進捗を追跡するためのデータクラス
 */
data class DownloadProgress(
    val fileName: String = "",
    val currentFileProgress: Float = 0f, // 0.0 to 1.0
    val currentFileIndex: Int = 0,
    val totalFiles: Int = 1,
    val downloadSpeed: String = "",
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val estimatedTimeRemaining: String = ""
) {
    val overallProgress: Float
        get() =
            if (totalFiles <= 1) currentFileProgress
            else (currentFileIndex.toFloat() + currentFileProgress) / totalFiles.toFloat()

    val progressText: String
        get() =
            if (totalFiles <= 1) "${(currentFileProgress * 100).toInt()}%"
            else "File ${currentFileIndex + 1}/$totalFiles (${(overallProgress * 100).toInt()}%)"
}
