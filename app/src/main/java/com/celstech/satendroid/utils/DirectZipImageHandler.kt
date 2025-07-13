package com.celstech.satendroid.utils

import android.content.Context
import android.net.Uri
import com.celstech.satendroid.cache.ImageCacheManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.math.max
import kotlin.math.min

/**
 * 高速化されたZIPファイル直接読み取りハンドラー
 * パフォーマンス最適化版
 */
class DirectZipImageHandler(private val context: Context) {
    private val cacheManager = ImageCacheManager(context)
    
    // サポートされる画像拡張子
    private val supportedExtensions = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp")
    
    // スレッドセーフなメモリキャッシュ
    private val imageDataCache = ConcurrentHashMap<String, ByteArray>()
    
    // プリロード管理
    private val preloadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val preloadChannel = Channel<PreloadRequest>(Channel.UNLIMITED)
    private val preloadingPages = ConcurrentHashMap<Int, Job>()
    
    // 位置保存のバッチ処理
    private var positionSaveJob: Job? = null
    private val positionQueue = Channel<PositionSaveRequest>(Channel.UNLIMITED)
    
    // ZIP エントリキャッシュ（ファイル全体の読み込み回避）
    private val zipEntryCache = ConcurrentHashMap<String, ZipEntryData>()
    
    // 最適化: Zipファイルを開いたまま保持
    private var currentOpenZipFile: ZipFile? = null
    private var currentOpenZipUri: Uri? = null
    private var currentZipEntryMap: Map<String, ZipEntry> = emptyMap()
    
    // キャッシュサイズ制限
    private val maxCacheSize = 50 // 最大50画像をキャッシュ
    private val preloadRange = 3 // 前後3ページをプリロード
    
    // 現在のZIPファイル情報
    private var currentZipUri: Uri? = null
    private var currentZipEntries: List<ZipImageEntry> = emptyList()
    
    // パフォーマンス監視
    private val _performanceMetrics = MutableStateFlow(PerformanceMetrics())
    val performanceMetrics: StateFlow<PerformanceMetrics> = _performanceMetrics
    
    data class PreloadRequest(val entry: ZipImageEntry, val priority: Int)
    data class PositionSaveRequest(val zipUri: Uri, val imageIndex: Int, val zipFile: File?)
    data class ZipEntryData(val entries: List<ZipImageEntry>, val lastModified: Long)
    data class PerformanceMetrics(
        val cacheHitRate: Float = 0f,
        val averageLoadTime: Long = 0L,
        val preloadedImages: Int = 0,
        val totalRequests: Int = 0
    )
    
    init {
        // プリロードワーカーを開始
        startPreloadWorker()
        // 位置保存ワーカーを開始
        startPositionSaveWorker()
    }
    
    /**
     * プリロードワーカーの開始（優先度付きキュー処理）
     */
    private fun startPreloadWorker() {
        preloadScope.launch {
            for (request in preloadChannel) {
                // 重複チェック
                if (imageDataCache.containsKey(request.entry.id)) continue
                
                try {
                    val startTime = System.currentTimeMillis()
                    val imageData = loadImageFromZip(request.entry)
                    val loadTime = System.currentTimeMillis() - startTime
                    
                    if (imageData != null) {
                        // キャッシュサイズチェック
                        if (imageDataCache.size >= maxCacheSize) {
                            cleanupOldCache()
                        }
                        
                        imageDataCache[request.entry.id] = imageData
                        println("DEBUG: Preloaded ${request.entry.fileName} in ${loadTime}ms")
                        
                        // メトリクス更新
                        updateMetrics(loadTime, isPreload = true)
                    }
                } catch (e: Exception) {
                    println("DEBUG: Preload failed for ${request.entry.fileName}: ${e.message}")
                }
            }
        }
    }
    
    /**
     * 位置保存ワーカーの開始（バッチ処理）
     */
    private fun startPositionSaveWorker() {
        preloadScope.launch {
            var lastSaveRequest: PositionSaveRequest? = null
            
            for (request in positionQueue) {
                lastSaveRequest = request
                // 500ms待機して、その間に新しいリクエストが来れば最新のものを使用
                delay(500)
                
                // チャンネルから最新のリクエストを取得
                var latestRequest = lastSaveRequest
                while (!positionQueue.isEmpty) {
                    latestRequest = positionQueue.tryReceive().getOrNull() ?: latestRequest
                }
                
                // 最新の位置を保存
                if (latestRequest != null) {
                    try {
                        cacheManager.saveCurrentPosition(
                            latestRequest.zipUri, 
                            latestRequest.imageIndex, 
                            latestRequest.zipFile
                        )
                        println("DEBUG: Batch saved position ${latestRequest.imageIndex}")
                    } catch (e: Exception) {
                        println("DEBUG: Position save failed: ${e.message}")
                    }
                }
            }
        }
    }
    
