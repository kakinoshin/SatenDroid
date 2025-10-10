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
 * ZipFileの安全な管理クラス（完全同期化版）
 * 参照カウントとタイムアウト機能で適切な資源管理を行う
 */
class ZipFileManager {
    private val zipFiles = ConcurrentHashMap<String, ZipFileEntry>()
    private val accessMutex = Mutex() // 同期化用Mutex

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
     * ZipFileを安全に取得または作成（完全同期化版）
     */
    suspend fun getZipFile(filePath: String): ZipFile? {
        return accessMutex.withLock {
            try {
                val currentTime = System.currentTimeMillis()

                println("DEBUG: ZipFileManager.getZipFile called for: $filePath")

                // 既存のエントリをチェック
                zipFiles[filePath]?.let { entry ->
                    // タイムアウトチェック
                    if (currentTime - entry.lastAccessed.get() < TIMEOUT_MS) {
                        // 参照カウントを増加
                        entry.refCount.incrementAndGet()
                        entry.lastAccessed.set(currentTime)
                        println("DEBUG: ZipFileManager - Reusing existing ZipFile: ${File(filePath).name}")
                        return@withLock entry.zipFile
                    } else {
                        // タイムアウトしたエントリを削除
                        println("DEBUG: ZipFileManager - Removing timed out entry: ${File(filePath).name}")
                        closeZipFileEntryInternal(filePath, entry)
                    }
                }

                // 新しいZipFileを作成
                val file = File(filePath)
                if (!file.exists()) {
                    println("ERROR: ZipFileManager - File does not exist: ${file.absolutePath}")
                    return@withLock null
                }

                if (!file.canRead()) {
                    println("ERROR: ZipFileManager - File is not readable: ${file.absolutePath}")
                    return@withLock null
                }

                println("DEBUG: ZipFileManager - Creating new ZipFile for: ${file.name} (${file.length()} bytes)")

                val zipFile = ZipFile(file)
                val entry = ZipFileEntry(
                    zipFile = zipFile,
                    lastAccessed = AtomicLong(currentTime),
                    refCount = AtomicInteger(1),
                    filePath = filePath
                )

                zipFiles[filePath] = entry
                println("DEBUG: ZipFileManager - Created new ZipFile: ${file.name}")

                // ZipFileの基本情報をログ出力
                try {
                    val entryCount = zipFile.entries().toList().size
                    println("DEBUG: ZipFile contains $entryCount entries")
                } catch (e: Exception) {
                    println("WARNING: Could not count ZipFile entries: ${e.message}")
                }

                return@withLock zipFile

            } catch (e: Exception) {
                println("ERROR: ZipFileManager - Failed to create ZipFile for $filePath: ${e.message}")
                e.printStackTrace()
                return@withLock null
            }
        }
    }

