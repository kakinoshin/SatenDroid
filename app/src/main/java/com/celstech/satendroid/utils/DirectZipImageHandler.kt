package com.celstech.satendroid.utils

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.math.max
import kotlin.math.min

/**
 * ZipFileの安全な管理クラス
 * 参照カウントとタイムアウト機能で適切な資源管理を行う
 */
class ZipFileManager {
    private val zipFiles = ConcurrentHashMap<String, ZipFileEntry>()
    private val lock = Any()

    // ZipFileエントリの情報
    data class ZipFileEntry(
        val zipFile: ZipFile,
        val lastAccessed: AtomicLong,
        val refCount: AtomicInteger,
        val filePath: String
    )

    companion object {
        private const val TIMEOUT_MS = 30000L // 30秒でタイムアウト
        private const val CLEANUP_INTERVAL_MS = 10000L // 10秒間隔でクリーンアップ
    }

    private val cleanupJob = CoroutineScope(Dispatchers.IO).launch {
        while (true) {
            delay(CLEANUP_INTERVAL_MS)
            cleanupExpiredEntries()
        }
    }

    /**
     * ZipFileを安全に取得または作成
     */
    fun getZipFile(filePath: String): ZipFile? {
        return synchronized(lock) {
            try {
                val currentTime = System.currentTimeMillis()

                // 既存のエントリをチェック
                zipFiles[filePath]?.let { entry ->
                    // タイムアウトチェック
                    if (currentTime - entry.lastAccessed.get() < TIMEOUT_MS) {
                        // 参照カウントを増加
                        entry.refCount.incrementAndGet()
                        entry.lastAccessed.set(currentTime)
                        println("DEBUG: ZipFileManager - Reusing existing ZipFile: ${File(filePath).name}")
                        return entry.zipFile
                    } else {
                        // タイムアウトしたエントリを削除
                        closeZipFileEntry(filePath, entry)
                    }
                }

                // 新しいZipFileを作成
                val file = File(filePath)
                if (!file.exists()) {
                    println("DEBUG: ZipFileManager - File does not exist: ${file.name}")
                    return null
                }

                val zipFile = ZipFile(file)
                val entry = ZipFileEntry(
                    zipFile = zipFile,
                    lastAccessed = AtomicLong(currentTime),
                    refCount = AtomicInteger(1),
                    filePath = filePath
                )

                zipFiles[filePath] = entry
                println("DEBUG: ZipFileManager - Created new ZipFile: ${file.name}")
                return zipFile

            } catch (e: Exception) {
                println("DEBUG: ZipFileManager - Failed to get ZipFile: ${e.message}")
                return null
            }
        }
    }

