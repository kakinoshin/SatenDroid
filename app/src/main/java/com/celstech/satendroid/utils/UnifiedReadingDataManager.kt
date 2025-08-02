package com.celstech.satendroid.utils

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.edit
import com.celstech.satendroid.ui.models.ReadingStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 統合読書データ管理クラス（統合版）
 * ReadingStatusManagerとImageCacheManagerの機能を統合
 * バッチ処理と設定管理を一元化
 */
class UnifiedReadingDataManager(private val context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "unified_reading_data", 
        Context.MODE_PRIVATE
    )
    
    // データ移行用の旧システムアクセス
    private val legacyReadingPrefs: SharedPreferences = context.getSharedPreferences(
        "reading_status_prefs", 
        Context.MODE_PRIVATE
    )
    
    private val legacyImageCachePrefs: SharedPreferences = context.getSharedPreferences(
        "image_cache_settings", 
        Context.MODE_PRIVATE
    )
    
    // バッチ処理用のJob
    private val batchJob = SupervisorJob()
    private val batchScope = CoroutineScope(Dispatchers.IO + batchJob)
    
    // 位置保存要求のチャンネル
    private val positionSaveChannel = Channel<PositionSaveRequest>(Channel.UNLIMITED)
    
    // 位置保存のバッチ処理マップ（ファイルごとの最新位置を保持）
    private val pendingPositions = ConcurrentHashMap<String, PositionSaveRequest>()
    
    // 設定関連のStateFlow
    private val _cacheEnabled = MutableStateFlow(prefs.getBoolean(KEY_CACHE_ENABLED, true))
    val cacheEnabled: StateFlow<Boolean> = _cacheEnabled.asStateFlow()
    
    private val _clearCacheOnDelete = MutableStateFlow(prefs.getBoolean(KEY_CLEAR_CACHE_ON_DELETE, true))
    val clearCacheOnDelete: StateFlow<Boolean> = _clearCacheOnDelete.asStateFlow()
    
    private val _reverseSwipeDirection = MutableStateFlow(prefs.getBoolean(KEY_REVERSE_SWIPE_DIRECTION, false))
    val reverseSwipeDirection: StateFlow<Boolean> = _reverseSwipeDirection.asStateFlow()
    
    companion object {
        // 既存のキー（読書状況管理）
        private const val STATUS_PREFIX = "status_"
        private const val POSITION_PREFIX = "position_"
        private const val LAST_UPDATED_PREFIX = "updated_"
        private const val TOTAL_IMAGES_PREFIX = "total_"
        
        // ImageCacheManagerから統合するキー
        private const val FILE_HASH_PREFIX = "hash_"
        
        // 設定関連（ImageCacheManagerから移行）
        private const val KEY_CACHE_ENABLED = "cache_enabled"
        private const val KEY_CLEAR_CACHE_ON_DELETE = "clear_cache_on_delete"
        private const val KEY_REVERSE_SWIPE_DIRECTION = "reverse_swipe_direction"
        
        // 移行完了フラグ
        private const val MIGRATION_COMPLETED = "migration_completed"
        private const val IMAGE_CACHE_MIGRATION_COMPLETED = "image_cache_migration_completed"
        
        // バッチ処理設定
        private const val POSITION_SAVE_DELAY = 1000L
        
        // 旧システムのキー（ReadingStatusManager）
        private const val LEGACY_STATUS_PREFIX = "status_"
        private const val LEGACY_CURRENT_INDEX_PREFIX = "current_index_"
    }
    
    data class PositionSaveRequest(
        val filePath: String,
        val position: Int,
        val totalImages: Int,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    init {
        // 初回起動時にデータ移行を実行
        if (!prefs.getBoolean(MIGRATION_COMPLETED, false)) {
            migrateLegacyData()
        }
        
        // ImageCacheManagerからのデータ移行
        if (!prefs.getBoolean(IMAGE_CACHE_MIGRATION_COMPLETED, false)) {
            migrateFromImageCacheManager()
        }
        
        // バッチ処理ワーカーを開始
        startBatchWorker()
    }
    
    /**
     * ImageCacheManagerからのデータ移行
     */
    private fun migrateFromImageCacheManager() {
        try {
            println("DEBUG: Starting ImageCacheManager migration...")
            
            val allKeys = legacyImageCachePrefs.all.keys
            val editor = prefs.edit()
            var migrationCount = 0
            var settingsMigrated = 0
            
            allKeys.forEach { key ->
                when {
                    key.startsWith("position_") -> {
                        val filePath = key.removePrefix("position_")
                        val position = legacyImageCachePrefs.getInt(key, -1)
                        if (position >= 0) {
                            val normalizedPath = normalizeFilePath(filePath)
                            // 既存データがない場合のみ移行
                            if (!prefs.contains(POSITION_PREFIX + normalizedPath)) {
                                editor.putInt(POSITION_PREFIX + normalizedPath, position)
                                editor.putLong(LAST_UPDATED_PREFIX + normalizedPath, System.currentTimeMillis())
                                // 位置があるということは読書中として扱う
                                editor.putString(STATUS_PREFIX + normalizedPath, ReadingStatus.READING.name)
                                migrationCount++
                                println("DEBUG: Migrated position: $filePath -> $position")
                            }
                        }
                    }
                    key.startsWith("hash_") -> {
                        val filePath = key.removePrefix("hash_")
                        val hash = legacyImageCachePrefs.getString(key, null)
                        if (hash != null) {
                            val normalizedPath = normalizeFilePath(filePath)
                            editor.putString(FILE_HASH_PREFIX + normalizedPath, hash)
                        }
                    }
                    key == "reverse_swipe_direction" -> {
                        val value = legacyImageCachePrefs.getBoolean(key, false)
                        editor.putBoolean(KEY_REVERSE_SWIPE_DIRECTION, value)
                        _reverseSwipeDirection.value = value
                        settingsMigrated++
                        println("DEBUG: Migrated reverse_swipe_direction: $value")
                    }
                    key == "cache_enabled" -> {
                        val value = legacyImageCachePrefs.getBoolean(key, true)
                        editor.putBoolean(KEY_CACHE_ENABLED, value)
                        _cacheEnabled.value = value
                        settingsMigrated++
                        println("DEBUG: Migrated cache_enabled: $value")
                    }
                    key == "clear_cache_on_delete" -> {
                        val value = legacyImageCachePrefs.getBoolean(key, true)
                        editor.putBoolean(KEY_CLEAR_CACHE_ON_DELETE, value)
                        _clearCacheOnDelete.value = value
                        settingsMigrated++
                        println("DEBUG: Migrated clear_cache_on_delete: $value")
                    }
                    key.startsWith("last_") -> {
                        // 最後のファイル情報も移行（デバッグ用）
                        when (key) {
                            "last_zip_identifier" -> {
                                val value = legacyImageCachePrefs.getString(key, null)
                                if (value != null) {
                                    println("DEBUG: Found last_zip_identifier: $value")
                                }
                            }
                            "last_image_index" -> {
                                val value = legacyImageCachePrefs.getInt(key, -1)
                                if (value >= 0) {
                                    println("DEBUG: Found last_image_index: $value")
                                }
                            }
                        }
                    }
                }
            }
            
            // 移行完了をマーク
            editor.putBoolean(IMAGE_CACHE_MIGRATION_COMPLETED, true)
            editor.apply()
            
            println("DEBUG: ImageCacheManager migration completed:")
            println("  - Position records migrated: $migrationCount")
            println("  - Settings migrated: $settingsMigrated")
            println("  - Total keys processed: ${allKeys.size}")
            
        } catch (e: Exception) {
            println("ERROR: ImageCacheManager migration failed: ${e.message}")
            e.printStackTrace()
            
            // 失敗しても移行完了をマーク（無限ループ防止）
            prefs.edit {
                putBoolean(IMAGE_CACHE_MIGRATION_COMPLETED, true)
            }
        }
    }
    
    /**
     * バッチ処理ワーカーを開始
     */
    private fun startBatchWorker() {
        batchScope.launch {
            for (request in positionSaveChannel) {
                // 同じファイルの場合は最新の位置で上書き
                pendingPositions[request.filePath] = request
                
                // 遅延後にバッチ保存を実行
                delay(POSITION_SAVE_DELAY)
                processBatchSave()
            }
        }
    }
    
    /**
     * バッチ処理で位置を保存
     */
    private suspend fun processBatchSave() = withContext(Dispatchers.IO) {
        if (pendingPositions.isEmpty()) return@withContext
        
        val requestsToProcess = pendingPositions.values.toList()
        pendingPositions.clear()
        
        try {
            val editor = prefs.edit()
            val currentTime = System.currentTimeMillis()
            
            requestsToProcess.forEach { request ->
                val normalizedPath = normalizeFilePath(request.filePath)
                
                // ReadingStatusを自動判定
                val status = when {
                    request.position < 0 -> ReadingStatus.UNREAD
                    request.position >= request.totalImages - 1 && request.totalImages > 0 -> ReadingStatus.COMPLETED
                    request.totalImages > 0 -> ReadingStatus.READING
                    else -> ReadingStatus.UNREAD
                }
                
                // 統合データ保存
                editor.putInt(POSITION_PREFIX + normalizedPath, request.position)
                editor.putString(STATUS_PREFIX + normalizedPath, status.name)
                editor.putLong(LAST_UPDATED_PREFIX + normalizedPath, currentTime)
                if (request.totalImages > 0) {
                    editor.putInt(TOTAL_IMAGES_PREFIX + normalizedPath, request.totalImages)
                }
                
                // ファイルハッシュも更新
                val hash = generateFileHash(request.filePath)
                if (hash != null) {
                    editor.putString(FILE_HASH_PREFIX + normalizedPath, hash)
                }
                
                println("DEBUG: Batch saving unified data - File: ${request.filePath.substringAfterLast('/')}")
                println("DEBUG:   Position: ${request.position}, Status: $status, Total: ${request.totalImages}")
            }
            
            editor.apply()
            println("DEBUG: Batch saved ${requestsToProcess.size} unified records successfully")
            
        } catch (e: Exception) {
            println("ERROR: Unified batch save failed: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * ファイルパスを正規化して一貫したキーを生成
     */
    private fun normalizeFilePath(filePath: String): String {
        return filePath
            .replace("\\", "/")
            .replace("//+".toRegex(), "/")
            .removeSuffix("/")
    }
    
    /**
     * 読書位置を保存（バッチ処理対応・統合版）
     */
    suspend fun saveReadingPosition(
        filePath: String,
        position: Int,
        totalImages: Int = 0
    ) = withContext(Dispatchers.IO) {
        if (!_cacheEnabled.value) {
            println("DEBUG: Cache disabled, skipping position save")
            return@withContext
        }
        
        // 即座に保存が必要な場合は直接保存
        if (position < 0 || totalImages <= 0) {
            saveReadingPositionImmediately(filePath, position, totalImages)
            return@withContext
        }
        
        // バッチ処理に追加
        val request = PositionSaveRequest(filePath, position, totalImages)
        positionSaveChannel.send(request)
        
        println("DEBUG: Queued position save - File: ${filePath.substringAfterLast('/')}, Position: $position")
    }
    
    /**
     * 即座に位置を保存（緊急時用・統合版）
     */
    suspend fun saveReadingPositionImmediately(
        filePath: String,
        position: Int,
        totalImages: Int = 0
    ) = withContext(Dispatchers.IO) {
        if (!_cacheEnabled.value) {
            return@withContext
        }
        
        val normalizedPath = normalizeFilePath(filePath)
        val currentTime = System.currentTimeMillis()
        
        // ReadingStatusを自動判定（修正版）
        val status = when {
            position < 0 -> ReadingStatus.UNREAD  // マイナス値のみ未読
            position >= totalImages - 1 && totalImages > 0 -> ReadingStatus.COMPLETED
            totalImages > 0 -> ReadingStatus.READING  // 有効な画像がある場合は読書中
            else -> ReadingStatus.UNREAD
        }
        
        prefs.edit {
            putInt(POSITION_PREFIX + normalizedPath, position)
            putString(STATUS_PREFIX + normalizedPath, status.name)
            putLong(LAST_UPDATED_PREFIX + normalizedPath, currentTime)
            if (totalImages > 0) {
                putInt(TOTAL_IMAGES_PREFIX + normalizedPath, totalImages)
            }
            
            // ファイルハッシュも更新
            val hash = generateFileHash(filePath)
            if (hash != null) {
                putString(FILE_HASH_PREFIX + normalizedPath, hash)
            }
        }
        
        println("DEBUG: Immediately saved unified data - Position: $position, Status: $status")
    }
    
    /**
     * 読書位置を取得
     */
    suspend fun getReadingPosition(filePath: String): Int = withContext(Dispatchers.IO) {
        val normalizedPath = normalizeFilePath(filePath)
        prefs.getInt(POSITION_PREFIX + normalizedPath, 0)
    }
    
    /**
     * 読書位置を同期的に取得（UI用）
     */
    fun getReadingPositionSync(filePath: String): Int {
        val normalizedPath = normalizeFilePath(filePath)
        return prefs.getInt(POSITION_PREFIX + normalizedPath, 0)
    }
    
    /**
     * 保存された位置を取得（ImageCacheManager互換）
     */
    fun getSavedPosition(filePath: String): Int {
        val normalizedPath = normalizeFilePath(filePath)
        val position = prefs.getInt(POSITION_PREFIX + normalizedPath, -1)
        return if (position >= 0) position else 0
    }
    
    /**
     * 保存された位置を取得（URI/File対応版）
     * DirectZipImageHandlerからの呼び出し用
     */
    fun getSavedPosition(zipUri: Uri, zipFile: File?): Int? {
        val filePath = generateFileIdentifier(zipUri, zipFile)
        val normalizedPath = normalizeFilePath(filePath)
        val position = prefs.getInt(POSITION_PREFIX + normalizedPath, -1)
        
        println("DEBUG: Getting saved position for: ${zipFile?.name ?: zipUri}")
        println("DEBUG:   File path: $filePath")
        println("DEBUG:   Normalized path: $normalizedPath")
        println("DEBUG:   Saved position: $position")
        
        return if (position >= 0) position else null
    }
    
    /**
     * 読書状況を取得
     */
    suspend fun getReadingStatus(filePath: String): ReadingStatus = withContext(Dispatchers.IO) {
        val normalizedPath = normalizeFilePath(filePath)
        val statusString = prefs.getString(STATUS_PREFIX + normalizedPath, ReadingStatus.UNREAD.name)
        try {
            ReadingStatus.valueOf(statusString ?: ReadingStatus.UNREAD.name)
        } catch (_: IllegalArgumentException) {
            ReadingStatus.UNREAD
        }
    }
    
    /**
     * 読書状況を同期的に取得（UI用）
     */
    fun getReadingStatusSync(filePath: String): ReadingStatus {
        val normalizedPath = normalizeFilePath(filePath)
        val statusString = prefs.getString(STATUS_PREFIX + normalizedPath, ReadingStatus.UNREAD.name)
        val result = try {
            ReadingStatus.valueOf(statusString ?: ReadingStatus.UNREAD.name)
        } catch (_: IllegalArgumentException) {
            ReadingStatus.UNREAD
        }
        
        // デバッグログ（詳細版）
        println("DEBUG: UnifiedDataManager read - File: ${filePath.substringAfterLast('/')}")
        println("DEBUG:   Normalized Path: $normalizedPath")
        println("DEBUG:   Stored Status String: '$statusString'")
        println("DEBUG:   Parsed Status: $result")
        println("DEBUG:   Current Position: ${getReadingPositionSync(filePath)}")
        println("DEBUG:   Total Images: ${getTotalImages(filePath)}")
        
        return result
    }
    
    /**
     * 読書進捗情報を一括取得
     */
    suspend fun getReadingProgress(filePath: String): ReadingProgress = withContext(Dispatchers.IO) {
        ReadingProgress(
            status = getReadingStatus(filePath),
            currentIndex = getReadingPosition(filePath)
        )
    }
    
    /**
     * 読書進捗情報を同期的に一括取得（UI用）
     */
    fun getReadingProgressSync(filePath: String): ReadingProgress {
        return ReadingProgress(
            status = getReadingStatusSync(filePath),
            currentIndex = getReadingPositionSync(filePath)
        )
    }
    
    /**
     * 総画像数を保存
     */
    suspend fun saveTotalImages(filePath: String, totalImages: Int) = withContext(Dispatchers.IO) {
        val normalizedPath = normalizeFilePath(filePath)
        prefs.edit {
            putInt(TOTAL_IMAGES_PREFIX + normalizedPath, totalImages)
        }
    }
    
    /**
     * 総画像数を取得
     */
    fun getTotalImages(filePath: String): Int {
        val normalizedPath = normalizeFilePath(filePath)
        return prefs.getInt(TOTAL_IMAGES_PREFIX + normalizedPath, 0)
    }
    
    /**
     * ファイル識別子を生成（ImageCacheManagerから移行）
     */
    fun generateFileIdentifier(zipUri: Uri, zipFile: File?): String {
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
     * ファイルハッシュを生成（ファイルサイズと更新日時から）
     */
    private fun generateFileHash(filePath: String): String? {
        return try {
            val file = File(filePath)
            if (file.exists()) {
                "${file.length()}_${file.lastModified()}"
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    // === 設定管理機能（ImageCacheManagerから移行） ===
    
    /**
     * キャッシュ機能の有効/無効を設定
     */
    fun setCacheEnabled(enabled: Boolean) {
        _cacheEnabled.value = enabled
        batchScope.launch {
            prefs.edit().putBoolean(KEY_CACHE_ENABLED, enabled).apply()
        }
        println("DEBUG: Cache enabled set to: $enabled")
    }
    
    /**
     * ファイル削除時にキャッシュをクリアするかの設定
     */
    fun setClearCacheOnDelete(clearOnDelete: Boolean) {
        _clearCacheOnDelete.value = clearOnDelete
        batchScope.launch {
            prefs.edit().putBoolean(KEY_CLEAR_CACHE_ON_DELETE, clearOnDelete).apply()
        }
        println("DEBUG: Clear cache on delete set to: $clearOnDelete")
    }
    
    /**
     * スワイプ方向を逆転するかの設定
     */
    fun setReverseSwipeDirection(reverse: Boolean) {
        _reverseSwipeDirection.value = reverse
        batchScope.launch {
            prefs.edit().putBoolean(KEY_REVERSE_SWIPE_DIRECTION, reverse).apply()
        }
        println("DEBUG: Reverse swipe direction set to: $reverse")
    }
    
    // === バッチ処理・キャッシュ管理 ===
    
    /**
     * 位置保存（バッチ処理版）- ImageCacheManager互換
     */
    fun saveCurrentPosition(zipUri: Uri, imageIndex: Int, zipFile: File? = null) {
        if (!_cacheEnabled.value) return
        
        val filePath = generateFileIdentifier(zipUri, zipFile)
        batchScope.launch {
            saveReadingPosition(filePath, imageIndex, 0) // totalImagesは後で更新
        }
        
        println("DEBUG: Queued position save via URI - File: ${zipFile?.name ?: zipUri}, Position: $imageIndex")
    }
    
    /**
     * 即座に位置を保存（緊急時用）- ImageCacheManager互換
     */
    suspend fun saveCurrentPositionImmediately(zipUri: Uri, imageIndex: Int, zipFile: File? = null) {
        if (!_cacheEnabled.value) return
        
        val filePath = generateFileIdentifier(zipUri, zipFile)
        saveReadingPositionImmediately(filePath, imageIndex, 0)
        
        println("DEBUG: Immediately saved position via URI - File: ${zipFile?.name ?: zipUri}, Position: $imageIndex")
    }
    
    /**
     * 未保存の位置をフラッシュ（アプリ終了時など）
     */
    suspend fun flushPendingPositions() {
        if (pendingPositions.isNotEmpty()) {
            println("DEBUG: Flushing ${pendingPositions.size} pending positions")
            processBatchSave()
        }
    }
    
    /**
     * ファイルの読書データをクリア
     */
    suspend fun clearReadingData(filePath: String) = withContext(Dispatchers.IO) {
        val normalizedPath = normalizeFilePath(filePath)
        
        // バッチ処理からも削除
        pendingPositions.remove(filePath)
        
        prefs.edit {
            remove(STATUS_PREFIX + normalizedPath)
            remove(POSITION_PREFIX + normalizedPath)
            remove(LAST_UPDATED_PREFIX + normalizedPath)
            remove(TOTAL_IMAGES_PREFIX + normalizedPath)
            remove(FILE_HASH_PREFIX + normalizedPath)
        }
        
        println("DEBUG: Cleared reading data for: ${filePath.substringAfterLast('/')}")
    }
    
    /**
     * 特定のZIPファイルのキャッシュをクリア（ImageCacheManager互換）
     */
    fun clearCacheForZip(zipUri: Uri, zipFile: File? = null) {
        if (!_cacheEnabled.value) return
        
        val filePath = generateFileIdentifier(zipUri, zipFile)
        batchScope.launch {
            clearReadingData(filePath)
        }
    }
    
    /**
     * ファイル削除時の処理（統合版）
     */
    fun onFileDeleted(zipUri: Uri, zipFile: File? = null) {
        if (_clearCacheOnDelete.value) {
            clearCacheForZip(zipUri, zipFile)
        }
    }
    
    /**
     * 全ての読書データをクリア
     */
    suspend fun clearAllReadingData() = withContext(Dispatchers.IO) {
        // バッチ処理をクリア
        pendingPositions.clear()
        
        prefs.edit { clear() }
        
        println("DEBUG: Cleared all reading data")
    }
    
    /**
     * キャッシュデータをクリア（ImageCacheManager互換）
     */
    fun clearCache() {
        batchScope.launch {
            clearAllReadingData()
        }
    }
    
    /**
     * 設定の概要を取得（ImageCacheManager互換）
     */
    fun getSettingsSummary(): String {
        val cacheStatus = if (_cacheEnabled.value) "有効" else "無効"
        val deleteStatus = if (_clearCacheOnDelete.value) "削除する" else "保持する"
        val swipeStatus = if (_reverseSwipeDirection.value) "逆転" else "標準"
        val pendingCount = pendingPositions.size
        return "キャッシュ機能: $cacheStatus\nファイル削除時: $deleteStatus\nスワイプ方向: $swipeStatus\n未保存位置: $pendingCount"
    }
    
    /**
     * 旧システムからのデータ移行（簡略版）
     */
    private fun migrateLegacyData() {
        try {
            println("DEBUG: Starting legacy data migration...")
            
            // 移行完了をマーク（データ移行を無効化）
            prefs.edit {
                putBoolean(MIGRATION_COMPLETED, true)
            }
            
            println("DEBUG: Legacy data migration completed (simplified).")
            
        } catch (e: Exception) {
            println("DEBUG: Legacy data migration failed: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * デバッグ用: 指定ファイルの全データを表示
     */
    fun debugPrintFileData(filePath: String) {
        val normalizedPath = normalizeFilePath(filePath)
        val position = getReadingPositionSync(filePath)
        val status = getReadingStatusSync(filePath)
        val totalImages = getTotalImages(filePath)
        val lastUpdated = prefs.getLong(LAST_UPDATED_PREFIX + normalizedPath, 0)
        val hash = prefs.getString(FILE_HASH_PREFIX + normalizedPath, null)
        
        println("DEBUG: UnifiedDataManager - File: ${filePath.substringAfterLast('/')}")
        println("DEBUG:   Position: $position")
        println("DEBUG:   Status: $status")
        println("DEBUG:   Total Images: $totalImages")
        println("DEBUG:   Last Updated: $lastUpdated")
        println("DEBUG:   Hash: $hash")
        println("DEBUG:   Pending Batch: ${pendingPositions.containsKey(filePath)}")
    }
    
    /**
     * リソースのクリーンアップ
     */
    fun cleanup() {
        batchScope.launch {
            // 未保存データをフラッシュ
            flushPendingPositions()
        }
        
        // バッチJobをキャンセル
        batchJob.cancel()
        pendingPositions.clear()
        
        println("DEBUG: UnifiedReadingDataManager cleanup completed")
    }
}
