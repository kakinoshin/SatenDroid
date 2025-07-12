package com.celstech.satendroid.cache

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 最適化された画像キャッシュ管理クラス
 * バッチ処理と非同期処理で高速化
 */
class ImageCacheManager(private val context: Context) {
    
    companion object {
        private const val PREFS_NAME = "image_cache_settings"
        private const val KEY_CACHE_ENABLED = "cache_enabled"
        private const val KEY_CLEAR_CACHE_ON_DELETE = "clear_cache_on_delete"
        private const val KEY_LAST_ZIP_IDENTIFIER = "last_zip_identifier"
        private const val KEY_LAST_IMAGE_INDEX = "last_image_index"
        private const val KEY_LAST_ZIP_HASH = "last_zip_hash"
        private const val KEY_REVERSE_SWIPE_DIRECTION = "reverse_swipe_direction"
        private const val KEY_FILE_POSITION_PREFIX = "position_"
        private const val KEY_FILE_HASH_PREFIX = "hash_"
        
        // 位置保存の遅延時間（ミリ秒）
        private const val POSITION_SAVE_DELAY = 1000L
        // バッチ処理の最大遅延時間
        private const val MAX_BATCH_DELAY = 3000L
    }
    
    private val sharedPrefs: SharedPreferences = 
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    private val _cacheEnabled = MutableStateFlow(sharedPrefs.getBoolean(KEY_CACHE_ENABLED, true))
    val cacheEnabled: StateFlow<Boolean> = _cacheEnabled.asStateFlow()
    
    private val _clearCacheOnDelete = MutableStateFlow(sharedPrefs.getBoolean(KEY_CLEAR_CACHE_ON_DELETE, true))
    val clearCacheOnDelete: StateFlow<Boolean> = _clearCacheOnDelete.asStateFlow()
    
    private val _reverseSwipeDirection = MutableStateFlow(sharedPrefs.getBoolean(KEY_REVERSE_SWIPE_DIRECTION, false))
    val reverseSwipeDirection: StateFlow<Boolean> = _reverseSwipeDirection.asStateFlow()
    
    // バッチ処理用のコルーチンスコープ
    private val batchScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // 位置保存要求のチャンネル
    private val positionSaveChannel = Channel<PositionSaveRequest>(Channel.UNLIMITED)
    
    // 位置保存のバッチ処理マップ（ファイルごとの最新位置を保持）
    private val pendingPositions = ConcurrentHashMap<String, PositionSaveRequest>()
    
    // 位置保存ジョブの管理
    private var batchSaveJob: Job? = null
    
