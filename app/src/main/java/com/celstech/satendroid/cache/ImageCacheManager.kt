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
        private const val KEY_LAST_ZIP_URI = "last_zip_uri"
        private const val KEY_LAST_IMAGE_INDEX = "last_image_index"
        private const val KEY_LAST_ZIP_HASH = "last_zip_hash"
        private const val KEY_REVERSE_SWIPE_DIRECTION = "reverse_swipe_direction"
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
        
        val zipHash = generateZipHash(zipUri, zipFile)
        
        sharedPrefs.edit().apply {
            putString(KEY_LAST_ZIP_URI, zipUri.toString())
            putInt(KEY_LAST_IMAGE_INDEX, imageIndex)
            putString(KEY_LAST_ZIP_HASH, zipHash)
            apply()
        }
    }
    
    /**
     * 保存された表示位置を取得
     */
    fun getSavedPosition(zipUri: Uri, zipFile: File? = null): Int? {
        if (!_cacheEnabled.value) return null
        
        val savedZipUri = sharedPrefs.getString(KEY_LAST_ZIP_URI, null)
        val savedZipHash = sharedPrefs.getString(KEY_LAST_ZIP_HASH, null)
        val currentZipHash = generateZipHash(zipUri, zipFile)
        
        return if (savedZipUri == zipUri.toString() && savedZipHash == currentZipHash) {
            val savedIndex = sharedPrefs.getInt(KEY_LAST_IMAGE_INDEX, 0)
            if (savedIndex >= 0) savedIndex else null
        } else {
            null
        }
    }
    
    /**
     * キャッシュデータをクリア
     */
    fun clearCache() {
        sharedPrefs.edit().apply {
            remove(KEY_LAST_ZIP_URI)
            remove(KEY_LAST_IMAGE_INDEX)
            remove(KEY_LAST_ZIP_HASH)
            apply()
        }
    }
    
    /**
     * 特定のZIPファイルのキャッシュをクリア
     */
    fun clearCacheForZip(zipUri: Uri, zipFile: File? = null) {
        if (!_cacheEnabled.value) return
        
        val savedZipUri = sharedPrefs.getString(KEY_LAST_ZIP_URI, null)
        val savedZipHash = sharedPrefs.getString(KEY_LAST_ZIP_HASH, null)
        val currentZipHash = generateZipHash(zipUri, zipFile)
        
        if (savedZipUri == zipUri.toString() && savedZipHash == currentZipHash) {
            clearCache()
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
                        zipUri.toString()
                    }
                }
                else -> {
                    // Content URIの場合は、URIそのものをハッシュとして使用
                    zipUri.toString()
                }
            }
        } catch (e: Exception) {
            zipUri.toString()
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