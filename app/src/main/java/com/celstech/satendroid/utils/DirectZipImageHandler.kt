package com.celstech.satendroid.utils

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.celstech.satendroid.cache.ImageCacheManager
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * ZIPファイルから直接画像を読み取るハンドラー
 * テンポラリファイルに展開せずにメモリ内で処理する
 */
class DirectZipImageHandler(private val context: Context) {
    private val cacheManager = ImageCacheManager(context)
    
    // サポートされる画像拡張子
    private val supportedExtensions = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp")
    
    // メモリキャッシュ（画像データを一時的に保存）
    private val imageDataCache = mutableMapOf<String, ByteArray>()
    
    /**
     * ZIPファイルから画像エントリのリストを取得
     */
    fun getImageEntriesFromZip(zipUri: Uri, zipFile: File? = null): List<ZipImageEntry> {
        val imageEntries = mutableListOf<ZipImageEntry>()
        
        try {
            context.contentResolver.openInputStream(zipUri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zipInputStream ->
                    var entry = zipInputStream.nextEntry
                    var index = 0
                    
                    while (entry != null) {
                        val entryName = entry.name
                        val fileName = entryName.substringAfterLast('/')
                        val fileExtension = fileName.substringAfterLast('.', "").lowercase()
                        
                        if (!entry.isDirectory && fileExtension in supportedExtensions) {
                            val zipImageEntry = ZipImageEntry(
                                zipUri = zipUri,
                                zipFile = zipFile,
                                entryName = entryName,
                                fileName = fileName,
                                size = entry.size,
                                index = index++
                            )
                            imageEntries.add(zipImageEntry)
                        }
                        
                        zipInputStream.closeEntry()
                        entry = zipInputStream.nextEntry
                    }
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
        
        // ファイル名でソート
        return imageEntries.sortedBy { it.fileName }
    }
    
    /**
     * 指定された画像エントリの画像データを取得
     */
    fun getImageData(imageEntry: ZipImageEntry): ByteArray? {
        // キャッシュから取得を試行
        val cacheKey = imageEntry.id
        imageDataCache[cacheKey]?.let { return it }
        
        // ZIPから読み取り
        try {
            context.contentResolver.openInputStream(imageEntry.zipUri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zipInputStream ->
                    var entry = zipInputStream.nextEntry
                    
                    while (entry != null) {
                        if (entry.name == imageEntry.entryName) {
                            val imageData = zipInputStream.readBytes()
                            // メモリキャッシュに保存
                            imageDataCache[cacheKey] = imageData
                            return imageData
                        }
                        
                        zipInputStream.closeEntry()
                        entry = zipInputStream.nextEntry
                    }
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
        
        return null
    }
    
    /**
     * キャッシュされた画像データを取得
     */
    fun getCachedImageData(imageEntry: ZipImageEntry): ByteArray? {
        return imageDataCache[imageEntry.id]
    }
    
    /**
     * メモリキャッシュをクリア
     */
    fun clearMemoryCache() {
        imageDataCache.clear()
        System.gc()
    }
    
    /**
     * キャッシュマネージャーを取得
     */
    fun getCacheManager(): ImageCacheManager = cacheManager
    
    /**
     * ファイル識別子を生成（エンコーディング統一）
     */
    fun generateFileIdentifier(zipUri: Uri, zipFile: File? = null): String {
        return try {
            when {
                zipFile != null && zipFile.exists() -> {
                    // Fileオブジェクトがある場合は絶対パスを使用（最も確実）
                    zipFile.absolutePath
                }
                zipUri.scheme == "file" -> {
                    // File URIの場合はパスを正規化
                    val path = zipUri.path ?: zipUri.toString()
                    File(path).absolutePath
                }
                else -> {
                    // Content URIの場合はそのまま使用（正規化困難）
                    zipUri.toString()
                }
            }
        } catch (e: Exception) {
            // フォールバック：URIをそのまま文字列化
            zipUri.toString()
        }
    }
    
    /**
     * 現在の表示位置を保存
     */
    fun saveCurrentPosition(zipUri: Uri, imageIndex: Int, zipFile: File? = null) {
        cacheManager.saveCurrentPosition(zipUri, imageIndex, zipFile)
    }
    
    /**
     * 保存された表示位置を取得
     */
    fun getSavedPosition(zipUri: Uri, zipFile: File? = null): Int? {
        return cacheManager.getSavedPosition(zipUri, zipFile)
    }
    
    /**
     * ZIPファイル削除時の処理
     */
    fun onZipFileDeleted(zipUri: Uri, zipFile: File? = null) {
        cacheManager.onFileDeleted(zipUri, zipFile)
        // 該当ファイルのメモリキャッシュも削除
        val fileId = generateFileIdentifier(zipUri, zipFile)
        imageDataCache.keys.removeAll { it.startsWith(fileId) }
    }
}
