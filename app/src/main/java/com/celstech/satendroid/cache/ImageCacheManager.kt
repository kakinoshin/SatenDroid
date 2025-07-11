package com.celstech.satendroid.cache

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * 画像キャッシュ管理クラス
 * 前回の表示位置と設定を管理する
 */
class ImageCacheManager(private val context: Context) {
    
    companion object {
        private const val PREFS_NAME = "image_cache_settings"
        private const val KEY_CACHE_ENABLED = "cache_enabled"
        private const val KEY_CLEAR_CACHE_ON_DELETE = "clear_cache_on_delete"
        private const val KEY_LAST_ZIP_IDENTIFIER = "last_zip_identifier"  // URI文字列からファイル識別子に変更
        private const val KEY_LAST_IMAGE_INDEX = "last_image_index"
        private const val KEY_LAST_ZIP_HASH = "last_zip_hash"
        private const val KEY_REVERSE_SWIPE_DIRECTION = "reverse_swipe_direction"
        // 各ファイルごとの位置保存用プレフィックス
        private const val KEY_FILE_POSITION_PREFIX = "position_"
        private const val KEY_FILE_HASH_PREFIX = "hash_"
    }
    
    private val sharedPrefs: SharedPreferences = 
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    private val _cacheEnabled = MutableStateFlow(sharedPrefs.getBoolean(KEY_CACHE_ENABLED, true))
    val cacheEnabled: StateFlow<Boolean> = _cacheEnabled.asStateFlow()
    
    private val _clearCacheOnDelete = MutableStateFlow(sharedPrefs.getBoolean(KEY_CLEAR_CACHE_ON_DELETE, true))
    val clearCacheOnDelete: StateFlow<Boolean> = _clearCacheOnDelete.asStateFlow()
    
    private val _reverseSwipeDirection = MutableStateFlow(sharedPrefs.getBoolean(KEY_REVERSE_SWIPE_DIRECTION, false))
    val reverseSwipeDirection: StateFlow<Boolean> = _reverseSwipeDirection.asStateFlow()
    
