package com.celstech.satendroid.utils

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.celstech.satendroid.cache.ImageCacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
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
    
    // プリロード範囲（現在のページの前後何ページまでプリロードするか）
    private val preloadRange = 2
    
    // プリロード中のページを追跡（重複防止）
    private val preloadingPages = mutableSetOf<Int>()
    
    // 現在のZIPファイルのURI（キャッシュの一貫性確保）
    private var currentZipUri: Uri? = null
    
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
     * キャッシュから取得できない場合のみZIPから読み取り
     */
    suspend fun getImageData(imageEntry: ZipImageEntry): ByteArray? = withContext(Dispatchers.IO) {
        // キャッシュから取得を試行
        val cacheKey = imageEntry.id
        imageDataCache[cacheKey]?.let { 
            println("DEBUG: Cache hit for ${imageEntry.fileName}")
            return@withContext it 
        }
        
        // キャッシュにない場合は単体で読み込み
        println("DEBUG: Cache miss for ${imageEntry.fileName}, loading from ZIP")
        
        try {
            context.contentResolver.openInputStream(imageEntry.zipUri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zipInputStream ->
                    var entry = zipInputStream.nextEntry
                    
                    while (entry != null) {
                        if (entry.name == imageEntry.entryName) {
                            val imageData = zipInputStream.readBytes()
                            // メモリキャッシュに保存
                            imageDataCache[cacheKey] = imageData
                            println("DEBUG: Loaded ${imageEntry.fileName} (${imageData.size} bytes)")
                            return@withContext imageData
                        }
                        
                        zipInputStream.closeEntry()
                        entry = zipInputStream.nextEntry
                    }
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
        
        return@withContext null
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
    
    /**
     * 複数の画像エントリを効率的に一括読み込み（無効化）
     * パフォーマンス改善のため一時的に無効化
     */
    suspend fun batchLoadImages(imageEntries: List<ZipImageEntry>): Map<String, ByteArray> {
        println("DEBUG: Batch loading disabled for performance optimization")
        return emptyMap() // 何もしない
    }
    
    /**
     * 指定されたページの周辺画像をプリロード（無効化）
     * パフォーマンス改善のため一時的に無効化
     */
    suspend fun preloadAroundPage(imageEntries: List<ZipImageEntry>, currentPage: Int) {
        println("DEBUG: Preload disabled for performance optimization")
        return // 何もしない
    }
}
