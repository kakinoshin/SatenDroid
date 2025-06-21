package com.celstech.satendroid.repository

import android.content.Context
import android.os.Environment
import com.celstech.satendroid.ui.models.LocalItem
import com.celstech.satendroid.utils.LocalItemFactory
import com.celstech.satendroid.utils.ReadingStatusManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ローカルファイル操作を担当するRepository
 * サムネイル生成と読書状況管理機能を統合
 */
class LocalFileRepository(private val context: Context) {

    private val localItemFactory = LocalItemFactory(context)
    private val readingStatusManager = ReadingStatusManager(context)

    /**
     * 指定されたパスのディレクトリをスキャンしてLocalItemリストを取得
     */
    suspend fun scanDirectory(path: String = ""): List<LocalItem> = withContext(Dispatchers.IO) {
        val allItems = mutableListOf<LocalItem>()
        val baseDirectories = getBaseDirectories()
        
        // Scan the specified path within each base directory
        for (baseDir in baseDirectories) {
            val targetDir = if (path.isEmpty()) baseDir else File(baseDir, path)
            
            if (!targetDir.exists() || !targetDir.isDirectory) continue
            
            println("DEBUG: Scanning directory: ${targetDir.absolutePath}")
            
            val children = targetDir.listFiles() ?: continue
            
            for (child in children) {
                when {
                    child.isDirectory -> {
                        val folderItem = processFolderItem(child, path)
                        if (folderItem != null) {
                            allItems.add(folderItem)
                        }
                    }
                    child.isFile && child.extension.lowercase() == "zip" -> {
                        val zipItem = processZipFileItem(child, path)
                        allItems.add(zipItem)
                    }
                }
            }
        }
        
        // Remove duplicates and sort
        allItems
            .distinctBy { it.path }
            .sortedWith(compareBy<LocalItem> { it !is LocalItem.Folder }.thenBy { it.name })
    }

    /**
     * ファイルを削除
     */
    suspend fun deleteFile(item: LocalItem.ZipFile): Boolean = withContext(Dispatchers.IO) {
        try {
            val success = item.file.delete()
            if (success) {
                // 読書状況もクリア
                localItemFactory.clearReadingStatusForFile(item.path)
            }
            success
        } catch (e: Exception) {
            println("DEBUG: Failed to delete file: ${e.message}")
            false
        }
    }

    /**
     * フォルダを削除（再帰的）
     */
    suspend fun deleteFolder(item: LocalItem.Folder): Boolean = withContext(Dispatchers.IO) {
        try {
            val baseDirectories = getBaseDirectories()
            
            var deleted = false
            for (baseDir in baseDirectories) {
                val folderFile = File(baseDir, item.path)
                if (folderFile.exists() && folderFile.isDirectory) {
                    // フォルダ内のZIPファイルの読書状況をクリア
                    clearReadingStatusForFolder(folderFile)
                    deleted = folderFile.deleteRecursively() || deleted
                }
            }
            
            deleted
        } catch (e: Exception) {
            println("DEBUG: Failed to delete folder: ${e.message}")
            false
        }
    }

    /**
     * 複数のアイテムを削除
     */
    suspend fun deleteItems(items: Set<LocalItem>): Pair<Int, Int> = withContext(Dispatchers.IO) {
        var successCount = 0
        var failCount = 0
        
        items.forEach { item ->
            val success = when (item) {
                is LocalItem.ZipFile -> deleteFile(item)
                is LocalItem.Folder -> deleteFolder(item)
            }
            
            if (success) successCount++ else failCount++
        }
        
        println("DEBUG: Deleted $successCount items, failed to delete $failCount items")
        Pair(successCount, failCount)
    }

    /**
     * ZipFileの読書状況を更新
     */
    suspend fun updateReadingStatus(
        zipFile: LocalItem.ZipFile,
        currentIndex: Int,
        totalCount: Int? = null
    ): LocalItem.ZipFile {
        return localItemFactory.updateReadingStatus(zipFile, currentIndex, totalCount)
    }

    /**
     * フォルダ内のZIPファイルの読書状況をクリア
     */
    private suspend fun clearReadingStatusForFolder(folder: File) {
        try {
            folder.walk()
                .filter { it.isFile && it.extension.lowercase() == "zip" }
                .forEach { zipFile ->
                    localItemFactory.clearReadingStatusForFile(zipFile.absolutePath)
                }
        } catch (e: Exception) {
            println("DEBUG: Failed to clear reading status for folder: ${e.message}")
        }
    }

    /**
     * フォルダアイテムを処理
     */
    private suspend fun processFolderItem(child: File, path: String): LocalItem.Folder? {
        val subFiles = child.listFiles() ?: return null
        var hasZipFiles = false
        var hasSubfolders = false
        var zipCount = 0
        
        for (subFile in subFiles) {
            when {
                subFile.isFile && subFile.extension.lowercase() == "zip" -> {
                    hasZipFiles = true
                    zipCount++
                }
                subFile.isDirectory -> {
                    val subDirFiles = subFile.listFiles()
                    if (!subDirFiles.isNullOrEmpty()) {
                        hasSubfolders = true
                    }
                }
            }
        }
        
        return if (hasZipFiles || hasSubfolders) {
            val relativePath = if (path.isEmpty()) 
                child.name 
            else 
                "$path/${child.name}"
            
            // LocalItemFactoryを使用してサムネイル等を含むFolderアイテムを作成
            localItemFactory.createFolderItem(
                folder = child,
                relativePath = relativePath,
                zipCount = zipCount
            )
        } else null
    }

    /**
     * ZIPファイルアイテムを処理
     */
    private suspend fun processZipFileItem(child: File, path: String): LocalItem.ZipFile {
        val relativePath = if (path.isEmpty()) 
            child.name 
        else 
            "$path/${child.name}"
        
        // LocalItemFactoryを使用してサムネイル等を含むZipFileアイテムを作成
        val zipFileItem = localItemFactory.createZipFileItem(
            file = child,
            relativePath = relativePath
        )
        
        println("DEBUG: Created ZipFile item - Name: ${zipFileItem.name}, Status: ${zipFileItem.readingStatus}, Index: ${zipFileItem.currentImageIndex}/${zipFileItem.totalImageCount}")
        
        return zipFileItem
    }

    /**
     * ベースディレクトリリストを取得
     */
    private fun getBaseDirectories(): List<File> {
        val baseDirectories = mutableListOf<File>()
        
        // App-specific external files directory
        val appDownloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (appDownloadsDir != null && appDownloadsDir.exists()) {
            baseDirectories.add(appDownloadsDir)
        }
        
        // Public Downloads directory
        try {
            val publicDownloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (publicDownloadsDir != null && publicDownloadsDir.exists()) {
                baseDirectories.add(publicDownloadsDir)
            }
        } catch (e: Exception) {
            println("DEBUG: Cannot access public downloads directory: ${e.message}")
        }
        
        return baseDirectories
    }
}