    /**
     * ZIPファイルから画像エントリのリストを取得（高速化・非同期版）
     */
    suspend fun getImageEntriesFromZip(zipUri: Uri, zipFile: File? = null): List<ZipImageEntry> = withContext(Dispatchers.IO) {
        val zipKey = generateFileIdentifier(zipUri, zipFile)
        val fileModified = zipFile?.lastModified() ?: System.currentTimeMillis()
        
        // キャッシュから取得を試行
        zipEntryCache[zipKey]?.let { cachedData ->
            if (cachedData.lastModified == fileModified) {
                currentZipUri = zipUri
                currentZipEntries = cachedData.entries
                println("DEBUG: Using cached entries for ${zipFile?.name ?: zipUri}")
                return@withContext cachedData.entries
            }
        }
        
        println("DEBUG: Scanning ZIP file entries for ${zipFile?.name ?: zipUri}")
        val imageEntries = mutableListOf<ZipImageEntry>()
        
        try {
            context.contentResolver.openInputStream(zipUri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zipInputStream ->
                    var entry = zipInputStream.nextEntry
                    var index = 0
                    var processedCount = 0
                    
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
                        
                        // 進行状況を定期的に報告（重い処理のため）
                        processedCount++
                        if (processedCount % 50 == 0) {
                            println("DEBUG: Processed $processedCount entries, found ${imageEntries.size} images...")
                            yield() // 他のコルーチンに処理を譲る
                        }
                    }
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
            return@withContext emptyList()
        }
        
        println("DEBUG: Found ${imageEntries.size} images, sorting...")
        
        // ファイル名でソート（バックグラウンドで実行）
        val sortedEntries = imageEntries.sortedBy { it.fileName }
        
        // キャッシュに保存
        zipEntryCache[zipKey] = ZipEntryData(sortedEntries, fileModified)
        currentZipUri = zipUri
        currentZipEntries = sortedEntries
        
        println("DEBUG: ZIP entry scanning completed - ${sortedEntries.size} images ready")
        return@withContext sortedEntries
    }
    
    /**
     * 指定された画像エントリの画像データを取得（高速化版）
     */
    suspend fun getImageData(imageEntry: ZipImageEntry): ByteArray? = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        
        // キャッシュから取得を試行
        imageDataCache[imageEntry.id]?.let { 
            println("DEBUG: Cache hit for ${imageEntry.fileName}")
            updateMetrics(System.currentTimeMillis() - startTime, isHit = true)
            return@withContext it 
        }
        
        // キャッシュにない場合は読み込み
        println("DEBUG: Cache miss for ${imageEntry.fileName}, loading from ZIP")
        
        val imageData = loadImageFromZip(imageEntry)
        val loadTime = System.currentTimeMillis() - startTime
        
        if (imageData != null) {
            // キャッシュサイズチェック
            if (imageDataCache.size >= maxCacheSize) {
                cleanupOldCache()
            }
            
            imageDataCache[imageEntry.id] = imageData
            println("DEBUG: Loaded ${imageEntry.fileName} (${imageData.size} bytes) in ${loadTime}ms")
            
            // プリロードは遅延実行（初回表示への影響を最小化）
            preloadScope.launch {
                delay(500) // 500ms後にプリロード開始
                triggerPreload(imageEntry.index)
            }
        }
        