    /**
     * ファイル識別子を生成（エンコーディング統一）
     */
    private fun generateFileIdentifier(zipUri: Uri, zipFile: File?): String {
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
     * ファイルパスを正規化してキー用に使用
     */
    private fun normalizeFilePathForKey(filePath: String): String {
        return filePath
            .replace("\\", "/")  // バックスラッシュをスラッシュに統一
            .replace("//+".toRegex(), "/")  // 連続スラッシュを単一に
            .removeSuffix("/")  // 末尾のスラッシュを除去
    }
    
    /**
     * キャッシュ機能の有効/無効を設定
     */
    fun setCacheEnabled(enabled: Boolean) {
        _cacheEnabled.value = enabled
        sharedPrefs.edit().putBoolean(KEY_CACHE_ENABLED, enabled).apply()
    }
    
    /**
     * ファイル削除時にキャッシュをクリアするかの設定
     */
    fun setClearCacheOnDelete(clearOnDelete: Boolean) {
        _clearCacheOnDelete.value = clearOnDelete
        sharedPrefs.edit().putBoolean(KEY_CLEAR_CACHE_ON_DELETE, clearOnDelete).apply()
    }
    
    /**
     * スワイプ方向を逆転するかの設定
     */
    fun setReverseSwipeDirection(reverse: Boolean) {
        _reverseSwipeDirection.value = reverse
        sharedPrefs.edit().putBoolean(KEY_REVERSE_SWIPE_DIRECTION, reverse).apply()
    }
    
    /**
     * 現在の表示位置を保存
     */
    fun saveCurrentPosition(zipUri: Uri, imageIndex: Int, zipFile: File? = null) {
        if (!_cacheEnabled.value) return
        
        val zipIdentifier = generateFileIdentifier(zipUri, zipFile)
        val zipHash = generateZipHash(zipUri, zipFile)
        val normalizedKey = normalizeFilePathForKey(zipIdentifier)
        
        android.util.Log.d("ImageCacheManager", "Saving position - File: $zipIdentifier, Index: $imageIndex, Key: $normalizedKey")
        
        sharedPrefs.edit().apply {
            // 各ファイルごとに位置とハッシュを保存
            putInt(KEY_FILE_POSITION_PREFIX + normalizedKey, imageIndex)
            putString(KEY_FILE_HASH_PREFIX + normalizedKey, zipHash)
            
            // 後方互換性のため、最後のファイル情報も保存
            putString(KEY_LAST_ZIP_IDENTIFIER, zipIdentifier)
            putInt(KEY_LAST_IMAGE_INDEX, imageIndex)
            putString(KEY_LAST_ZIP_HASH, zipHash)
            apply()
        }
        
        android.util.Log.d("ImageCacheManager", "Position saved successfully")
    }
    
    /**
     * 保存された表示位置を取得
     */
    fun getSavedPosition(zipUri: Uri, zipFile: File? = null): Int? {
        if (!_cacheEnabled.value) return null
        
        val currentZipIdentifier = generateFileIdentifier(zipUri, zipFile)
        val currentZipHash = generateZipHash(zipUri, zipFile)
        val normalizedKey = normalizeFilePathForKey(currentZipIdentifier)
        
        android.util.Log.d("ImageCacheManager", "Getting saved position - File: $currentZipIdentifier, Key: $normalizedKey")
        
        // 各ファイルごとの保存データを確認
        val savedPosition = sharedPrefs.getInt(KEY_FILE_POSITION_PREFIX + normalizedKey, -1)
        val savedHash = sharedPrefs.getString(KEY_FILE_HASH_PREFIX + normalizedKey, null)
        
        android.util.Log.d("ImageCacheManager", "Found position: $savedPosition, hash match: ${savedHash == currentZipHash}")
        
        if (savedPosition >= 0 && savedHash == currentZipHash) {
            android.util.Log.d("ImageCacheManager", "Returning saved position: $savedPosition")
            return savedPosition
        }
        
        // フォールバック: 古い形式のデータをチェック
        val savedZipIdentifier = sharedPrefs.getString(KEY_LAST_ZIP_IDENTIFIER, null)
        val savedZipHash = sharedPrefs.getString(KEY_LAST_ZIP_HASH, null)
        
        android.util.Log.d("ImageCacheManager", "Fallback check - identifier match: ${savedZipIdentifier == currentZipIdentifier}, hash match: ${savedZipHash == currentZipHash}")
        
        return if (savedZipIdentifier == currentZipIdentifier && savedZipHash == currentZipHash) {
            val savedIndex = sharedPrefs.getInt(KEY_LAST_IMAGE_INDEX, 0)
            android.util.Log.d("ImageCacheManager", "Returning fallback position: $savedIndex")
            if (savedIndex >= 0) savedIndex else null
        } else {
            android.util.Log.d("ImageCacheManager", "No valid saved position found")
            null
        }
    }
    
    /**
     * キャッシュデータをクリア
     */
    fun clearCache() {
        sharedPrefs.edit().apply {
            // 古いキーをクリア
            remove(KEY_LAST_ZIP_IDENTIFIER)
            remove("last_zip_uri")  // 古いキーも削除
            remove(KEY_LAST_IMAGE_INDEX)
            remove(KEY_LAST_ZIP_HASH)
            
            // 各ファイルごとのキーをクリア
            val allKeys = sharedPrefs.all.keys
            allKeys.forEach { key ->
                if (key.startsWith(KEY_FILE_POSITION_PREFIX) || key.startsWith(KEY_FILE_HASH_PREFIX)) {
                    remove(key)
                }
            }
            apply()
        }
    }
    
    /**
     * 特定のZIPファイルのキャッシュをクリア
     */
    fun clearCacheForZip(zipUri: Uri, zipFile: File? = null) {
        if (!_cacheEnabled.value) return
        
        val currentZipIdentifier = generateFileIdentifier(zipUri, zipFile)
        val normalizedKey = normalizeFilePathForKey(currentZipIdentifier)
        
        sharedPrefs.edit().apply {
            // 各ファイルごとのデータをクリア
            remove(KEY_FILE_POSITION_PREFIX + normalizedKey)
            remove(KEY_FILE_HASH_PREFIX + normalizedKey)
            
            // 最後のファイルが該当する場合はそのデータもクリア
            val savedZipIdentifier = sharedPrefs.getString(KEY_LAST_ZIP_IDENTIFIER, null)
            if (savedZipIdentifier == currentZipIdentifier) {
                remove(KEY_LAST_ZIP_IDENTIFIER)
                remove(KEY_LAST_IMAGE_INDEX)
                remove(KEY_LAST_ZIP_HASH)
            }
            apply()
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
                    // Content URIの場合は、識別子をハッシュとして使用
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
        return "キャッシュ機能: $cacheStatus\nファイル削除時: $deleteStatus\nスワイプ方向: $swipeStatus"
    }
}