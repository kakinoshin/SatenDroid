package com.celstech.satendroid.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.celstech.satendroid.ui.models.ReadingStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * シンプル化された読書データ管理クラス
 * 
 * 特徴:
 * - 単一データクラスで全情報管理
 * - 即座保存（バッチ処理なし）
 * - JSONシリアライズで1キー1ファイル
 * - 単一キャッシュレイヤー
 */
class SimpleReadingDataManager(private val context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "simple_reading_data", 
        Context.MODE_PRIVATE
    )
    
    // 設定関連のStateFlow
    private val _reverseSwipeDirection = MutableStateFlow(prefs.getBoolean(KEY_REVERSE_SWIPE_DIRECTION, false))
    val reverseSwipeDirection: StateFlow<Boolean> = _reverseSwipeDirection.asStateFlow()
    
    // メモリキャッシュ（単一キャッシュレイヤー）
    private val cache = ConcurrentHashMap<String, FileReadingData>()
    
    // JSON serializer
    private val json = Json { 
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    
    companion object {
        private const val KEY_REVERSE_SWIPE_DIRECTION = "reverse_swipe_direction"
        private const val DATA_PREFIX = "file_data_"
        
        // 移行完了フラグ
        private const val MIGRATION_FROM_UNIFIED_COMPLETED = "migration_from_unified_completed"
    }
    
    init {
        // 統合システムからの移行処理
        if (!prefs.getBoolean(MIGRATION_FROM_UNIFIED_COMPLETED, false)) {
            migrateFromUnifiedSystem()
        }
    }
    
    /**
     * ファイルパスを正規化
     */
    private fun normalizeFilePath(filePath: String): String {
        return filePath
            .replace("\\", "/")
            .replace("//+".toRegex(), "/")
            .removeSuffix("/")
    }
    
    /**
     * ストレージキーを生成
     */
    private fun getStorageKey(filePath: String): String {
        return DATA_PREFIX + normalizeFilePath(filePath).hashCode()
    }
    
    /**
     * 読書データを保存（即座保存）
     */
    fun saveReadingData(
        filePath: String,
        currentPage: Int,
        totalPages: Int,
        status: ReadingStatus = determineStatus(currentPage, totalPages)
    ) {
        val normalizedPath = normalizeFilePath(filePath)
        
        val data = FileReadingData(
            filePath = normalizedPath,
            currentPage = currentPage,
            totalPages = totalPages,
            status = status,
            lastUpdated = System.currentTimeMillis()
        )
        
        // メモリキャッシュ更新
        cache[normalizedPath] = data
        
        // 即座にSharedPreferencesに保存
        val key = getStorageKey(normalizedPath)
        val jsonString = json.encodeToString(data)
        
        prefs.edit {
            putString(key, jsonString)
        }
        
        println("DEBUG: SimpleReadingDataManager - Saved data for ${File(filePath).name}")
        println("DEBUG:   CurrentPage: $currentPage, TotalPages: $totalPages, Status: $status")
    }
    
    /**
     * 読書データを取得
     */
    fun getReadingData(filePath: String): FileReadingData {
        val normalizedPath = normalizeFilePath(filePath)
        
        // メモリキャッシュから取得
        cache[normalizedPath]?.let { return it }
        
        // SharedPreferencesから読み込み
        val key = getStorageKey(normalizedPath)
        val jsonString = prefs.getString(key, null)
        
        val data = if (jsonString != null) {
            try {
                json.decodeFromString<FileReadingData>(jsonString)
            } catch (e: Exception) {
                println("ERROR: Failed to decode JSON for $filePath: ${e.message}")
                // デフォルトデータを作成
                FileReadingData(
                    filePath = normalizedPath,
                    currentPage = 0,
                    totalPages = 0,
                    status = ReadingStatus.UNREAD,
                    lastUpdated = System.currentTimeMillis()
                )
            }
        } else {
            // データが存在しない場合のデフォルト
            FileReadingData(
                filePath = normalizedPath,
                currentPage = 0,
                totalPages = 0,
                status = ReadingStatus.UNREAD,
                lastUpdated = System.currentTimeMillis()
            )
        }
        
        // メモリキャッシュに保存
        cache[normalizedPath] = data
        
        return data
    }
    
    /**
     * 読書進捗情報を取得（互換性用）
     */
    fun getReadingProgress(filePath: String): ReadingProgress {
        val data = getReadingData(filePath)
        return ReadingProgress(
            status = data.status,
            currentIndex = data.currentPage
        )
    }
    
    /**
     * 現在のページ位置を取得
     */
    fun getCurrentPage(filePath: String): Int {
        return getReadingData(filePath).currentPage
    }
    
    /**
     * 総ページ数を取得
     */
    fun getTotalPages(filePath: String): Int {
        return getReadingData(filePath).totalPages
    }
    
    /**
     * 読書状況を取得
     */
    fun getReadingStatus(filePath: String): ReadingStatus {
        return getReadingData(filePath).status
    }
    
    /**
     * ファイルを削除時のデータクリア
     */
    fun clearFileData(filePath: String) {
        val normalizedPath = normalizeFilePath(filePath)
        
        // メモリキャッシュから削除
        cache.remove(normalizedPath)
        
        // SharedPreferencesから削除
        val key = getStorageKey(normalizedPath)
        prefs.edit {
            remove(key)
        }
        
        println("DEBUG: SimpleReadingDataManager - Cleared data for ${File(filePath).name}")
    }
    
    /**
     * フォルダー内のファイルデータをクリア
     */
    fun clearFolderData(folderPath: String) {
        val normalizedFolderPath = normalizeFilePath(folderPath)
        
        // メモリキャッシュから該当ファイルを削除
        val keysToRemove = cache.keys.filter { filePath ->
            filePath.contains(normalizedFolderPath)
        }
        
        keysToRemove.forEach { key ->
            cache.remove(key)
        }
        
        // SharedPreferencesから該当ファイルを削除
        val allKeys = prefs.all.keys
        val storageKeysToRemove = allKeys.filter { key ->
            if (key.startsWith(DATA_PREFIX)) {
                try {
                    val jsonString = prefs.getString(key, null)
                    if (jsonString != null) {
                        val data = json.decodeFromString<FileReadingData>(jsonString)
                        data.filePath.contains(normalizedFolderPath)
                    } else {
                        false
                    }
                } catch (e: Exception) {
                    false
                }
            } else {
                false
            }
        }
        
        if (storageKeysToRemove.isNotEmpty()) {
            prefs.edit {
                storageKeysToRemove.forEach { key ->
                    remove(key)
                }
            }
        }
        
        println("DEBUG: SimpleReadingDataManager - Cleared ${keysToRemove.size} files for folder: $folderPath")
    }
    
    /**
     * 全データクリア
     */
    fun clearAllData() {
        cache.clear()
        
        // データのみクリア（設定は保持）
        val allKeys = prefs.all.keys
        val dataKeys = allKeys.filter { it.startsWith(DATA_PREFIX) }
        
        prefs.edit {
            dataKeys.forEach { key ->
                remove(key)
            }
        }
        
        println("DEBUG: SimpleReadingDataManager - Cleared all reading data")
    }
    
    /**
     * 読書状況を自動判定
     */
    private fun determineStatus(currentPage: Int, totalPages: Int): ReadingStatus {
        return when {
            currentPage < 0 -> ReadingStatus.UNREAD
            totalPages <= 0 -> ReadingStatus.UNREAD
            currentPage >= totalPages - 1 -> ReadingStatus.COMPLETED
            currentPage > 0 -> ReadingStatus.READING
            else -> ReadingStatus.UNREAD
        }
    }
    
    /**
     * スワイプ方向設定
     */
    fun setReverseSwipeDirection(reverse: Boolean) {
        _reverseSwipeDirection.value = reverse
        prefs.edit {
            putBoolean(KEY_REVERSE_SWIPE_DIRECTION, reverse)
        }
    }
    
    /**
     * 統合システムからの移行処理
     */
    private fun migrateFromUnifiedSystem() {
        try {
            println("DEBUG: Starting migration from UnifiedReadingDataManager...")
            
            val unifiedPrefs = context.getSharedPreferences("unified_reading_data", Context.MODE_PRIVATE)
            val allKeys = unifiedPrefs.all.keys
            
            // ファイルパスを抽出してグループ化
            val fileDataMap = mutableMapOf<String, MutableMap<String, Any>>()
            
            allKeys.forEach { key ->
                when {
                    key.startsWith("status_") -> {
                        val filePath = key.removePrefix("status_")
                        val statusString = unifiedPrefs.getString(key, "UNREAD") ?: "UNREAD"
                        fileDataMap.getOrPut(filePath) { mutableMapOf() }["status"] = statusString
                    }
                    key.startsWith("position_") -> {
                        val filePath = key.removePrefix("position_")
                        val position = unifiedPrefs.getInt(key, 0)
                        fileDataMap.getOrPut(filePath) { mutableMapOf() }["position"] = position
                    }
                    key.startsWith("total_") -> {
                        val filePath = key.removePrefix("total_")
                        val total = unifiedPrefs.getInt(key, 0)
                        fileDataMap.getOrPut(filePath) { mutableMapOf() }["total"] = total
                    }
                    key.startsWith("updated_") -> {
                        val filePath = key.removePrefix("updated_")
                        val updated = unifiedPrefs.getLong(key, System.currentTimeMillis())
                        fileDataMap.getOrPut(filePath) { mutableMapOf() }["updated"] = updated
                    }
                    key == "reverse_swipe_direction" -> {
                        val reverse = unifiedPrefs.getBoolean(key, false)
                        setReverseSwipeDirection(reverse)
                    }
                }
            }
            
            // 新システムに変換して保存
            var migratedCount = 0
            fileDataMap.forEach { (filePath, dataMap) ->
                val status = try {
                    ReadingStatus.valueOf(dataMap["status"] as? String ?: "UNREAD")
                } catch (e: Exception) {
                    ReadingStatus.UNREAD
                }
                
                val position = dataMap["position"] as? Int ?: 0
                val total = dataMap["total"] as? Int ?: 0
                val updated = dataMap["updated"] as? Long ?: System.currentTimeMillis()
                
                val newData = FileReadingData(
                    filePath = normalizeFilePath(filePath),
                    currentPage = position,
                    totalPages = total,
                    status = status,
                    lastUpdated = updated
                )
                
                // 新システムに保存
                val key = getStorageKey(newData.filePath)
                val jsonString = json.encodeToString(newData)
                prefs.edit {
                    putString(key, jsonString)
                }
                
                // メモリキャッシュにも保存
                cache[newData.filePath] = newData
                
                migratedCount++
            }
            
            // 移行完了をマーク
            prefs.edit {
                putBoolean(MIGRATION_FROM_UNIFIED_COMPLETED, true)
            }
            
            println("DEBUG: Migration completed - $migratedCount files migrated")
            
        } catch (e: Exception) {
            println("ERROR: Migration failed: ${e.message}")
            e.printStackTrace()
            
            // 失敗してもフラグを立てて無限ループを防ぐ
            prefs.edit {
                putBoolean(MIGRATION_FROM_UNIFIED_COMPLETED, true)
            }
        }
    }
    
    /**
     * デバッグ用：指定ファイルのデータを表示
     */
    fun debugPrintFileData(filePath: String) {
        val data = getReadingData(filePath)
        println("=== SimpleReadingDataManager Debug ===")
        println("File: ${File(filePath).name}")
        println("Normalized Path: ${data.filePath}")
        println("Current Page: ${data.currentPage}")
        println("Total Pages: ${data.totalPages}")
        println("Status: ${data.status}")
        println("Last Updated: ${data.lastUpdated}")
        println("Cache Hit: ${cache.containsKey(normalizeFilePath(filePath))}")
        println("=== End Debug ===")
    }
}

/**
 * 単一データクラスで全情報管理
 */
@Serializable
data class FileReadingData(
    val filePath: String,           // 正規化されたファイルパス
    val currentPage: Int,           // 現在のページ（0ベース）
    val totalPages: Int,            // 総ページ数
    val status: ReadingStatus,      // 読書状況
    val lastUpdated: Long           // 最終更新時刻
) {
    /**
     * 読書進捗率（0.0 - 1.0）
     */
    val progressRatio: Float
        get() = if (totalPages > 0) (currentPage + 1).toFloat() / totalPages.toFloat() else 0f
    
    /**
     * 人間が読みやすい進捗文字列
     */
    val progressText: String
        get() = when (status) {
            ReadingStatus.UNREAD -> "未読"
            ReadingStatus.READING -> {
                if (totalPages > 0) {
                    "読書中 ${currentPage + 1}/$totalPages"
                } else {
                    "読書中"
                }
            }
            ReadingStatus.COMPLETED -> "読了"
        }
}