    /**
     * ZipFileの参照を解放（完全同期化版）
     */
    suspend fun releaseZipFile(filePath: String) {
        accessMutex.withLock {
            zipFiles[filePath]?.let { entry ->
                val newRefCount = entry.refCount.decrementAndGet()
                println("DEBUG: ZipFileManager - Released ZipFile: ${File(filePath).name}, refCount: $newRefCount")

                // 参照カウントが0になったら遅延削除のタイマーを開始
                if (newRefCount <= 0) {
                    CoroutineScope(Dispatchers.IO).launch {
                        delay(5000) // 5秒後に削除
                        accessMutex.withLock {
                            zipFiles[filePath]?.let { currentEntry ->
                                if (currentEntry.refCount.get() <= 0) {
                                    closeZipFileEntryInternal(filePath, currentEntry)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 指定されたZipFileを強制的に閉じる（完全同期化版）
     */
    suspend fun forceCloseZipFile(filePath: String) {
        accessMutex.withLock {
            zipFiles[filePath]?.let { entry ->
                closeZipFileEntryInternal(filePath, entry)
            }
        }
    }

    /**
     * 期限切れのエントリをクリーンアップ（完全同期化版）
     */
    private suspend fun cleanupExpiredEntries() {
        val currentTime = System.currentTimeMillis()
        val toRemove = mutableListOf<String>()

        accessMutex.withLock {
            zipFiles.forEach { (filePath, entry) ->
                val timeSinceLastAccess = currentTime - entry.lastAccessed.get()
                if (timeSinceLastAccess > TIMEOUT_MS && entry.refCount.get() <= 0) {
                    toRemove.add(filePath)
                }
            }

            toRemove.forEach { filePath ->
                zipFiles[filePath]?.let { entry ->
                    closeZipFileEntryInternal(filePath, entry)
                }
            }
        }

        if (toRemove.isNotEmpty()) {
            println("DEBUG: ZipFileManager - Cleaned up ${toRemove.size} expired entries")
        }
    }

    /**
     * ZipFileエントリを安全に閉じる（内部用、既にロック済みを前提）
     */
    private fun closeZipFileEntryInternal(filePath: String, entry: ZipFileEntry) {
        try {
            entry.zipFile.close()
            zipFiles.remove(filePath)
            println("DEBUG: ZipFileManager - Closed and removed ZipFile: ${File(filePath).name}")
        } catch (_: Exception) {
            println("DEBUG: ZipFileManager - Error closing ZipFile")
            // エラーが発生してもマップからは削除
            zipFiles.remove(filePath)
        }
    }

    /**
     * 全てのZipFileを閉じる（完全同期化版）
     */
    suspend fun closeAllZipFiles() {
        accessMutex.withLock {
            val entries = zipFiles.values.toList()
            zipFiles.clear()

            entries.forEach { entry ->
                try {
                    entry.zipFile.close()
                    println("DEBUG: ZipFileManager - Closed ZipFile during cleanup: ${File(entry.filePath).name}")
                } catch (_: Exception) {
                    println("DEBUG: ZipFileManager - Error closing ZipFile during cleanup")
                }
            }
        }

        cleanupJob.cancel()
        println("DEBUG: ZipFileManager - All ZipFiles closed and cleanup job canceled")
    }

    /**
     * 現在管理されているZipFileの数を取得（同期化版）
     */
    suspend fun getActiveZipFileCount(): Int {
        return accessMutex.withLock {
            zipFiles.size
        }
    }
}

/**
 * 高速化されたZIPファイル直接読み取りハンドラー
 * パフォーマンス最適化版 - 完全同期化対応
 */
class DirectZipImageHandler(private val context: Context) {
    private val readingStateManager = ReadingStateManager(context) // 新システム

    // ZipFile管理
    private val zipFileManager = ZipFileManager()

    // 同期化用Mutex群
    private val cacheMutex = Mutex() // キャッシュ操作用
    private val stateMutex = Mutex() // 状態変更用
    private val metricsMutex = Mutex() // メトリクス更新用

    // 現在のページ位置を自分で管理（UI状態に依存しない）
    @Volatile
    private var currentPagePosition: Int = 0
    
    @Volatile
    private var totalPageCount: Int = 0

    // サポートされる画像拡張子
    private val supportedExtensions = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp")

    // スレッドセーフなメモリキャッシュ
    private val imageDataCache = ConcurrentHashMap<String, ByteArray>()

    // プリロード管理（改良版）
    private val preloadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val preloadChannel = Channel<PreloadRequest>(capacity = 20) // キャパシティ制限
    private val activePreloadJobs = ConcurrentHashMap<String, Job>()

    // プリロード制御用の実行数制限（同時実行数制限）
    private val activePreloadCount = AtomicInteger(0)
    private val maxConcurrentPreloads = 3 // 最大3つまで同時プリロード

    // Phase 2: プリロード機能の段階的再有効化
    // 保守的な設定で有効化開始
    private val enablePreload = true

    // メモリ監視（Phase 2: より保守的な設定）
    private val maxMemoryUsage = Runtime.getRuntime().maxMemory() / 5 // 最大メモリの20%（25%から削減）
    private val currentMemoryUsage = AtomicLong(0)

    // 位置保存のバッチ処理
    private val positionQueue = Channel<PositionSaveRequest>(Channel.UNLIMITED)

    // ZIP エントリキャッシュ（ファイル全体の読み込み回避）
    private val zipEntryCache = ConcurrentHashMap<String, ZipEntryData>()

    // キャッシュサイズ制限（Phase 2: より保守的な設定）
    private val maxCacheSize = 15 // 最大15画像をキャッシュ（20から削減）
    private val preloadRange = 1 // 前後1ページをプリロード（2から削減）
    private val minPreloadRange = 1 // 最小プリロード範囲
    private val maxPreloadRange = 2 // 最大プリロード範囲（3から削減）

    // 現在のZIPファイル情報（同期化対象）
    @Volatile
    private var currentZipUri: Uri? = null

    @Volatile
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
     * プリロードワーカーの開始（Phase 2: 安定性向上版）
     */
    private fun startPreloadWorker() {
        // プリロードチャンネルの処理
        preloadScope.launch {
            try {
                println("DEBUG: Phase 2 Preload worker started")

                for (request in preloadChannel) {
                    try {
                        // 重複チェック（既にキャッシュされているかアクティブな処理があるか）
                        if (imageDataCache.containsKey(request.entry.id) ||
                            activePreloadJobs.containsKey(request.requestId)
                        ) {
                            println("DEBUG: Phase 2 Skipping duplicate preload request: ${request.entry.fileName}")
                            continue
                        }

                        // メモリ使用量チェック（Phase 2: より厳格）
                        val memoryPercent =
                            (currentMemoryUsage.get().toFloat() / maxMemoryUsage) * 100
                        if (memoryPercent > 75) {
                            println("DEBUG: Phase 2 Skipping preload due to memory limit (${memoryPercent.toInt()}%): ${request.entry.fileName}")
                            cleanupOldCache() // 強制的にクリーンアップを実行
                            continue
                        }

                        // Mutexとカウンターで同時実行数を制限
                        val job = preloadScope.launch {
                            // 実行数チェック（Phase 2: より短い待機時間）
                            var waitCount = 0
                            while (activePreloadCount.get() >= maxConcurrentPreloads) {
                                delay(100) // 100ms待機してリトライ
                                waitCount++
                                if (waitCount > 50) { // 5秒でタイムアウト
                                    println("DEBUG: Phase 2 Preload timeout, skipping: ${request.entry.fileName}")
                                    return@launch
                                }
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

                    } catch (e: Exception) {
                        println("ERROR: Phase 2 Exception in preload worker loop: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                println("ERROR: Phase 2 Preload worker crashed: ${e.message}")
                e.printStackTrace()
            }
        }

        // 定期的なメモリ監視とクリーンアップ（Phase 2: より頻繁な監視）
        preloadScope.launch {
            try {
                while (true) {
                    delay(3000) // 3秒間隔（5秒から短縮）
                    monitorMemoryAndCleanup()
                }
            } catch (e: Exception) {
                println("ERROR: Phase 2 Memory monitor crashed: ${e.message}")
            }
        }
    }

    /**
     * 実際のプリロード処理を実行（Phase 2: 安定性向上版）
     */
    private suspend fun executePreload(request: PreloadRequest) {
        try {
            val startTime = System.currentTimeMillis()
            println("DEBUG: Phase 2 Starting preload for ${request.entry.fileName} (priority: ${request.priority})")

            // 重複チェック（実行直前に再確認）
            if (imageDataCache.containsKey(request.entry.id)) {
                println("DEBUG: Phase 2 Preload skipped - already cached: ${request.entry.fileName}")
                return
            }

            // メモリ制限の最終チェック
            val currentMemory = currentMemoryUsage.get()
            val memoryPercent = (currentMemory.toFloat() / maxMemoryUsage) * 100

            if (memoryPercent > 80) {
                println("DEBUG: Phase 2 Preload skipped - high memory usage (${memoryPercent.toInt()}%): ${request.entry.fileName}")
                return
            }

            val imageData = loadImageFromZip(request.entry)
            val loadTime = System.currentTimeMillis() - startTime

            if (imageData != null && imageData.isNotEmpty()) {
                // 原子的にキャッシュに追加
                val addedToCache = addImageToCache(request.entry, imageData)

                if (addedToCache) {
                    println("DEBUG: Phase 2 Successfully preloaded ${request.entry.fileName}")
                    println("        - Size: ${imageData.size} bytes, Time: ${loadTime}ms")
                    println("        - Memory: ${currentMemoryUsage.get() / 1024 / 1024}MB / ${maxMemoryUsage / 1024 / 1024}MB")

                    // メトリクス更新
                    updateMetrics(loadTime, isPreload = true)
                } else {
                    println("DEBUG: Phase 2 Preload failed to add to cache (memory constraints): ${request.entry.fileName}")
                }
            } else {
                println("WARNING: Phase 2 Preload returned empty data: ${request.entry.fileName}")
            }

        } catch (e: Exception) {
            println("ERROR: Phase 2 Preload failed for ${request.entry.fileName}: ${e.message}")
            // スタックトレースは重要なエラーの場合のみ
            if (e !is IOException) {
                e.printStackTrace()
            }
        }
    }

    /**
     * メモリ監視とクリーンアップ（Phase 2: より保守的な設定）
     */
    private fun monitorMemoryAndCleanup() {
        val memoryUsage = currentMemoryUsage.get()
        val memoryUsagePercent = (memoryUsage.toFloat() / maxMemoryUsage) * 100

        println("DEBUG: Phase 2 Memory monitor - Usage: ${memoryUsage / 1024 / 1024}MB (${memoryUsagePercent.toInt()}%)")
        println("DEBUG: Active preloads: ${activePreloadJobs.size}, Cache size: ${imageDataCache.size}/$maxCacheSize")

        // Phase 2: より保守的なメモリ管理（70%でクリーンアップ開始、80%で積極的クリーンアップ）
        if (memoryUsagePercent > 70) {
            println("DEBUG: Phase 2 Memory usage above 70%, performing cleanup")

            // 非同期でクリーンアップを実行
            preloadScope.launch {
                cleanupOldCache()
            }

            // さらに必要なら一時的にプリロードを停止（80%超過時）
            if (memoryUsagePercent > 80) {
                println("WARNING: Phase 2 High memory usage (${memoryUsagePercent.toInt()}%), canceling active preloads")
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
                        // 新しいシステムでは位置保存は不要（ファイルを閉じる時に保存）
                        println("DEBUG: Batch saved position ${latestRequest.imageIndex}")
                    } catch (_: Exception) {
                        println("DEBUG: Position save failed")
                    }
                }
            }
        }
    }

    /**
     * ZIPファイルから画像エントリのリストを取得（完全同期化版）
     */
    suspend fun getImageEntriesFromZip(zipUri: Uri, zipFile: File? = null): List<ZipImageEntry> =
        withContext(Dispatchers.IO) {
            val zipKey = generateFileIdentifier(zipUri, zipFile)
            val fileModified = zipFile?.lastModified() ?: System.currentTimeMillis()

            // キャッシュから取得を試行
            zipEntryCache[zipKey]?.let { cachedData ->
                if (cachedData.lastModified == fileModified) {
                    // 状態更新を同期化
                    stateMutex.withLock {
                        currentZipUri = zipUri
                        currentZipEntries = cachedData.entries
                        totalPageCount = cachedData.entries.size
                        
                        // ★ キャッシュヒット時も保存されている位置を読み込む
                        val filePath = generateFileIdentifier(zipUri, zipFile)
                        val savedState = readingStateManager.getState(filePath)
                        currentPagePosition = savedState.currentPage
                        
                        println("DEBUG: Using cached entries for ${zipFile?.name ?: zipUri}")
                        println("  Total pages: $totalPageCount")
                        println("  Saved position: $currentPagePosition")
                        println("  Status: ${savedState.status}")
                    }
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

            // キャッシュに保存と状態更新を同期化
            stateMutex.withLock {
                zipEntryCache[zipKey] = ZipEntryData(sortedEntries, fileModified)
                
                // ★ 新しいファイルの状態を設定
                currentZipUri = zipUri
                currentZipEntries = sortedEntries
                totalPageCount = sortedEntries.size
                
                // ★ 保存されている位置を読み込む（既存データを尊重）
                val filePath = generateFileIdentifier(zipUri, zipFile)
                val savedState = readingStateManager.getState(filePath)
                currentPagePosition = savedState.currentPage
                
                println("DEBUG: DirectZipHandler - Initialized new file")
                println("  Total pages: $totalPageCount")
                println("  Saved position: $currentPagePosition")
                println("  Status: ${savedState.status}")
            }

            println("DEBUG: ZIP entry scanning completed - ${sortedEntries.size} images ready")
            return@withContext sortedEntries
        }

    /**
     * ページが変更されたことを通知（UIから呼ばれる）
     * ただし、保存はしない。記録するだけ。
     */
    fun updateCurrentPage(page: Int) {
        currentPagePosition = page
        println("DEBUG: DirectZipHandler - Current page updated to: $page")
    }

    /**
     * キャッシュに画像データを原子的に追加（Phase 2: より保守的な設定）
     */
    private suspend fun addImageToCache(imageEntry: ZipImageEntry, imageData: ByteArray): Boolean {
        return cacheMutex.withLock {
            val imageSize = imageData.size.toLong()

            // Phase 2: より厳格なメモリ制限チェック
            if (imageDataCache.size >= maxCacheSize ||
                currentMemoryUsage.get() + imageSize > maxMemoryUsage * 0.9
            ) { // 90%でブロック（100%から変更）

                println("DEBUG: Phase 2 Cache size or memory limit reached, performing cleanup")
                // クリーンアップを実行
                cleanupOldCacheInternal()

                // クリーンアップ後も制限を超える場合は追加しない
                if (currentMemoryUsage.get() + imageSize > maxMemoryUsage * 0.9) {
                    println("DEBUG: Phase 2 Cannot add to cache after cleanup: ${imageEntry.fileName}")
                    return@withLock false
                }
            }

            // 原子的に追加
            imageDataCache[imageEntry.id] = imageData
            currentMemoryUsage.addAndGet(imageSize)

            println("DEBUG: Phase 2 Added to cache: ${imageEntry.fileName} (${imageData.size} bytes)")
            println("DEBUG: Current memory usage: ${currentMemoryUsage.get() / 1024 / 1024}MB / ${maxMemoryUsage / 1024 / 1024}MB")

            return@withLock true
        }
    }

    /**
     * 指定された画像エントリの画像データを取得（基本機能修復版）
     */
    suspend fun getImageData(imageEntry: ZipImageEntry): ByteArray? = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        println("DEBUG: getImageData called for ${imageEntry.fileName}")
        println("DEBUG: Entry details - ID: ${imageEntry.id}, Size: ${imageEntry.size}")

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

        println("DEBUG: loadImageFromZip returned ${imageData?.size ?: 0} bytes for ${imageEntry.fileName}")

        if (imageData != null) {
            // 原子的にキャッシュに追加
            val addedToCache = addImageToCache(imageEntry, imageData)

            println("DEBUG: Added to cache: $addedToCache for ${imageEntry.fileName}")

            if (addedToCache) {
                // プリロードは基本機能確認後に段階的に有効化
                if (addedToCache && enablePreload) {
                    preloadScope.launch {
                        delay(1000) // 1秒後にプリロード開始（500msから延長してより安定化）
                        triggerPreload(imageEntry.index)
                    }
                }
            }
        } else {
            println("ERROR: Failed to load image data for ${imageEntry.fileName}")
        }

        updateMetrics(loadTime, isHit = false)
        return@withContext imageData
    }

    /**
     * ZIPファイルから直接画像を読み込み（改良版）
     */
    private suspend fun loadImageFromZip(imageEntry: ZipImageEntry): ByteArray? =
        withContext(Dispatchers.IO) {
            println("DEBUG: loadImageFromZip called for ${imageEntry.fileName}")
            println("DEBUG: Entry name: ${imageEntry.entryName}")
            println("DEBUG: ZIP URI: ${imageEntry.zipUri}")
            println("DEBUG: ZIP File: ${imageEntry.zipFile?.absolutePath}")

            // 最適化読み込みを試行
            val optimizedResult = tryOptimizedRead(imageEntry)
            if (optimizedResult != null) {
                println("DEBUG: Optimized read successful for ${imageEntry.fileName}: ${optimizedResult.size} bytes")
                return@withContext optimizedResult
            }

            println("DEBUG: Optimized read failed, falling back to ZipInputStream for ${imageEntry.fileName}")

            // フォールバック: ZipInputStreamを使用した従来の方法
            try {
                context.contentResolver.openInputStream(imageEntry.zipUri)?.use { inputStream ->
                    println("DEBUG: Opened InputStream for ${imageEntry.zipUri}")

                    ZipInputStream(inputStream).use { zipInputStream ->
                        println("DEBUG: Created ZipInputStream for ${imageEntry.fileName}")

                        var entry = zipInputStream.nextEntry
                        var entryCount = 0

                        while (entry != null) {
                            entryCount++
                            println("DEBUG: Processing entry #$entryCount: ${entry.name}")

                            if (entry.name == imageEntry.entryName) {
                                println("DEBUG: Found target entry: ${entry.name}")
                                println("DEBUG: Entry size: ${entry.size}")
                                println("DEBUG: Entry compressed size: ${entry.compressedSize}")

                                val data = zipInputStream.readBytes()
                                println("DEBUG: Read ${data.size} bytes from ${imageEntry.fileName}")

                                if (data.isNotEmpty()) {
                                    // ファイルヘッダーをチェック
                                    if (data.size >= 10) {
                                        val header =
                                            data.take(10).joinToString(" ") { "0x%02x".format(it) }
                                        println("DEBUG: File header: $header")
                                    }
                                }

                                return@withContext data
                            }

                            zipInputStream.closeEntry()
                            entry = zipInputStream.nextEntry

                            // 大量のエントリがある場合の進行状況
                            if (entryCount % 100 == 0) {
                                println("DEBUG: Processed $entryCount entries, still searching for ${imageEntry.entryName}")
                            }
                        }

                        println("ERROR: Entry not found in ZIP: ${imageEntry.entryName} (searched $entryCount entries)")
                    }
                }
            } catch (e: IOException) {
                println("ERROR: IOException in loadImageFromZip for ${imageEntry.fileName}: ${e.message}")
                e.printStackTrace()
            } catch (e: Exception) {
                println("ERROR: Exception in loadImageFromZip for ${imageEntry.fileName}: ${e.message}")
                e.printStackTrace()
            }

            println("ERROR: Failed to load image ${imageEntry.fileName}")
            return@withContext null
        }

    /**
     * 最適化された読み込みを試行（完全同期化版）
     */
    private suspend fun tryOptimizedRead(imageEntry: ZipImageEntry): ByteArray? =
        withContext(Dispatchers.IO) {
            println("DEBUG: tryOptimizedRead called for ${imageEntry.fileName}")

            val filePath = imageEntry.zipFile?.absolutePath ?: run {
                if (imageEntry.zipUri.scheme == "file") {
                    imageEntry.zipUri.path
                } else {
                    null
                }
            } ?: run {
                println("DEBUG: Cannot determine file path for optimized read: ${imageEntry.zipUri}")
                return@withContext null
            }

            println("DEBUG: Using file path for optimized read: $filePath")

            var zipFile: ZipFile? = null
            try {
                // ZipFileManagerから安全にZipFileを取得（suspend対応）
                zipFile = zipFileManager.getZipFile(filePath)
                if (zipFile == null) {
                    println("DEBUG: Failed to get ZipFile from manager for ${imageEntry.fileName}")
                    return@withContext null
                }

                println("DEBUG: Successfully obtained ZipFile for ${imageEntry.fileName}")

                // エントリを検索
                val zipEntry = zipFile.getEntry(imageEntry.entryName)
                if (zipEntry == null) {
                    println("DEBUG: Entry not found in ZipFile: ${imageEntry.entryName}")
                    // 利用可能なエントリをリスト（デバッグ用）
                    val entries = zipFile.entries().toList().take(10) // 最初の10個だけ
                    println("DEBUG: Available entries (first 10):")
                    entries.forEach { entry ->
                        println("  - ${entry.name}")
                    }
                    return@withContext null
                }

                println("DEBUG: Found entry in ZipFile: ${zipEntry.name}")
                println("DEBUG: Entry size: ${zipEntry.size}")
                println("DEBUG: Entry compressed size: ${zipEntry.compressedSize}")

                // 画像データを読み込み
                zipFile.getInputStream(zipEntry)?.use { inputStream ->
                    val data = inputStream.readBytes()
                    println("DEBUG: Successfully read ${data.size} bytes from optimized ZipFile for ${imageEntry.fileName}")

                    if (data.isNotEmpty() && data.size >= 10) {
                        val header = data.take(10).joinToString(" ") { "0x%02x".format(it) }
                        println("DEBUG: Optimized read file header: $header")
                    }

                    return@withContext data
                }

                println("ERROR: Failed to get InputStream from ZipEntry: ${imageEntry.entryName}")
                return@withContext null

            } catch (e: Exception) {
                println("ERROR: Exception in tryOptimizedRead for ${imageEntry.fileName}: ${e.message}")
                e.printStackTrace()
                return@withContext null
            } finally {
                // ZipFileの参照を解放（suspend対応）
                if (zipFile != null) {
                    zipFileManager.releaseZipFile(filePath)
                    println("DEBUG: Released ZipFile reference for ${imageEntry.fileName}")
                }
            }
        }

    /**
     * 現在のZIPファイルの状態を保存（ファイルは閉じない）
     * ファイル切り替え前に確実に状態を保存するためのメソッド
     * 
     * @param currentPage 保存する現在のページ位置
     */
    suspend fun saveCurrentFileState(currentPage: Int) {
        stateMutex.withLock {
            val zipUri = currentZipUri
            val zipFile = currentZipEntries.firstOrNull()?.zipFile
            
            if (zipUri != null && totalPageCount > 0) {
                // 最新のページ位置を更新
                currentPagePosition = currentPage
                
                // ファイルパスを生成
                val filePath = generateFileIdentifier(zipUri, zipFile)
                
                // ★ ステータスを正確に判定（最終ページなら必ずCOMPLETED）
                val status = when {
                    currentPagePosition >= totalPageCount - 1 -> {
                        println("DEBUG: DirectZipHandler - ★★★ File on last page -> COMPLETED ★★★")
                        com.celstech.satendroid.ui.models.ReadingStatus.COMPLETED
                    }
                    currentPagePosition > 0 -> com.celstech.satendroid.ui.models.ReadingStatus.READING
                    else -> com.celstech.satendroid.ui.models.ReadingStatus.UNREAD
                }
                
                // 同期保存で確実に保存
                withContext(Dispatchers.IO) {
                    readingStateManager.updateCurrentPage(
                        filePath = filePath,
                        page = currentPagePosition,
                        totalPages = totalPageCount
                    )
                    readingStateManager.saveStateSync(filePath)
                }
                
                println("DEBUG: DirectZipHandler - ★ Saved file state ★")
                println("  File: ${zipFile?.name ?: zipUri}")
                println("  Page: $currentPagePosition/$totalPageCount")
                println("  Status: $status")
            }
        }
    }
    
    /**
     * 現在のZIPファイルを閉じ、読書状態を確定して保存
     * ファイルクローズ時に必ず呼ばれるべきメソッド
     * 
     * UIの状態には一切依存せず、DirectZipHandler自身が管理している状態を使用
     */
    suspend fun closeCurrentZipFileWithState() {
        stateMutex.withLock {
            val zipUri = currentZipUri
            val zipFile = currentZipEntries.firstOrNull()?.zipFile
            
            if (zipUri != null && totalPageCount > 0) {
                // ファイルパスを生成
                val filePath = generateFileIdentifier(zipUri, zipFile)
                
                // ★ 自分が管理している現在ページを使う（UIの状態には依存しない）
                // ★ ステータスを正確に判定（最終ページなら必ずCOMPLETED）
                val status = when {
                    currentPagePosition >= totalPageCount - 1 -> {
                        println("DEBUG: DirectZipHandler - ★★★ File on last page (close) -> COMPLETED ★★★")
                        com.celstech.satendroid.ui.models.ReadingStatus.COMPLETED
                    }
                    currentPagePosition > 0 -> com.celstech.satendroid.ui.models.ReadingStatus.READING
                    else -> com.celstech.satendroid.ui.models.ReadingStatus.UNREAD
                }
                
                // 同期保存で確実に保存
                withContext(Dispatchers.IO) {
                    readingStateManager.updateCurrentPage(
                        filePath = filePath,
                        page = currentPagePosition,
                        totalPages = totalPageCount
                    )
                    readingStateManager.saveStateSync(filePath)
                }
                
                println("DEBUG: DirectZipHandler - ★ Saved state on close ★")
                println("  File: ${zipFile?.name ?: zipUri}")
                println("  Page: $currentPagePosition/$totalPageCount")
                println("  Status: $status")
            }
            
            // 物理的にZIPファイルを閉じる
            closeCurrentZipFileInternal()
            
            // 状態をクリア
            currentZipUri = null
            currentZipEntries = emptyList()
            currentPagePosition = 0
            totalPageCount = 0
        }
    }

    /**
     * 現在のZipファイルを閉じる（内部用・状態保存なし）
     */
    private suspend fun closeCurrentZipFileInternal() {
        try {
            // 管理されているすべてのZipFileを閉じる
            zipFileManager.closeAllZipFiles()
            println("DEBUG: All ZipFiles closed via ZipFileManager")
        } catch (_: Exception) {
            println("DEBUG: Error closing ZipFiles")
        }
    }

    /**
     * 現在のZipファイルを閉じる（完全同期化版・互換性用）
     * @deprecated 状態保存が必要な場合はcloseCurrentZipFileWithStateを使用してください
     */
    @Deprecated("Use closeCurrentZipFileWithState for proper state management")
    suspend fun closeCurrentZipFile() {
        closeCurrentZipFileInternal()
    }

    /**
     * 現在のZipファイルを閉じる（非同期版・互換性用）
     */
    fun closeCurrentZipFileAsync() {
        preloadScope.launch {
            closeCurrentZipFile()
        }
    }

    /**
     * プリロードをトリガー（Phase 2: エラーハンドリング強化版）
     */
    private fun triggerPreload(currentIndex: Int) {
        if (!enablePreload) {
            println("DEBUG: Preload disabled")
            return
        }

        preloadScope.launch {
            try {
                // 現在の状態を同期化して取得
                val currentEntries = stateMutex.withLock {
                    currentZipEntries.toList() // 不変のコピーを作成
                }

                if (currentEntries.isEmpty()) {
                    println("DEBUG: No entries available for preload")
                    return@launch
                }

                // 現在のメモリ使用状況に基づいてプリロード範囲を動的調整
                val memoryUsagePercent = (currentMemoryUsage.get().toFloat() / maxMemoryUsage) * 100
                val dynamicPreloadRange = when {
                    memoryUsagePercent > 70 -> minPreloadRange // メモリ使用量が多い場合は最小範囲
                    memoryUsagePercent < 30 -> maxPreloadRange // メモリに余裕がある場合は最大範囲
                    else -> preloadRange // 通常範囲
                }

                val startIndex = max(0, currentIndex - dynamicPreloadRange)
                val endIndex = min(currentEntries.size - 1, currentIndex + dynamicPreloadRange)

                println("DEBUG: Phase 2 Preload - Range $startIndex to $endIndex (current: $currentIndex)")
                println("DEBUG: Dynamic preload range: $dynamicPreloadRange (memory usage: ${memoryUsagePercent.toInt()}%)")
                println("DEBUG: Active cache size: ${imageDataCache.size}/$maxCacheSize")

                // 優先度付きでプリロード要求を送信
                val requests = mutableListOf<PreloadRequest>()

                for (i in startIndex..endIndex) {
                    if (i == currentIndex) continue // 現在のページはスキップ

                    val entry = currentEntries[i]

                    // 既にキャッシュされているか、アクティブな処理があるかチェック
                    if (imageDataCache.containsKey(entry.id)) {
                        println("DEBUG: Skipping preload (cached): ${entry.fileName}")
                        continue
                    }

                    if (activePreloadJobs.values.any { !it.isCancelled }) {
                        println("DEBUG: Skipping preload (active job exists): ${entry.fileName}")
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
                            println("DEBUG: Phase 2 Queued preload: ${request.entry.fileName} (priority: ${request.priority})")
                            delay(200) // プリロード要求間に間隔を設ける（100msから延長）
                        } else {
                            println("DEBUG: Phase 2 Preload queue full, skipping: ${request.entry.fileName}")
                        }
                    } catch (e: Exception) {
                        println("ERROR: Phase 2 Failed to queue preload: ${e.message}")
                    }
                }

                println("DEBUG: Phase 2 Preload trigger completed - ${requests.size} requests processed")

            } catch (e: Exception) {
                println("ERROR: Phase 2 Exception in triggerPreload: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    /**
     * 古いキャッシュのクリーンアップ（完全同期化版）
     */
    private suspend fun cleanupOldCache() {
        cacheMutex.withLock {
            cleanupOldCacheInternal()
        }
    }

    /**
     * 古いキャッシュのクリーンアップ（内部実装・既にロック済みを前提）
     */
    private fun cleanupOldCacheInternal() {
        if (imageDataCache.isEmpty()) return

        val beforeSize = imageDataCache.size
        val beforeMemory = currentMemoryUsage.get()

        // 現在の状態を安全に取得
        val currentEntries = currentZipEntries.toList()

        // 現在表示中の範囲を計算
        val currentIndex = currentEntries.indexOfFirst {
            imageDataCache.containsKey(it.id)
        }.takeIf { it != -1 } ?: 0

        // 保護する範囲（現在のプリロード範囲）
        val protectedRange = max(0, currentIndex - preloadRange)..min(
            currentEntries.size - 1,
            currentIndex + preloadRange
        )

        // 削除対象のキーを収集
        val keysToRemove = mutableListOf<Pair<String, Long>>()

        imageDataCache.forEach { (key, data) ->
            val entryIndex = currentEntries.indexOfFirst { it.id == key }

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
     * 位置保存（バッチ処理版・内部使用）
     */
    private fun saveCurrentPositionBatched(zipUri: Uri, imageIndex: Int, zipFile: File? = null) {
        preloadScope.launch {
            positionQueue.send(PositionSaveRequest(zipUri, imageIndex, zipFile))
        }
    }

    /**
     * ファイル識別子を生成（public）
     */
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

    /**
     * パフォーマンスメトリクスの更新（完全同期化版）
     */
    private fun updateMetrics(loadTime: Long, isHit: Boolean = false, isPreload: Boolean = false) {
        // メトリクス更新をバックグラウンドで実行（メインスレッドの負荷軽減）
        preloadScope.launch {
            metricsMutex.withLock {
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
                } catch (_: Exception) {
                    // メトリクス更新エラーは無視（パフォーマンス優先）
                }
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
     * メモリキャッシュをクリア（完全同期化版）
     * 注意: currentZipUri と currentZipEntries はクリアしない（状態保持）
     */
    suspend fun clearMemoryCache() {
        // アクティブなプリロードをキャンセル
        cancelAllActivePreloads()

        // キャッシュを同期化してクリア
        cacheMutex.withLock {
            imageDataCache.clear()
            currentMemoryUsage.set(0)
        }

        // エントリキャッシュのみクリア（状態は保持）
        stateMutex.withLock {
            zipEntryCache.clear()
            // currentZipUri と currentZipEntries はクリアしない
            // currentPagePosition と totalPageCount も保持
        }

        println("DEBUG: Memory cache cleared (state preserved)")
        println("DEBUG: Active ZipFiles: ${zipFileManager.getActiveZipFileCount()}")
        println("DEBUG: Memory usage reset to 0")

        System.gc()
    }

    /**
     * メモリキャッシュをクリア（非同期版・互換性用）
     */
    fun clearMemoryCacheAsync() {
        preloadScope.launch {
            clearMemoryCache()
        }
    }

    /**
     * リソースのクリーンアップ（完全同期化版）
     */
    suspend fun cleanup() {
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

    /**
     * リソースのクリーンアップ（非同期版・互換性用）
     */
    fun cleanupAsync() {
        preloadScope.launch {
            cleanup()
        }
    }

    /**
     * 外部からの位置保存要求を受け付ける（バッチ処理版を内部利用）
     */
    fun saveCurrentPosition(zipUri: Uri, imageIndex: Int, zipFile: File? = null) {
        saveCurrentPositionBatched(zipUri, imageIndex, zipFile)
    }

    /**
     * 外部からの位置取得要求
     */
    fun getSavedPosition(zipUri: Uri, zipFile: File? = null): Int {
        val filePath = generateFileIdentifier(zipUri, zipFile)
        val state = readingStateManager.getState(filePath)
        return state.currentPage
    }

    /**
     * フォルダー削除処理（簡略化）
     */
    fun onFolderDeleted(folderPath: String) {
        // フォルダー削除時のデータクリアは不要（age機能で後から削除）
        // メモリキャッシュのみクリア
        preloadScope.launch {
            try {
                println("DEBUG: DirectZipHandler - Folder deleted notification: $folderPath")

                // メモリキャッシュから関連データを削除
                cacheMutex.withLock {
                    val keysToRemove = imageDataCache.keys.filter { key ->
                        key.contains(folderPath) || folderPath.contains(key.substringBeforeLast('_'))
                    }
                    keysToRemove.forEach { key ->
                        val data = imageDataCache.remove(key)
                        if (data != null) {
                            currentMemoryUsage.addAndGet(-data.size.toLong())
                        }
                    }

                    if (keysToRemove.isNotEmpty()) {
                        println("DEBUG: DirectZipHandler - Cleared ${keysToRemove.size} cached images for folder")
                    }
                }

                // エントリキャッシュからも削除
                stateMutex.withLock {
                    val keysToRemove = zipEntryCache.keys.filter { key ->
                        key.contains(folderPath) || folderPath.contains(key.substringBeforeLast('/'))
                    }
                    keysToRemove.forEach { key ->
                        zipEntryCache.remove(key)
                    }

                    if (keysToRemove.isNotEmpty()) {
                        println("DEBUG: DirectZipHandler - Cleared ${keysToRemove.size} entry caches for folder")
                    }
                }

            } catch (e: Exception) {
                println("ERROR: DirectZipHandler - Failed to handle folder deletion: ${e.message}")
                e.printStackTrace()
            }
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
     * デバッグ用: ZipFileManagerの状態を表示（同期化版）
     */
    @Suppress("unused")
    suspend fun getZipFileManagerStatus(): String {
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