        updateMetrics(loadTime, isHit = false)
        return@withContext imageData
    }
    
    /**
     * ZIPファイルから直接画像を読み込み（最適化版）
     */
    private suspend fun loadImageFromZip(imageEntry: ZipImageEntry): ByteArray? = withContext(Dispatchers.IO) {
        try {
            // 最適化: 同じZipファイルなら開いたまま使用
            val optimizedResult = tryOptimizedRead(imageEntry)
            if (optimizedResult != null) {
                return@withContext optimizedResult
            }
            
            // フォールバック: 従来の方法
            context.contentResolver.openInputStream(imageEntry.zipUri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zipInputStream ->
                    var entry = zipInputStream.nextEntry
                    
                    while (entry != null) {
                        if (entry.name == imageEntry.entryName) {
                            return@withContext zipInputStream.readBytes()
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
     * 最適化された読み込みを試行
     */
    @Synchronized
    private fun tryOptimizedRead(imageEntry: ZipImageEntry): ByteArray? {
        return try {
            // 同じZipファイルでない場合は新しく開く
            if (currentOpenZipFile == null || currentOpenZipUri != imageEntry.zipUri) {
                openZipFileForOptimization(imageEntry.zipUri, imageEntry.zipFile)
            }
            
            // 開いているZipファイルから直接読み取り
            val zipFile = currentOpenZipFile ?: return null
            val zipEntry = currentZipEntryMap[imageEntry.entryName] ?: return null
            
            zipFile.getInputStream(zipEntry)?.use { inputStream ->
                inputStream.readBytes()
            }
        } catch (e: Exception) {
            println("DEBUG: Optimized read failed: ${e.message}")
            null
        }
    }
    
    /**
     * 最適化用にZipファイルを開く（高速化版）
     */
    private fun openZipFileForOptimization(zipUri: Uri, zipFile: File?) {
        try {
            // 古いファイルを閉じる
            currentOpenZipFile?.close()
            
            val actualZipFile = zipFile ?: run {
                if (zipUri.scheme == "file") {
                    File(zipUri.path!!)
                } else {
                    return
                }
            }
            
            if (!actualZipFile.exists()) return
            
            println("DEBUG: Opening ZIP for optimization: ${actualZipFile.name}")
            
            currentOpenZipFile = ZipFile(actualZipFile)
            currentOpenZipUri = zipUri
            
            // エントリマップを効率的に作成（必要最小限）
            val entryMap = mutableMapOf<String, ZipEntry>()
            val entries = currentOpenZipFile!!.entries()
            var mapCount = 0
            
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                // 画像ファイルのみをマップに追加（高速化）
                val fileName = entry.name.substringAfterLast('/')
                val fileExtension = fileName.substringAfterLast('.', "").lowercase()
                if (!entry.isDirectory && fileExtension in supportedExtensions) {
                    entryMap[entry.name] = entry
                    mapCount++
                }
            }
            currentZipEntryMap = entryMap
            
            println("DEBUG: ZIP optimization ready - ${mapCount} image entries mapped")
        } catch (e: Exception) {
            println("DEBUG: Failed to open ZIP for optimization: ${e.message}")
            currentOpenZipFile = null
            currentOpenZipUri = null
            currentZipEntryMap = emptyMap()
        }
    }
    
    /**
     * 現在のZipファイルを閉じる
     */
    fun closeCurrentZipFile() {
        try {
            currentOpenZipFile?.close()
            currentOpenZipFile = null
            currentOpenZipUri = null
            currentZipEntryMap = emptyMap()
            println("DEBUG: Closed current ZIP file")
        } catch (e: Exception) {
            println("DEBUG: Error closing ZIP file: ${e.message}")
        }
    }
    
    /**
     * プリロードをトリガー（最適化版 - 初回表示への影響を最小化）
     */
    private fun triggerPreload(currentIndex: Int) {
        if (currentZipEntries.isEmpty()) return
        
        // 初回表示を優先するため、少し遅延してからプリロードを開始
        preloadScope.launch {
            delay(200) // 200ms遅延
            
            val startIndex = max(0, currentIndex - preloadRange)
            val endIndex = min(currentZipEntries.size - 1, currentIndex + preloadRange)
            
            println("DEBUG: Starting preload for range $startIndex to $endIndex (current: $currentIndex)")
            
            for (i in startIndex..endIndex) {
                if (i == currentIndex) continue // 現在のページはスキップ
                
                val entry = currentZipEntries[i]
                if (!imageDataCache.containsKey(entry.id) && !preloadingPages.containsKey(i)) {
                    // 距離に基づく優先度設定（近いページほど高優先度）
                    val priority = preloadRange - kotlin.math.abs(i - currentIndex)
                    
                    // 少し間隔を空けてプリロード（CPUの負荷を分散）
                    delay(50)
                    preloadChannel.send(PreloadRequest(entry, priority))
                }
            }
        }
    }
    
    /**
     * 古いキャッシュのクリーンアップ（LRU的な動作）
     */
    private fun cleanupOldCache() {
        if (imageDataCache.size <= maxCacheSize) return
        
        // 現在表示中の範囲外のキャッシュを削除
        val currentIndex = currentZipEntries.indexOfFirst { 
            imageDataCache.containsKey(it.id) 
        }
        
        if (currentIndex != -1) {
            val keysToRemove = mutableListOf<String>()
            val protectedRange = max(0, currentIndex - preloadRange)..min(currentZipEntries.size - 1, currentIndex + preloadRange)
            
            imageDataCache.keys.forEach { key ->
                val entryIndex = currentZipEntries.indexOfFirst { it.id == key }
                if (entryIndex == -1 || entryIndex !in protectedRange) {
                    keysToRemove.add(key)
                }
            }
            
            // 一定数のキャッシュを削除
            keysToRemove.take(imageDataCache.size - maxCacheSize + 5).forEach { key ->
                imageDataCache.remove(key)
            }
        }
        
        // GCを提案
        if (imageDataCache.size > maxCacheSize) {
            System.gc()
        }
    }
    
    /**
     * 位置保存（バッチ処理）
     */
    fun saveCurrentPosition(zipUri: Uri, imageIndex: Int, zipFile: File? = null) {
        preloadScope.launch {
            positionQueue.send(PositionSaveRequest(zipUri, imageIndex, zipFile))
        }
    }
    
    /**
     * パフォーマンスメトリクスの更新（軽量化版）
     */
    private fun updateMetrics(loadTime: Long, isHit: Boolean = false, isPreload: Boolean = false) {
        // メトリクス更新をバックグラウンドで実行（メインスレッドの負荷軽減）
        preloadScope.launch {
            try {
                val currentMetrics = _performanceMetrics.value
                val newTotalRequests = currentMetrics.totalRequests + 1
                val newHits = if (isHit) newTotalRequests * currentMetrics.cacheHitRate + 1 else newTotalRequests * currentMetrics.cacheHitRate
                val newPreloadedImages = if (isPreload) currentMetrics.preloadedImages + 1 else currentMetrics.preloadedImages
                
                _performanceMetrics.value = currentMetrics.copy(
                    cacheHitRate = newHits / newTotalRequests,
                    averageLoadTime = (currentMetrics.averageLoadTime + loadTime) / 2,
                    preloadedImages = newPreloadedImages,
                    totalRequests = newTotalRequests
                )
            } catch (e: Exception) {
                // メトリクス更新エラーは無視（パフォーマンス優先）
            }
        }
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
        zipEntryCache.clear()
        preloadingPages.clear()
        System.gc()
    }
    
    /**
     * リソースのクリーンアップ
     */
    fun cleanup() {
        closeCurrentZipFile()
        preloadScope.cancel()
        clearMemoryCache()
    }
    
    // 既存メソッドのデリゲート
    fun getCacheManager(): ImageCacheManager = cacheManager
    
    fun generateFileIdentifier(zipUri: Uri, zipFile: File? = null): String {
        return try {
            when {
                zipFile != null && zipFile.exists() -> zipFile.absolutePath
                zipUri.scheme == "file" -> {
                    val path = zipUri.path ?: zipUri.toString()
                    File(path).absolutePath
                }
                else -> zipUri.toString()
            }
        } catch (e: Exception) {
            zipUri.toString()
        }
    }
    
    fun getSavedPosition(zipUri: Uri, zipFile: File? = null): Int? {
        return cacheManager.getSavedPosition(zipUri, zipFile)
    }
    
    fun onZipFileDeleted(zipUri: Uri, zipFile: File? = null) {
        cacheManager.onFileDeleted(zipUri, zipFile)
        val fileId = generateFileIdentifier(zipUri, zipFile)
        imageDataCache.keys.removeAll { it.startsWith(fileId) }
        zipEntryCache.remove(fileId)
        
        // 削除されたファイルが現在開いているファイルの場合は閉じる
        if (currentOpenZipUri == zipUri) {
            closeCurrentZipFile()
        }
    }
    
    /**
     * 複数の画像エントリを効率的に一括読み込み（廃止予定）
     * 新しいプリロード機能で置き換え
     */
    @Deprecated("Use preload functionality instead")
    suspend fun batchLoadImages(imageEntries: List<ZipImageEntry>): Map<String, ByteArray> {
        println("DEBUG: batchLoadImages is deprecated, using new preload system")
        return emptyMap()
    }
    
    /**
     * 指定されたページの周辺画像をプリロード（廃止予定）
     * 新しいプリロード機能で置き換え
     */
    @Deprecated("Use automatic preload functionality instead")
    suspend fun preloadAroundPage(imageEntries: List<ZipImageEntry>, currentPage: Int) {
        println("DEBUG: preloadAroundPage is deprecated, using automatic preload")
    }
}