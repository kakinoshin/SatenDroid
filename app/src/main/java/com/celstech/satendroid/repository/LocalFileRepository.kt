package com.celstech.satendroid.repository

import android.content.Context
import android.os.Environment
import com.celstech.satendroid.ui.models.LocalItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ローカルファイル操作を担当するRepository
 */
class LocalFileRepository(private val context: Context) {

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
            item.file.delete()
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

    /**
     * フォルダアイテムを処理
     */
    private fun processFolderItem(child: File, path: String): LocalItem.Folder? {
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
            
            LocalItem.Folder(
                name = child.name,
                path = relativePath,
                lastModified = child.lastModified(),
                zipCount = zipCount
            )
        } else null
    }

    /**
     * ZIPファイルアイテムを処理
     */
    private fun processZipFileItem(child: File, path: String): LocalItem.ZipFile {
        val relativePath = if (path.isEmpty()) 
            child.name 
        else 
            "$path/${child.name}"
        
        return LocalItem.ZipFile(
            name = child.name,
            path = relativePath,
            lastModified = child.lastModified(),
            size = child.length(),
            file = child
        )
    }
}
