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
        val readingProgress = readingStatusManager.getReadingProgress(file.absolutePath)
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
        
        val newStatus = when {
            currentIndex >= actualTotalCount - 1 -> {
                readingStatusManager.markAsCompleted(zipFile.file.absolutePath, actualTotalCount)
                com.celstech.satendroid.ui.models.ReadingStatus.COMPLETED
            }
            currentIndex > 0 -> {
                readingStatusManager.markAsReading(zipFile.file.absolutePath, currentIndex, actualTotalCount)
                com.celstech.satendroid.ui.models.ReadingStatus.READING
            }
            else -> {
                readingStatusManager.markAsUnread(zipFile.file.absolutePath)
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
