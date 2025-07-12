package com.celstech.satendroid.utils

import android.content.Context
import android.content.SharedPreferences

/**
 * ZIP ファイルアクセス方式の設定管理
 */
enum class ZipAccessMode {
    /** 従来方式：テンポラリフォルダに展開 */
    EXTRACTION,
    /** 新方式：直接アクセス */
    DIRECT_ACCESS
}

/**
 * ZIP アクセス方式の設定管理クラス
 */
class ZipAccessModeManager(private val context: Context) {
    
    companion object {
        private const val PREFS_NAME = "zip_access_settings"
        private const val KEY_ACCESS_MODE = "access_mode"
        // デフォルトは新しい直接アクセス方式
        private const val DEFAULT_MODE = "DIRECT_ACCESS"
    }
    
    private val prefs: SharedPreferences = 
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    /**
     * 現在のアクセス方式を取得
     */
    fun getCurrentMode(): ZipAccessMode {
        val modeName = prefs.getString(KEY_ACCESS_MODE, DEFAULT_MODE)
        return try {
            ZipAccessMode.valueOf(modeName ?: DEFAULT_MODE)
        } catch (e: IllegalArgumentException) {
            ZipAccessMode.DIRECT_ACCESS
        }
    }
    
    /**
     * アクセス方式を設定
     */
    fun setAccessMode(mode: ZipAccessMode) {
        prefs.edit()
            .putString(KEY_ACCESS_MODE, mode.name)
            .apply()
    }
    
    /**
     * 直接アクセス方式かどうかを判定
     */
    fun isDirectAccessMode(): Boolean {
        return getCurrentMode() == ZipAccessMode.DIRECT_ACCESS
    }
    
    /**
     * 展開方式かどうかを判定
     */
    fun isExtractionMode(): Boolean {
        return getCurrentMode() == ZipAccessMode.EXTRACTION
    }
    
    /**
     * アクセス方式の説明を取得
     */
    fun getCurrentModeDescription(): String {
        return when (getCurrentMode()) {
            ZipAccessMode.EXTRACTION -> "展開方式（テンポラリファイル使用）"
            ZipAccessMode.DIRECT_ACCESS -> "直接アクセス方式（メモリ効率的）"
        }
    }
    
    /**
     * アクセス方式を切り替え
     */
    fun toggleMode(): ZipAccessMode {
        val newMode = when (getCurrentMode()) {
            ZipAccessMode.EXTRACTION -> ZipAccessMode.DIRECT_ACCESS
            ZipAccessMode.DIRECT_ACCESS -> ZipAccessMode.EXTRACTION
        }
        setAccessMode(newMode)
        return newMode
    }
}