    data class PositionSaveRequest(
        val zipIdentifier: String,
        val zipUri: Uri,
        val imageIndex: Int,
        val zipFile: File?,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    init {
        // バッチ処理ワーカーを開始
        startBatchWorker()
    }
    
    /**
     * バッチ処理ワーカーを開始
     */
    private fun startBatchWorker() {
        batchScope.launch {
            for (request in positionSaveChannel) {
                // 同じファイルの場合は最新の位置で上書き
                pendingPositions[request.zipIdentifier] = request
                
                // 既存のバッチジョブをキャンセル
                batchSaveJob?.cancel()
                
                // 新しいバッチジョブを開始
                batchSaveJob = batchScope.launch {
                    delay(POSITION_SAVE_DELAY)
                    processBatchSave()
                }
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
            // 一括でSharedPreferencesに書き込み
            val editor = sharedPrefs.edit()
            
            requestsToProcess.forEach { request ->
                val zipHash = generateZipHash(request.zipUri, request.zipFile)
                val normalizedKey = normalizeFilePathForKey(request.zipIdentifier)
                
                // 各ファイルごとに位置とハッシュを保存
                editor.putInt(KEY_FILE_POSITION_PREFIX + normalizedKey, request.imageIndex)
                editor.putString(KEY_FILE_HASH_PREFIX + normalizedKey, zipHash)
                
                // 後方互換性のため、最後のファイル情報も保存
                editor.putString(KEY_LAST_ZIP_IDENTIFIER, request.zipIdentifier)
                editor.putInt(KEY_LAST_IMAGE_INDEX, request.imageIndex)
                editor.putString(KEY_LAST_ZIP_HASH, zipHash)
                
                android.util.Log.d("ImageCacheManager", 
                    "Batch saving position - File: ${request.zipIdentifier}, Index: ${request.imageIndex}")
            }
            
            // 一括コミット（非同期）
            editor.apply()
            
            android.util.Log.d("ImageCacheManager", 
                "Batch saved ${requestsToProcess.size} positions successfully")
            
        } catch (e: Exception) {
            android.util.Log.e("ImageCacheManager", 
                "Batch save failed: ${e.message}", e)
        }
    }
    
    /**
     * ファイル識別子を生成（エンコーディング統一）
     */
    private fun generateFileIdentifier(zipUri: Uri, zipFile: File?): String {
        return try {
            when {
                zipFile != null && zipFile.exists() -> {
                    zipFile.absolutePath
                }
                zipUri.scheme == "file" -> {
                    val path = zipUri.path ?: zipUri.toString()
                    File(path).absolutePath
                }
                else -> {
                    zipUri.toString()
                }
            }
        } catch (e: Exception) {
            zipUri.toString()
        }
    }
    
    /**
     * ファイルパスを正規化してキー用に使用
     */
    private fun normalizeFilePathForKey(filePath: String): String {
        return filePath
            .replace("\\", "/")
            .replace("//+".toRegex(), "/")
            .removeSuffix("/")
    }
    
    /**
     * キャッシュ機能の有効/無効を設定
     */
    fun setCacheEnabled(enabled: Boolean) {
        _cacheEnabled.value = enabled
        batchScope.launch {
            sharedPrefs.edit().putBoolean(KEY_CACHE_ENABLED, enabled).apply()
        }
    }
    
    /**
     * ファイル削除時にキャッシュをクリアするかの設定
     */
    fun setClearCacheOnDelete(clearOnDelete: Boolean) {
        _clearCacheOnDelete.value = clearOnDelete
        batchScope.launch {
            sharedPrefs.edit().putBoolean(KEY_CLEAR_CACHE_ON_DELETE, clearOnDelete).apply()
        }
    }
    
    /**
     * スワイプ方向を逆転するかの設定
     */
    fun setReverseSwipeDirection(reverse: Boolean) {
        _reverseSwipeDirection.value = reverse
        batchScope.launch {
            sharedPrefs.edit().putBoolean(KEY_REVERSE_SWIPE_DIRECTION, reverse).apply()
        }
    }
    
    /**
     * 現在の表示位置を保存（バッチ処理）
     */
    fun saveCurrentPosition(zipUri: Uri, imageIndex: Int, zipFile: File? = null) {
        if (!_cacheEnabled.value) return
        
        val zipIdentifier = generateFileIdentifier(zipUri, zipFile)
        val request = PositionSaveRequest(zipIdentifier, zipUri, imageIndex, zipFile)
        
        batchScope.launch {
            positionSaveChannel.send(request)
        }
    }
    
    /**
     * 即座に位置を保存（緊急時用）
     */
    suspend fun saveCurrentPositionImmediately(zipUri: Uri, imageIndex: Int, zipFile: File? = null) {
        if (!_cacheEnabled.value) return
        
        val zipIdentifier = generateFileIdentifier(zipUri, zipFile)
        val zipHash = generateZipHash(zipUri, zipFile)
        val normalizedKey = normalizeFilePathForKey(zipIdentifier)
        
        withContext(Dispatchers.IO) {
            sharedPrefs.edit().apply {
                putInt(KEY_FILE_POSITION_PREFIX + normalizedKey, imageIndex)
                putString(KEY_FILE_HASH_PREFIX + normalizedKey, zipHash)
                putString(KEY_LAST_ZIP_IDENTIFIER, zipIdentifier)
                putInt(KEY_LAST_IMAGE_INDEX, imageIndex)
                putString(KEY_LAST_ZIP_HASH, zipHash)
                apply()
            }
        }
        
        android.util.Log.d("ImageCacheManager", 
            "Immediately saved position - File: $zipIdentifier, Index: $imageIndex")
    }
    
    /**
     * 保存された表示位置を取得（高速化版）
     */
    fun getSavedPosition(zipUri: Uri, zipFile: File? = null): Int? {
        if (!_cacheEnabled.value) return null
        
        val currentZipIdentifier = generateFileIdentifier(zipUri, zipFile)
        val currentZipHash = generateZipHash(zipUri, zipFile)
        val normalizedKey = normalizeFilePathForKey(currentZipIdentifier)
        
        android.util.Log.d("ImageCacheManager", 
            "Getting saved position - File: $currentZipIdentifier, Key: $normalizedKey")
        
        // 各ファイルごとの保存データを確認
        val savedPosition = sharedPrefs.getInt(KEY_FILE_POSITION_PREFIX + normalizedKey, -1)
        val savedHash = sharedPrefs.getString(KEY_FILE_HASH_PREFIX + normalizedKey, null)
        
        android.util.Log.d("ImageCacheManager", 
            "Found position: $savedPosition, hash match: ${savedHash == currentZipHash}")
        
        if (savedPosition >= 0 && savedHash == currentZipHash) {
            android.util.Log.d("ImageCacheManager", 
                "Returning saved position: $savedPosition")
            return savedPosition
        }
        
        // フォールバック: 古い形式のデータをチェック
        val savedZipIdentifier = sharedPrefs.getString(KEY_LAST_ZIP_IDENTIFIER, null)
        val savedZipHash = sharedPrefs.getString(KEY_LAST_ZIP_HASH, null)
        
        return if (savedZipIdentifier == currentZipIdentifier && savedZipHash == currentZipHash) {
            val savedIndex = sharedPrefs.getInt(KEY_LAST_IMAGE_INDEX, 0)
            android.util.Log.d("ImageCacheManager", 
                "Returning fallback position: $savedIndex")
            if (savedIndex >= 0) savedIndex else null
        } else {
            android.util.Log.d("ImageCacheManager", 
                "No valid saved position found")
            null
        }
    }
    
    /**
     * 未保存の位置をフラッシュ（アプリ終了時など）
     */
    suspend fun flushPendingPositions() {
        batchSaveJob?.join() // 現在のバッチジョブの完了を待機
        if (pendingPositions.isNotEmpty()) {
            processBatchSave() // 残りの位置を保存
        }
    }
    
    /**
     * キャッシュデータをクリア
     */
    fun clearCache() {
        batchScope.launch {
            // 未保存の位置をクリア
            pendingPositions.clear()
            batchSaveJob?.cancel()
            
            sharedPrefs.edit().apply {
                remove(KEY_LAST_ZIP_IDENTIFIER)
                remove("last_zip_uri")
                remove(KEY_LAST_IMAGE_INDEX)
                remove(KEY_LAST_ZIP_HASH)
                
                val allKeys = sharedPrefs.all.keys
                allKeys.forEach { key ->
                    if (key.startsWith(KEY_FILE_POSITION_PREFIX) || key.startsWith(KEY_FILE_HASH_PREFIX)) {
                        remove(key)
                    }
                }
                apply()
            }
        }
    }
    
    /**
     * 特定のZIPファイルのキャッシュをクリア
     */
    fun clearCacheForZip(zipUri: Uri, zipFile: File? = null) {
        if (!_cacheEnabled.value) return
        
        val currentZipIdentifier = generateFileIdentifier(zipUri, zipFile)
        val normalizedKey = normalizeFilePathForKey(currentZipIdentifier)
        
        batchScope.launch {
            // 未保存の位置から削除
            pendingPositions.remove(currentZipIdentifier)
            
            sharedPrefs.edit().apply {
                remove(KEY_FILE_POSITION_PREFIX + normalizedKey)
                remove(KEY_FILE_HASH_PREFIX + normalizedKey)
                
                val savedZipIdentifier = sharedPrefs.getString(KEY_LAST_ZIP_IDENTIFIER, null)
                if (savedZipIdentifier == currentZipIdentifier) {
                    remove(KEY_LAST_ZIP_IDENTIFIER)
                    remove(KEY_LAST_IMAGE_INDEX)
                    remove(KEY_LAST_ZIP_HASH)
                }
                apply()
            }
        }
    }
    
    /**
     * ファイル削除時の処理
     */
    fun onFileDeleted(zipUri: Uri, zipFile: File? = null) {
        if (_clearCacheOnDelete.value) {
            clearCacheForZip(zipUri, zipFile)
        }
    }
    
    /**
     * ZIPファイルのハッシュを生成（ファイルサイズと更新日時から）
     */
    private fun generateZipHash(zipUri: Uri, zipFile: File?): String {
        return try {
            when {
                zipFile != null && zipFile.exists() -> {
                    "${zipFile.length()}_${zipFile.lastModified()}"
                }
                zipUri.scheme == "file" -> {
                    val file = File(zipUri.path ?: "")
                    if (file.exists()) {
                        "${file.length()}_${file.lastModified()}"
                    } else {
                        generateFileIdentifier(zipUri, zipFile)
                    }
                }
                else -> {
                    generateFileIdentifier(zipUri, zipFile)
                }
            }
        } catch (e: Exception) {
            generateFileIdentifier(zipUri, zipFile)
        }
    }
    
    /**
     * 設定の概要を取得
     */
    fun getSettingsSummary(): String {
        val cacheStatus = if (_cacheEnabled.value) "有効" else "無効"
        val deleteStatus = if (_clearCacheOnDelete.value) "削除する" else "保持する"
        val swipeStatus = if (_reverseSwipeDirection.value) "逆転" else "標準"
        val pendingCount = pendingPositions.size
        return "キャッシュ機能: $cacheStatus\nファイル削除時: $deleteStatus\nスワイプ方向: $swipeStatus\n未保存位置: $pendingCount"
    }
    
    /**
     * リソースのクリーンアップ
     */
    fun cleanup() {
        batchScope.cancel()
        pendingPositions.clear()
    }
}