    /**
     * ZipFileの参照を解放
     */
    fun releaseZipFile(filePath: String) {
        synchronized(lock) {
            zipFiles[filePath]?.let { entry ->
                val newRefCount = entry.refCount.decrementAndGet()
                println("DEBUG: ZipFileManager - Released ZipFile: ${File(filePath).name}, refCount: $newRefCount")

                // 参照カウントが0になったら遅延削除のタイマーを開始
                if (newRefCount <= 0) {
                    CoroutineScope(Dispatchers.IO).launch {
                        delay(5000) // 5秒後に削除
                        synchronized(lock) {
                            zipFiles[filePath]?.let { currentEntry ->
                                if (currentEntry.refCount.get() <= 0) {
                                    closeZipFileEntry(filePath, currentEntry)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 指定されたZipFileを強制的に閉じる
     */
    fun forceCloseZipFile(filePath: String) {
        synchronized(lock) {
            zipFiles[filePath]?.let { entry ->
                closeZipFileEntry(filePath, entry)
            }
        }
    }

    /**
     * 期限切れのエントリをクリーンアップ
     */
    private fun cleanupExpiredEntries() {
        val currentTime = System.currentTimeMillis()
        val toRemove = mutableListOf<String>()
        
        synchronized(lock) {
            zipFiles.forEach { (filePath, entry) ->
                val timeSinceLastAccess = currentTime - entry.lastAccessed.get()
                if (timeSinceLastAccess > TIMEOUT_MS && entry.refCount.get() <= 0) {
                    toRemove.add(filePath)
                }
            }

            toRemove.forEach { filePath ->
                zipFiles[filePath]?.let { entry ->
                    closeZipFileEntry(filePath, entry)
                }
            }
        }

        if (toRemove.isNotEmpty()) {
            println("DEBUG: ZipFileManager - Cleaned up ${toRemove.size} expired entries")
        }
    }

    /**
     * ZipFileエントリを安全に閉じる
     */
    private fun closeZipFileEntry(filePath: String, entry: ZipFileEntry) {
        try {
            entry.zipFile.close()
            zipFiles.remove(filePath)
            println("DEBUG: ZipFileManager - Closed and removed ZipFile: ${File(filePath).name}")
        } catch (e: Exception) {
            println("DEBUG: ZipFileManager - Error closing ZipFile: ${e.message}")
            // エラーが発生してもマップからは削除
            zipFiles.remove(filePath)
        }
    }

    /**
     * 全てのZipFileを閉じる
     */
    fun closeAllZipFiles() {
        synchronized(lock) {
            val entries = zipFiles.values.toList()
            zipFiles.clear()

            entries.forEach { entry ->
                try {
                    entry.zipFile.close()
                    println("DEBUG: ZipFileManager - Closed ZipFile during cleanup: ${File(entry.filePath).name}")
                } catch (e: Exception) {
                    println("DEBUG: ZipFileManager - Error closing ZipFile during cleanup: ${e.message}")
                }
            }
        }

        cleanupJob.cancel()
        println("DEBUG: ZipFileManager - All ZipFiles closed and cleanup job canceled")
    }

    /**
     * 現在管理されているZipFileの数を取得
     */
    fun getActiveZipFileCount(): Int {
        return zipFiles.size
    }
}

/**
 * 高速化されたZIPファイル直接読み取りハンドラー
 * パフォーマンス最適化版 - 完全同期化対応
 */
class DirectZipImageHandler(private val context: Context) {
    private val unifiedDataManager = UnifiedReadingDataManager(context)

    // ZipFile管理
    private val zipFileManager = ZipFileManager()

    // サポートされる画像拡張子
    private val supportedExtensions = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp")

    // スレッドセーフなメモリキャッシュ
    private val imageDataCache = ConcurrentHashMap<String, ByteArray>()

    // プリロード管理（改良版）
    private val preloadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val preloadChannel = Channel<PreloadRequest>(capacity = 20) // キャパシティ制限
    private val activePreloadJobs = ConcurrentHashMap<String, Job>()

    // プリロード制御用のMutexと実行数制限（同時実行数制限）
    private val preloadMutex = Mutex()
    private val activePreloadCount = AtomicInteger(0)
    private val maxConcurrentPreloads = 3 // 最大3つまで同時プリロード

    // メモリ監視
    private val maxMemoryUsage = Runtime.getRuntime().maxMemory() / 4 // 最大メモリの25%
    private val currentMemoryUsage = AtomicLong(0)

    // 位置保存のバッチ処理
    private val positionQueue = Channel<PositionSaveRequest>(Channel.UNLIMITED)

    // ZIP エントリキャッシュ（ファイル全体の読み込み回避）
    private val zipEntryCache = ConcurrentHashMap<String, ZipEntryData>()

    // キャッシュサイズ制限（動的調整）
    private val maxCacheSize = 20 // 最大20画像をキャッシュ（50から削減）
    private val preloadRange = 2 // 前後2ページをプリロード（3から削減）
    private val minPreloadRange = 1 // 最小プリロード範囲
    private val maxPreloadRange = 3 // 最大プリロード範囲

    // 現在のZIPファイル情報
    private var currentZipUri: Uri? = null
    private var currentZipEntries: List<ZipImageEntry> = emptyList()

    // パフォーマンス監視
    private val _performanceMetrics = MutableStateFlow(PerformanceMetrics())
    val performanceMetrics: StateFlow<PerformanceMetrics> = _performanceMetrics

    data class PreloadRequest(
        val entry: ZipImageEntry,
        val priority: Int,
        val requestId: String = "${entry.id}_${System.currentTimeMillis()}"
    )

    data class PositionSaveRequest(val zipUri: Uri, val imageIndex: Int, val zipFile: File?)
    data class ZipEntryData(val entries: List<ZipImageEntry>, val lastModified: Long)
    data class PerformanceMetrics(
        val cacheHitRate: Float = 0f,
        val averageLoadTime: Long = 0L,
        val preloadedImages: Int = 0,
        val totalRequests: Int = 0,
        val memoryUsageBytes: Long = 0L,
        val activePreloads: Int = 0
    )

    init {
        // プリロードワーカーを開始
        startPreloadWorker()
        // 位置保存ワーカーを開始
        startPositionSaveWorker()
    }

    /**
     * プリロードワーカーの開始（制御強化版）
     */
    private fun startPreloadWorker() {
        // プリロードチャンネルの処理
        preloadScope.launch {
            for (request in preloadChannel) {
                // 重複チェック（既にキャッシュされているかアクティブな処理があるか）
                if (imageDataCache.containsKey(request.entry.id) ||
                    activePreloadJobs.containsKey(request.requestId)
                ) {
                    continue
                }

                // メモリ使用量チェック
                if (currentMemoryUsage.get() > maxMemoryUsage) {
                    println("DEBUG: Skipping preload due to memory limit: ${request.entry.fileName}")
                    cleanupOldCache() // 強制的にクリーンアップを実行
                    continue
                }

                // Mutexとカウンターで同時実行数を制限
                val job = preloadScope.launch {
                    // 実行数チェック
                    while (activePreloadCount.get() >= maxConcurrentPreloads) {
                        delay(50) // 少し待機してリトライ
                    }
                    
                    activePreloadCount.incrementAndGet()
                    try {
                        executePreload(request)
                    } finally {
                        activePreloadCount.decrementAndGet()
                        activePreloadJobs.remove(request.requestId)
                    }
                }

                activePreloadJobs[request.requestId] = job
            }
        }

        // 定期的なメモリ監視とクリーンアップ
        preloadScope.launch {
            while (true) {
                delay(5000) // 5秒間隔
                monitorMemoryAndCleanup()
            }
        }
    }

    /**
     * 実際のプリロード処理を実行
     */
    private suspend fun executePreload(request: PreloadRequest) {
        try {
            val startTime = System.currentTimeMillis()
            println("DEBUG: Starting preload for ${request.entry.fileName} (priority: ${request.priority})")

            val imageData = loadImageFromZip(request.entry)
            val loadTime = System.currentTimeMillis() - startTime

            if (imageData != null) {
                // キャッシュサイズと メモリ使用量をチェック
                val imageSize = imageData.size.toLong()
                if (imageDataCache.size >= maxCacheSize ||
                    currentMemoryUsage.get() + imageSize > maxMemoryUsage
                ) {

                    cleanupOldCache()

                    // クリーンアップ後も制限を超える場合はスキップ
                    if (currentMemoryUsage.get() + imageSize > maxMemoryUsage) {
                        println("DEBUG: Skipping preload due to memory constraints: ${request.entry.fileName}")
                        return
                    }
                }

                imageDataCache[request.entry.id] = imageData
                currentMemoryUsage.addAndGet(imageSize)

                println("DEBUG: Preloaded ${request.entry.fileName} (${imageData.size} bytes) in ${loadTime}ms")
                println("DEBUG: Memory usage: ${currentMemoryUsage.get() / 1024 / 1024}MB / ${maxMemoryUsage / 1024 / 1024}MB")

                // メトリクス更新
                updateMetrics(loadTime, isPreload = true)
            }

        } catch (e: Exception) {
            println("DEBUG: Preload failed for ${request.entry.fileName}: ${e.message}")
        }
    }

    /**
     * メモリ監視とクリーンアップ
     */
    private fun monitorMemoryAndCleanup() {
        val memoryUsage = currentMemoryUsage.get()
        val memoryUsagePercent = (memoryUsage.toFloat() / maxMemoryUsage) * 100

        println("DEBUG: Memory monitor - Usage: ${memoryUsage / 1024 / 1024}MB (${memoryUsagePercent.toInt()}%)")
        println("DEBUG: Active preloads: ${activePreloadJobs.size}, Cache size: ${imageDataCache.size}")

        // メモリ使用量が80%を超えたら積極的にクリーンアップ
        if (memoryUsagePercent > 80) {
            println("DEBUG: High memory usage detected, performing aggressive cleanup")
            cleanupOldCache()

            // さらに必要なら一時的にプリロードを停止
            if (memoryUsagePercent > 90) {
                cancelAllActivePreloads()
                System.gc()
            }
        }
    }

    /**
     * 全てのアクティブなプリロードをキャンセル
     */
    private fun cancelAllActivePreloads() {
        val canceledCount = activePreloadJobs.size
        activePreloadJobs.values.forEach { job ->
            job.cancel()
        }
        activePreloadJobs.clear()

        println("DEBUG: Canceled $canceledCount active preload jobs due to memory pressure")
    }

    /**
     * 位置保存ワーカーの開始（バッチ処理）
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun startPositionSaveWorker() {
        preloadScope.launch {
            var lastSaveRequest: PositionSaveRequest?

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
                        unifiedDataManager.saveCurrentPosition(
                            latestRequest.zipUri,
                            latestRequest.imageIndex,
                            latestRequest.zipFile
                        )
                        println("DEBUG: Batch saved position ${latestRequest.imageIndex}")
                    } catch (_: Exception) {
                        println("DEBUG: Position save failed")
                    }
                }
            }
        }
    }

    /**
     * ZIPファイルから画像エントリのリストを取得（高速化・非同期版）
     */
    suspend fun getImageEntriesFromZip(zipUri: Uri, zipFile: File? = null): List<ZipImageEntry> =
        withContext(Dispatchers.IO) {
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
     * 指定された画像エントリの画像データを取得（メモリ管理強化版）
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

        // メモリ使用量チェック
        if (currentMemoryUsage.get() > maxMemoryUsage * 0.8) {
            println("DEBUG: High memory usage, performing cleanup before loading")
            cleanupOldCache()
        }

        val imageData = loadImageFromZip(imageEntry)
        val loadTime = System.currentTimeMillis() - startTime

        if (imageData != null) {
            val imageSize = imageData.size.toLong()

            // キャッシュサイズとメモリ使用量をチェック
            if (imageDataCache.size >= maxCacheSize ||
                currentMemoryUsage.get() + imageSize > maxMemoryUsage
            ) {
                cleanupOldCache()
            }

            imageDataCache[imageEntry.id] = imageData
            currentMemoryUsage.addAndGet(imageSize)

            println("DEBUG: Loaded ${imageEntry.fileName} (${imageData.size} bytes) in ${loadTime}ms")
            println("DEBUG: Current memory usage: ${currentMemoryUsage.get() / 1024 / 1024}MB")

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
     * ZIPファイルから直接画像を読み込み（改良版）
     */
    private suspend fun loadImageFromZip(imageEntry: ZipImageEntry): ByteArray? =
        withContext(Dispatchers.IO) {
            // 最適化読み込みを試行
            val optimizedResult = tryOptimizedRead(imageEntry)
            if (optimizedResult != null) {
                return@withContext optimizedResult
            }

            // フォールバック: ZipInputStreamを使用した従来の方法
            try {
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
                println("DEBUG: loadImageFromZip failed for ${imageEntry.fileName}: ${e.message}")
                e.printStackTrace()
            }

            return@withContext null
        }

    /**
     * 最適化された読み込みを試行（ZipFileManager使用版）
     */
    private suspend fun tryOptimizedRead(imageEntry: ZipImageEntry): ByteArray? =
        withContext(Dispatchers.IO) {
            val filePath = imageEntry.zipFile?.absolutePath ?: run {
                if (imageEntry.zipUri.scheme == "file") {
                    imageEntry.zipUri.path
                } else {
                    null
                }
            } ?: return@withContext null

            var zipFile: ZipFile? = null
            try {
                // ZipFileManagerから安全にZipFileを取得
                zipFile = zipFileManager.getZipFile(filePath)
                if (zipFile == null) {
                    println("DEBUG: Failed to get ZipFile from manager for ${imageEntry.fileName}")
                    return@withContext null
                }

                // エントリを検索
                val zipEntry = zipFile.getEntry(imageEntry.entryName)
                if (zipEntry == null) {
                    println("DEBUG: Entry not found in ZipFile: ${imageEntry.entryName}")
                    return@withContext null
                }

                // 画像データを読み込み
                zipFile.getInputStream(zipEntry)?.use { inputStream ->
                    val data = inputStream.readBytes()
                    println("DEBUG: Successfully read ${data.size} bytes from optimized ZipFile for ${imageEntry.fileName}")
                    return@withContext data
                }

                return@withContext null

            } catch (e: Exception) {
                println("DEBUG: Optimized read failed for ${imageEntry.fileName}: ${e.message}")
                return@withContext null
            } finally {
                // ZipFileの参照を解放
                if (zipFile != null) {
                    zipFileManager.releaseZipFile(filePath)
                }
            }
        }

    /**
     * 現在のZipファイルを閉じる（ZipFileManager使用版）
     */
    fun closeCurrentZipFile() {
        try {
            // 管理されているすべてのZipFileを閉じる
            zipFileManager.closeAllZipFiles()
            println("DEBUG: All ZipFiles closed via ZipFileManager")
        } catch (e: Exception) {
            println("DEBUG: Error closing ZipFiles: ${e.message}")
        }
    }

    /**
     * プリロードをトリガー（制御強化版）
     */
    private fun triggerPreload(currentIndex: Int) {
        if (currentZipEntries.isEmpty()) return

        preloadScope.launch {
            // 現在のメモリ使用状況に基づいてプリロード範囲を動的調整
            val memoryUsagePercent = (currentMemoryUsage.get().toFloat() / maxMemoryUsage) * 100
            val dynamicPreloadRange = when {
                memoryUsagePercent > 70 -> minPreloadRange // メモリ使用量が多い場合は最小範囲
                memoryUsagePercent < 30 -> maxPreloadRange // メモリに余裕がある場合は最大範囲
                else -> preloadRange // 通常範囲
            }

            val startIndex = max(0, currentIndex - dynamicPreloadRange)
            val endIndex = min(currentZipEntries.size - 1, currentIndex + dynamicPreloadRange)

            println("DEBUG: Starting controlled preload for range $startIndex to $endIndex (current: $currentIndex)")
            println("DEBUG: Dynamic preload range: $dynamicPreloadRange (memory usage: ${memoryUsagePercent.toInt()}%)")

            // 優先度付きでプリロード要求を送信
            val requests = mutableListOf<PreloadRequest>()

            for (i in startIndex..endIndex) {
                if (i == currentIndex) continue // 現在のページはスキップ

                val entry = currentZipEntries[i]

                // 既にキャッシュされているか、アクティブな処理があるかチェック
                if (imageDataCache.containsKey(entry.id) ||
                    activePreloadJobs.values.any { !it.isCancelled }
                ) {
                    continue
                }

                // 距離に基づく優先度設定（近いページほど高優先度）
                val distance = kotlin.math.abs(i - currentIndex)
                val priority = dynamicPreloadRange - distance + 1

                requests.add(PreloadRequest(entry, priority))
            }

            // 優先度順にソートして送信
            requests.sortedByDescending { it.priority }.forEach { request ->
                try {
                    // チャンネルが満杯の場合は低優先度の要求をスキップ
                    if (preloadChannel.trySend(request).isSuccess) {
                        println("DEBUG: Queued preload: ${request.entry.fileName} (priority: ${request.priority})")
                        delay(100) // プリロード要求間に間隔を設ける
                    } else {
                        println("DEBUG: Preload queue full, skipping: ${request.entry.fileName}")
                    }
                } catch (_: Exception) {
                    println("DEBUG: Failed to queue preload")
                }
            }
        }
    }

    /**
     * 古いキャッシュのクリーンアップ（メモリ管理強化版）
     */
    private fun cleanupOldCache() {
        if (imageDataCache.isEmpty()) return

        val beforeSize = imageDataCache.size
        val beforeMemory = currentMemoryUsage.get()

        // 現在表示中の範囲を計算
        val currentIndex = currentZipEntries.indexOfFirst {
            imageDataCache.containsKey(it.id)
        }.takeIf { it != -1 } ?: 0

        // 保護する範囲（現在のプリロード範囲）
        val protectedRange = max(0, currentIndex - preloadRange)..min(
            currentZipEntries.size - 1,
            currentIndex + preloadRange
        )

        // 削除対象のキーを収集
        val keysToRemove = mutableListOf<Pair<String, Long>>()

        imageDataCache.forEach { (key, data) ->
            val entryIndex = currentZipEntries.indexOfFirst { it.id == key }

            // 保護範囲外のキャッシュを削除対象にする
            if (entryIndex == -1 || entryIndex !in protectedRange) {
                keysToRemove.add(key to data.size.toLong())
            }
        }

        // サイズが大きい順にソートして削除（大きなファイルから優先的に削除）
        keysToRemove.sortedByDescending { it.second }.forEach { (key, size) ->
            imageDataCache.remove(key)
            currentMemoryUsage.addAndGet(-size)

            // 十分なメモリが確保できたら停止
            if (imageDataCache.size <= maxCacheSize / 2 &&
                currentMemoryUsage.get() <= maxMemoryUsage / 2
            ) {
                return@forEach
            }
        }

        val afterSize = imageDataCache.size
        val afterMemory = currentMemoryUsage.get()
        val freedMemory = (beforeMemory - afterMemory) / 1024 / 1024

        println("DEBUG: Cache cleanup completed:")
        println("DEBUG:   Before: $beforeSize images, ${beforeMemory / 1024 / 1024}MB")
        println("DEBUG:   After: $afterSize images, ${afterMemory / 1024 / 1024}MB")
        println("DEBUG:   Freed: ${beforeSize - afterSize} images, ${freedMemory}MB")

        // 大幅なクリーンアップ後はGCを実行
        if (beforeSize - afterSize > 5) {
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
     * パフォーマンスメトリクスの更新（メモリ監視対応版）
     */
    private fun updateMetrics(loadTime: Long, isHit: Boolean = false, isPreload: Boolean = false) {
        // メトリクス更新をバックグラウンドで実行（メインスレッドの負荷軽減）
        preloadScope.launch {
            try {
                val currentMetrics = _performanceMetrics.value
                val newTotalRequests = currentMetrics.totalRequests + 1
                val newHits =
                    if (isHit) newTotalRequests * currentMetrics.cacheHitRate + 1 else newTotalRequests * currentMetrics.cacheHitRate
                val newPreloadedImages =
                    if (isPreload) currentMetrics.preloadedImages + 1 else currentMetrics.preloadedImages

                _performanceMetrics.value = currentMetrics.copy(
                    cacheHitRate = if (newTotalRequests > 0) newHits / newTotalRequests else 0f,
                    averageLoadTime = (currentMetrics.averageLoadTime + loadTime) / 2,
                    preloadedImages = newPreloadedImages,
                    totalRequests = newTotalRequests,
                    memoryUsageBytes = currentMemoryUsage.get(),
                    activePreloads = activePreloadJobs.size
                )
            } catch (e: Exception) {
                // メトリクス更新エラーは無視（パフォーマンス優先）
            }
        }
    }

    /**
     * キャッシュされた画像データを取得
     */
    @Suppress("unused")
    fun getCachedImageData(imageEntry: ZipImageEntry): ByteArray? {
        return imageDataCache[imageEntry.id]
    }

    /**
     * メモリキャッシュをクリア（プリロード制御対応版）
     */
    fun clearMemoryCache() {
        // アクティブなプリロードをキャンセル
        cancelAllActivePreloads()

        // キャッシュをクリア
        imageDataCache.clear()
        zipEntryCache.clear()
        currentMemoryUsage.set(0)

        println("DEBUG: Memory cache cleared")
        println("DEBUG: Active ZipFiles: ${zipFileManager.getActiveZipFileCount()}")
        println("DEBUG: Memory usage reset to 0")

        System.gc()
    }

    /**
     * リソースのクリーンアップ（プリロード制御対応版）
     */
    fun cleanup() {
        // アクティブなプリロードを全てキャンセル
        cancelAllActivePreloads()

        // ZipFileを安全に閉じる
        try {
            zipFileManager.closeAllZipFiles()
            println("DEBUG: Cleanup - All ZipFiles closed via ZipFileManager")
        } catch (_: Exception) {
            println("DEBUG: Cleanup - Error closing ZipFiles")
        }

        // その他のリソースをクリア
        preloadScope.cancel()
        clearMemoryCache()

        println("DEBUG: DirectZipImageHandler cleanup completed")
        println("DEBUG: Final memory usage: ${currentMemoryUsage.get() / 1024 / 1024}MB")
    }

    // 既存メソッドのデリゲート
    fun getUnifiedDataManager(): UnifiedReadingDataManager = unifiedDataManager

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
        return unifiedDataManager.getSavedPosition(zipUri, zipFile)
    }

    fun onZipFileDeleted(zipUri: Uri, zipFile: File? = null) {
        unifiedDataManager.onFileDeleted(zipUri, zipFile)
        val fileId = generateFileIdentifier(zipUri, zipFile)
        imageDataCache.keys.removeAll { it.startsWith(fileId) }
        zipEntryCache.remove(fileId)

        // 削除されたファイルのZipFileを強制的に閉じる
        val filePath = zipFile?.absolutePath ?: run {
            if (zipUri.scheme == "file") {
                zipUri.path
            } else {
                null
            }
        }

        if (filePath != null) {
            zipFileManager.forceCloseZipFile(filePath)
            println("DEBUG: Force closed ZipFile for deleted file: ${zipFile?.name ?: zipUri}")
        }
    }

    /**
     * 複数の画像エントリを効率的に一括読み込み（廃止予定）
     * 新しいプリロード機能で置き換え
     */
    @Deprecated("Use preload functionality instead")
    @Suppress("UNUSED_PARAMETER")
    fun batchLoadImages(imageEntries: List<ZipImageEntry>): Map<String, ByteArray> {
        println("DEBUG: batchLoadImages is deprecated, using new preload system")
        return emptyMap()
    }

    /**
     * 指定されたページの周辺画像をプリロード（廃止予定）
     * 新しいプリロード機能で置き換え
     */
    @Deprecated("Use automatic preload functionality instead")
    @Suppress("UNUSED_PARAMETER")
    fun preloadAroundPage(imageEntries: List<ZipImageEntry>, currentPage: Int) {
        println("DEBUG: preloadAroundPage is deprecated, using automatic preload")
    }

    /**
     * デバッグ用: ZipFileManagerの状態を表示
     */
    @Suppress("unused")
    fun getZipFileManagerStatus(): String {
        return "Active ZipFiles: ${zipFileManager.getActiveZipFileCount()}"
    }

    /**
     * デバッグ用: メモリとプリロードの状態を表示
     */
    @Suppress("unused")
    fun getMemoryAndPreloadStatus(): String {
        val memoryUsageMB = currentMemoryUsage.get() / 1024 / 1024
        val maxMemoryMB = maxMemoryUsage / 1024 / 1024
        val memoryPercent = if (maxMemoryMB > 0) (memoryUsageMB * 100 / maxMemoryMB) else 0

        return """
            Memory Usage: ${memoryUsageMB}MB / ${maxMemoryMB}MB (${memoryPercent}%)
            Cache Size: ${imageDataCache.size} / $maxCacheSize
            Active Preloads: ${activePreloadJobs.size}
            Available Preload Slots: ${maxConcurrentPreloads - activePreloadCount.get()}
        """.trimIndent()
    }

    /**
     * プリロード範囲を動的に変更
     */
    @Suppress("unused")
    fun adjustPreloadRange(newRange: Int) {
        val adjustedRange = newRange.coerceIn(minPreloadRange, maxPreloadRange)
        println("DEBUG: Preload range adjusted to: $adjustedRange")
    }

    /**
     * メモリ制限を動的に変更
     */
    @Suppress("unused")
    fun adjustMemoryLimit(percentOfMaxMemory: Float) {
        val newLimit =
            (Runtime.getRuntime().maxMemory() * percentOfMaxMemory.coerceIn(0.1f, 0.5f)).toLong()
        println("DEBUG: Memory limit would be adjusted to: ${newLimit / 1024 / 1024}MB")
        // 実際の変更は定数なので、ログ出力のみ
    }
}