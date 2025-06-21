package com.celstech.satendroid.utils

import android.content.Context
import com.celstech.satendroid.ui.models.LocalItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * LocalItemを作成するためのファクトリークラス
 * サムネイル生成と読書状況の取得を統合
 */
class LocalItemFactory(private val context: Context) {
    
    private val readingStatusManager = ReadingStatusManager(context)
    
    /**
     * ファイルからLocalItemを作成（汎用メソッド）
     */
    suspend fun createLocalItem(file: File): LocalItem? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        
        when {
            file.isDirectory -> {
                val zipCount = file.listFiles { f ->
                    f.isFile && f.extension.lowercase() == "zip"
                }?.size ?: 0
                createFolderItem(file, file.name, zipCount)
            }
            file.extension.lowercase() == "zip" -> createZipFileItem(file, file.name)
            else -> null
        }
    }
    
    /**
     * 複数のファイルからLocalItemリストを作成
     */
    suspend fun createLocalItems(files: List<File>): List<LocalItem> = withContext(Dispatchers.IO) {
        files.mapNotNull { file ->
            createLocalItem(file)
        }
    }
    
    /**
     * フォルダからLocalItem.Folderを作成
     */
    suspend fun createFolderItem(
        folder: File, 
        relativePath: String, 
        zipCount: Int
    ): LocalItem.Folder = withContext(Dispatchers.IO) {
        LocalItem.Folder(
            name = folder.name,
            path = relativePath,
            lastModified = folder.lastModified(),
            zipCount = zipCount
        )
    }
    
    /**
     * ZIPファイルからLocalItem.ZipFileを作成
     */
    suspend fun createZipFileItem(
        file: File, 
        relativePath: String
    ): LocalItem.ZipFile = withContext(Dispatchers.IO) {
        // 順次実行（並行処理を避けてエラーを回避）
        val thumbnail = ThumbnailGenerator.generateThumbnail(file)
        
        // デバッグ: 読書状態取得に使用するパスを確認
        val filePathForReading = file.absolutePath
        println("DEBUG: Reading status path for '${file.name}': $filePathForReading")
        
        val readingProgress = readingStatusManager.getReadingProgress(filePathForReading)
        println("DEBUG: Reading status for '${file.name}': ${readingProgress.status}")
        
        val imageCount = getImageCountInZip(file)
        
        LocalItem.ZipFile(
            name = file.name,
            path = relativePath,
            lastModified = file.lastModified(),
            size = file.length(),
            file = file,
            readingStatus = readingProgress.status,
            thumbnail = thumbnail,
            currentImageIndex = readingProgress.currentIndex,
            totalImageCount = imageCount
        )
    }
    
    /**
     * ZIPファイル内の画像数を取得
     */
    private suspend fun getImageCountInZip(file: File): Int = withContext(Dispatchers.IO) {
        try {
            val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp")
            
            java.util.zip.ZipFile(file).use { zipFile ->
                zipFile.entries().asSequence()
                    .filter { entry ->
                        !entry.isDirectory && 
                        entry.name.substringAfterLast('.', "").lowercase() in imageExtensions
                    }
                    .count()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            0
        }
    }
    
    /**
     * LocalItem.ZipFileの読書状況を更新
     */
    suspend fun updateReadingStatus(
        zipFile: LocalItem.ZipFile,
        currentIndex: Int,
        totalCount: Int? = null
    ): LocalItem.ZipFile {
        val actualTotalCount = totalCount ?: zipFile.totalImageCount
        val filePathForReading = zipFile.file.absolutePath
        
        println("DEBUG: Updating reading status - File: ${zipFile.name}, Path: $filePathForReading, Index: $currentIndex, Total: $actualTotalCount")
        
        val newStatus = when {
            // 最後の画像まで見た場合は「既読」
            currentIndex >= actualTotalCount - 1 && actualTotalCount > 0 -> {
                readingStatusManager.markAsCompleted(filePathForReading, actualTotalCount)
                com.celstech.satendroid.ui.models.ReadingStatus.COMPLETED
            }
            // 1枚でも画像を見た場合、または明示的に読書開始した場合は「読書中」
            currentIndex >= 0 && actualTotalCount > 0 -> {
                readingStatusManager.markAsReading(filePathForReading, currentIndex, actualTotalCount)
                com.celstech.satendroid.ui.models.ReadingStatus.READING
            }
            // その他の場合は「未読」
            else -> {
                readingStatusManager.markAsUnread(filePathForReading)
                com.celstech.satendroid.ui.models.ReadingStatus.UNREAD
            }
        }
        
        return zipFile.copy(
            readingStatus = newStatus,
            currentImageIndex = currentIndex,
            totalImageCount = actualTotalCount
        )
    }
    
    /**
     * ファイル削除時に読書状況をクリア
     */
    suspend fun clearReadingStatusForFile(filePath: String) {
        readingStatusManager.clearReadingStatus(filePath)
    }
}
