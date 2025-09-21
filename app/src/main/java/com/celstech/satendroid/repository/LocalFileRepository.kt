package com.celstech.satendroid.repository

import android.content.Context
import android.os.Environment
import com.celstech.satendroid.ui.models.LocalItem
import com.celstech.satendroid.utils.LocalItemFactory
import com.celstech.satendroid.utils.SimpleReadingDataManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ローカルファイル操作を担当するRepository
 * シンプル読書データ管理システム使用
 */
class LocalFileRepository(private val context: Context) {

    private val localItemFactory = LocalItemFactory(context)
    private val readingDataManager = SimpleReadingDataManager(context)

    /**
     * 指定されたパスのディレクトリをスキャンしてLocalItemリストを取得
     */
    suspend fun scanDirectory(path: String = ""): List<LocalItem> = withContext(Dispatchers.IO) {
        val allItems = mutableListOf<LocalItem>()
        
        if (path.isEmpty()) {
            // ルートレベルスキャン（従来の複数ベースディレクトリ対応）
            val baseDirectories = getBaseDirectories()
            
            for (baseDir in baseDirectories) {
                if (!baseDir.exists() || !baseDir.isDirectory) continue
                
                println("DEBUG: Scanning base directory: ${baseDir.absolutePath}")
                
                val children = baseDir.listFiles() ?: continue
                
                for (child in children) {
                    when {
                        child.isDirectory -> {
                            val folderItem = processFolderItem(child)
                            if (folderItem != null) {
                                allItems.add(folderItem)
                            }
                        }
                        child.isFile && child.extension.lowercase() == "zip" -> {
                            val zipItem = processZipFileItem(child)
                            allItems.add(zipItem)
                        }
                    }
                }
            }
        } else {
            // 指定パスのスキャン（絶対パス対応）
            val targetDir = File(path)
            
            if (!targetDir.exists() || !targetDir.isDirectory) {
                println("DEBUG: Target directory does not exist: $path")
                return@withContext emptyList()
            }
            
            println("DEBUG: Scanning specified directory: ${targetDir.absolutePath}")
            
            val children = targetDir.listFiles() ?: return@withContext emptyList()
            
            for (child in children) {
                when {
                    child.isDirectory -> {
                        val folderItem = processFolderItem(child)
                        if (folderItem != null) {
                            allItems.add(folderItem)
                        }
                    }
                    child.isFile && child.extension.lowercase() == "zip" -> {
                        val zipItem = processZipFileItem(child)
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
                println("DEBUG: Repository deleted file: ${item.name}")
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
            // 絶対パスとして直接処理（パス統一修正）
            val folderFile = File(item.path)
            if (folderFile.exists() && folderFile.isDirectory) {
                val success = folderFile.deleteRecursively()
                if (success) {
                    println("DEBUG: Repository deleted folder: ${item.name}")
                } else {
                    println("DEBUG: Failed to delete folder recursively: ${item.name}")
                }
                return@withContext success
            }
            
            println("DEBUG: Folder not found at path: ${item.path}")
            false
        } catch (e: Exception) {
            println("DEBUG: Failed to delete folder: ${e.message}")
            e.printStackTrace()
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
     * フォルダアイテムを処理
     */
    private suspend fun processFolderItem(child: File): LocalItem.Folder? {
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
            // 絶対パスを使用
            val absolutePath = child.absolutePath
            
            // LocalItemFactoryを使用してサムネイル等を含むFolderアイテムを作成
            localItemFactory.createFolderItem(
                folder = child,
                relativePath = absolutePath, // 絶対パスに変更
                zipCount = zipCount
            )
        } else null
    }

    /**
     * ZIPファイルアイテムを処理
     */
    private suspend fun processZipFileItem(child: File): LocalItem.ZipFile {
        // 絶対パスを使用
        val absolutePath = child.absolutePath
        
        println("DEBUG: Processing ZIP file: ${child.absolutePath}")
        
        // LocalItemFactoryを使用してサムネイル等を含むZipFileアイテムを作成
        val zipFileItem = localItemFactory.createZipFileItem(
            file = child,
            relativePath = absolutePath // 絶対パスに変更
        )
        
        // 読書データ管理システムに総画像数を保存
        if (zipFileItem.totalImageCount > 0) {
            readingDataManager.saveTotalImages(
                filePath = child.absolutePath,
                totalImages = zipFileItem.totalImageCount
            )
        }
        
        println("DEBUG: Processed ZIP file: ${child.name}, totalImageCount: ${zipFileItem.totalImageCount}")
        
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
