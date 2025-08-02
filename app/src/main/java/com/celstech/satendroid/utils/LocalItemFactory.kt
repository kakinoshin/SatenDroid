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
    
    // 画像数のキャッシュ (ファイルパス -> 画像数)
    private val imageCountCache = mutableMapOf<String, Pair<Long, Int>>() // (lastModified, imageCount)
    
    companion object {
        private const val IMAGE_COUNT_CACHE_PREFS = "image_count_cache"
        private const val CACHE_PREFIX = "count_"
        private const val CACHE_TIME_PREFIX = "time_"
    }
    
    /**
     * キャッシュされた画像数を取得
     */
    private fun getCachedImageCount(file: File): Int? {
        val prefs = context.getSharedPreferences(IMAGE_COUNT_CACHE_PREFS, Context.MODE_PRIVATE)
        val key = file.absolutePath
        val cachedTime = prefs.getLong(CACHE_TIME_PREFIX + key, 0)
        val cachedCount = prefs.getInt(CACHE_PREFIX + key, -1)
        
        return if (cachedTime == file.lastModified() && cachedCount >= 0) {
            println("DEBUG: Using cached image count for ${file.name}: $cachedCount")
            cachedCount
        } else {
            null
        }
    }
    
    /**
     * 画像数をキャッシュに保存
     */
    private fun saveCachedImageCount(file: File, count: Int) {
        val prefs = context.getSharedPreferences(IMAGE_COUNT_CACHE_PREFS, Context.MODE_PRIVATE)
        val key = file.absolutePath
        prefs.edit().apply {
            putLong(CACHE_TIME_PREFIX + key, file.lastModified())
            putInt(CACHE_PREFIX + key, count)
            apply()
        }
        println("DEBUG: Cached image count for ${file.name}: $count")
    }
    
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
        println("DEBUG: Creating ZipFileItem for: ${file.name}")
        
        // 順次実行（並行処理を避けてエラーを回避）
        val thumbnail = ThumbnailGenerator.generateThumbnail(file)
        val imageCount = getImageCountInZip(file)
        
        println("DEBUG: ZipFileItem created - File: ${file.name}, ImageCount: $imageCount")
        
        LocalItem.ZipFile(
            name = file.name,
            path = relativePath,
            lastModified = file.lastModified(),
            size = file.length(),
            file = file,
            thumbnail = thumbnail,
            totalImageCount = imageCount
        )
    }
    
    /**
     * ZIPファイル内の画像数を取得
     */
    private suspend fun getImageCountInZip(file: File): Int = withContext(Dispatchers.IO) {
        // キャッシュから取得を試行
        val cachedCount = getCachedImageCount(file)
        if (cachedCount != null) {
            return@withContext cachedCount
        }
        
        try {
            println("DEBUG: Counting images in ZIP: ${file.absolutePath}")
            
            // ファイルの基本検証
            if (!file.exists()) {
                println("DEBUG: File does not exist: ${file.absolutePath}")
                return@withContext 0
            }
            
            if (!file.canRead()) {
                println("DEBUG: Cannot read file: ${file.absolutePath}")
                return@withContext 0
            }
            
            if (file.length() == 0L) {
                println("DEBUG: File is empty: ${file.absolutePath}")
                return@withContext 0
            }
            
            val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp")
            
            java.util.zip.ZipFile(file).use { zipFile ->
                var totalEntries = 0
                var imageEntries = 0
                
                val imageCount = zipFile.entries().asSequence()
                    .onEach { entry -> 
                        totalEntries++
                        if (!entry.isDirectory) {
                            val fileName = entry.name.substringAfterLast('/')
                            val fileExtension = fileName.substringAfterLast('.', "").lowercase()
                            if (fileExtension in imageExtensions) {
                                imageEntries++
                                println("DEBUG: Found image entry: ${entry.name}")
                            }
                        }
                    }
                    .filter { entry ->
                        if (entry.isDirectory) return@filter false
                        val fileName = entry.name.substringAfterLast('/')
                        val fileExtension = fileName.substringAfterLast('.', "").lowercase()
                        fileExtension in imageExtensions
                    }
                    .count()
                
                println("DEBUG: ZIP ${file.name} - Total entries: $totalEntries, Image entries: $imageEntries, Final count: $imageCount")
                
                // キャッシュに保存
                saveCachedImageCount(file, imageCount)
                
                imageCount
            }
        } catch (e: java.util.zip.ZipException) {
            println("DEBUG: Invalid ZIP file ${file.name}: ${e.message}")
            e.printStackTrace()
            0
        } catch (e: SecurityException) {
            println("DEBUG: Security error accessing ${file.name}: ${e.message}")
            e.printStackTrace()
            0
        } catch (e: Exception) {
            println("DEBUG: Error counting images in ${file.name}: ${e.message}")
            e.printStackTrace()
            0
        }
    }
    
    /**
     * ZIPファイルの読書状況を更新
     */
    suspend fun updateReadingStatus(
        zipFile: LocalItem.ZipFile,
        currentIndex: Int
    ) {
        val filePathForReading = zipFile.file.absolutePath
        
        when {
            // 最後の画像まで見た場合は「既読」
            currentIndex >= zipFile.totalImageCount - 1 && zipFile.totalImageCount > 0 -> {
                readingStatusManager.markAsCompleted(filePathForReading, currentIndex)
            }
            // 1枚でも画像を見た場合、または明示的に読書開始した場合は「読書中」
            currentIndex >= 0 && zipFile.totalImageCount > 0 -> {
                readingStatusManager.markAsReading(filePathForReading, currentIndex)
            }
            // その他の場合は「未読」
            else -> {
                readingStatusManager.markAsUnread(filePathForReading)
            }
        }
    }
    
    /**
     * ファイル削除時に読書状況をクリア
     */
    suspend fun clearReadingStatusForFile(filePath: String) {
        readingStatusManager.clearReadingStatus(filePath)
    }
}
