package com.celstech.satendroid.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ダウンロード設定管理
 */
class DownloadSettings private constructor(context: Context) {
    
    companion object {
        private const val PREFS_NAME = "download_settings"
        private const val KEY_MAX_CONCURRENT_DOWNLOADS = "max_concurrent_downloads"
        private const val DEFAULT_MAX_CONCURRENT_DOWNLOADS = 2
        private const val MIN_CONCURRENT_DOWNLOADS = 1
        private const val MAX_CONCURRENT_DOWNLOADS = 8
        
        @Volatile
        private var INSTANCE: DownloadSettings? = null
        
        fun getInstance(context: Context): DownloadSettings {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DownloadSettings(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    // 最大同時ダウンロード数のStateFlow
    private val _maxConcurrentDownloads = MutableStateFlow(getMaxConcurrentDownloads())
    val maxConcurrentDownloads: StateFlow<Int> = _maxConcurrentDownloads.asStateFlow()
    
    /**
     * 最大同時ダウンロード数を取得
     */
    fun getMaxConcurrentDownloads(): Int {
        return prefs.getInt(KEY_MAX_CONCURRENT_DOWNLOADS, DEFAULT_MAX_CONCURRENT_DOWNLOADS)
            .coerceIn(MIN_CONCURRENT_DOWNLOADS, MAX_CONCURRENT_DOWNLOADS)
    }
    
    /**
     * 最大同時ダウンロード数を設定
     */
    fun setMaxConcurrentDownloads(count: Int) {
        val validCount = count.coerceIn(MIN_CONCURRENT_DOWNLOADS, MAX_CONCURRENT_DOWNLOADS)
        prefs.edit().putInt(KEY_MAX_CONCURRENT_DOWNLOADS, validCount).apply()
        _maxConcurrentDownloads.value = validCount
        println("DEBUG: DownloadSettings - Max concurrent downloads set to: $validCount")
    }
    
    /**
     * 最小値を取得
     */
    fun getMinConcurrentDownloads(): Int = MIN_CONCURRENT_DOWNLOADS
    
    /**
     * 最大値を取得
     */
    fun getMaxConcurrentDownloadsLimit(): Int = MAX_CONCURRENT_DOWNLOADS
    
    /**
     * デフォルト値を取得
     */
    fun getDefaultConcurrentDownloads(): Int = DEFAULT_MAX_CONCURRENT_DOWNLOADS
    
    /**
     * 設定をリセット
     */
    fun resetToDefaults() {
        setMaxConcurrentDownloads(DEFAULT_MAX_CONCURRENT_DOWNLOADS)
    }
}
