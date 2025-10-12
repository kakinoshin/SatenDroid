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
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 既読情報管理システム - 再設計版
 *
 * 設計原則:
 * 1. 単一責任: 状態管理と永続化のみ
 * 2. 同期保存: commit()のみ使用
 * 3. 単一キャッシュ: メモリキャッシュが唯一の真実の源
 * 4. シンプル: 不要な機能は削除
 */
class ReadingStateManager(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "reading_states_v2",
        Context.MODE_PRIVATE
    )

    // 単一メモリキャッシュ（唯一の真実の源）
    private val cache = ConcurrentHashMap<String, ReadingState>()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // キャッシュ更新トリガー（UIの再描画用）
    private val _cacheUpdateTrigger = MutableStateFlow(0L)
    val cacheUpdateTrigger: StateFlow<Long> = _cacheUpdateTrigger.asStateFlow()

    // 設定関連のStateFlow
    private val _reverseSwipeDirection = MutableStateFlow(prefs.getBoolean(KEY_REVERSE_SWIPE_DIRECTION, false))
    val reverseSwipeDirection: StateFlow<Boolean> = _reverseSwipeDirection.asStateFlow()

    private val _cacheEnabled = MutableStateFlow(prefs.getBoolean(KEY_CACHE_ENABLED, true))
    val cacheEnabled: StateFlow<Boolean> = _cacheEnabled.asStateFlow()

    companion object {
        private const val KEY_PREFIX = "state_"
        private const val KEY_REVERSE_SWIPE_DIRECTION = "reverse_swipe_direction"
        private const val KEY_CACHE_ENABLED = "cache_enabled"
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
        return KEY_PREFIX + normalizeFilePath(filePath).hashCode()
    }

    /**
     * 読書状態をメモリキャッシュに読み込む
     * リスト表示時に一括で呼び出す
     * リフレッシュ時は常に最新データで上書き
     */
    fun loadStateToCache(filePath: String) {
        val normalizedPath = normalizeFilePath(filePath)
        
        // 常に最新データを読み込む（キャッシュチェックを削除）
        val state = loadFromStorage(normalizedPath)
        cache[normalizedPath] = state
        
        // キャッシュ更新を通知（UIの再描画トリガー）
        _cacheUpdateTrigger.value = System.currentTimeMillis()
        
        println("DEBUG: Loaded state to cache - File: ${File(filePath).name}, Status: ${state.status}, Page: ${state.currentPage}/${state.totalPages}")
    }

    /**
     * Storageから読書状態を読み込む（内部用）
     */
    private fun loadFromStorage(normalizedPath: String): ReadingState {
        val key = getStorageKey(normalizedPath)
        val jsonString = prefs.getString(key, null)

        return if (jsonString != null) {
            try {
                json.decodeFromString<ReadingState>(jsonString)
            } catch (e: Exception) {
                println("ERROR: Failed to decode reading state: ${e.message}")
                createDefaultState()
            }
        } else {
            createDefaultState()
        }
    }

    /**
     * デフォルト状態を作成
     */
    private fun createDefaultState(): ReadingState {
        return ReadingState(
            status = ReadingStatus.UNREAD,
            currentPage = 0,
            totalPages = 0,
            lastUpdated = System.currentTimeMillis()
        )
    }

    /**
     * 読書状態を取得（メモリキャッシュから）
     * UIはこのメソッドを使用する
     */
    fun getState(filePath: String): ReadingState {
        val normalizedPath = normalizeFilePath(filePath)
        
        // メモリキャッシュから取得
        val cachedState = cache[normalizedPath]
        if (cachedState != null) {
            return cachedState
        }

        // キャッシュにない場合はStorageから読み込んでキャッシュに保存
        val state = loadFromStorage(normalizedPath)
        cache[normalizedPath] = state
        return state
    }

    /**
     * 現在のページを更新（RAM上のみ、保存はしない）
     * ページ移動時に呼び出す
     */
    fun updateCurrentPage(filePath: String, page: Int, totalPages: Int) {
        val normalizedPath = normalizeFilePath(filePath)
        val currentState = cache[normalizedPath] ?: loadFromStorage(normalizedPath)

        val newState = currentState.copy(
            currentPage = page,
            totalPages = totalPages,
            status = determineStatus(page, totalPages),
            lastUpdated = System.currentTimeMillis(),
            filePath = normalizedPath
        )

        cache[normalizedPath] = newState
        
        // キャッシュ更新を通知（UIの再描画トリガー）
        _cacheUpdateTrigger.value = System.currentTimeMillis()
        
        println("DEBUG: Updated page in RAM - File: ${File(filePath).name}, Page: $page/$totalPages, Status: ${newState.status}")
    }

    /**
     * 読書状態を同期保存（ファイルを閉じる時のみ呼び出す）
     * 
     * @return 保存成功時はResult.success、失敗時はResult.failure
     */
    fun saveStateSync(filePath: String): Result<Unit> {
        return try {
            val normalizedPath = normalizeFilePath(filePath)
            val state = cache[normalizedPath] ?: return Result.success(Unit) // キャッシュにない場合は何もしない

            val key = getStorageKey(normalizedPath)
            val stateToSave = state.copy(filePath = normalizedPath)
            val jsonString = json.encodeToString(stateToSave)

            // commit()を使用して同期保存
            val success = prefs.edit().let { editor ->
                editor.putString(key, jsonString)
                editor.commit()
            }

            if (success) {
                // キャッシュ更新を通知（UIの再描画トリガー）
                _cacheUpdateTrigger.value = System.currentTimeMillis()
                
                println("DEBUG: Saved state (SYNC) - File: ${File(filePath).name}, Status: ${state.status}, Page: ${state.currentPage}/${state.totalPages}")
                Result.success(Unit)
            } else {
                val error = Exception("Failed to commit reading state to SharedPreferences")
                println("ERROR: ${error.message}")
                Result.failure(error)
            }
        } catch (e: Exception) {
            println("ERROR: Exception during saveStateSync: ${e.message}")
            e.printStackTrace()
            Result.failure(e)
        }
    }

    /**
     * 読書状態を判定
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
     * ファイルの状態をクリア
     */
    fun clearFileState(filePath: String) {
        val normalizedPath = normalizeFilePath(filePath)
        
        // メモリキャッシュから削除
        cache.remove(normalizedPath)
        
        // Storageから削除
        val key = getStorageKey(normalizedPath)
        prefs.edit(commit = true) {
            remove(key)
        }
        
        // キャッシュ更新を通知（UIの再描画トリガー）
        _cacheUpdateTrigger.value = System.currentTimeMillis()
        
        println("DEBUG: Cleared state for ${File(filePath).name}")
    }

    /**
     * フォルダー内の全ファイル状態をクリア
     */
    fun clearFolderStates(folderPath: String) {
        val normalizedFolderPath = normalizeFilePath(folderPath)
        
        // メモリキャッシュから削除
        val keysToRemove = cache.keys.filter { it.contains(normalizedFolderPath) }
        keysToRemove.forEach { cache.remove(it) }
        
        // Storageから削除
        val allKeys = prefs.all.keys.filter { it.startsWith(KEY_PREFIX) }
        val storageKeysToRemove = mutableListOf<String>()
        
        allKeys.forEach { key ->
            try {
                val jsonString = prefs.getString(key, null)
                if (jsonString != null) {
                    val state = json.decodeFromString<ReadingState>(jsonString)
                    if (state.filePath.contains(normalizedFolderPath)) {
                        storageKeysToRemove.add(key)
                    }
                }
            } catch (e: Exception) {
                // 解析エラーの場合はスキップ
            }
        }
        
        if (storageKeysToRemove.isNotEmpty()) {
            prefs.edit(commit = true) {
                storageKeysToRemove.forEach { remove(it) }
            }
        }
        
        // キャッシュ更新を通知（UIの再描画トリガー）
        _cacheUpdateTrigger.value = System.currentTimeMillis()
        
        println("DEBUG: Cleared ${keysToRemove.size} states for folder: $folderPath")
    }

    /**
     * 全状態をクリア
     */
    fun clearAllStates() {
        cache.clear()
        
        val allKeys = prefs.all.keys.filter { it.startsWith(KEY_PREFIX) }
        prefs.edit(commit = true) {
            allKeys.forEach { remove(it) }
        }
        
        println("DEBUG: Cleared all reading states")
    }

    /**
     * メモリキャッシュをクリア（アプリ終了時など）
     */
    fun clearMemoryCache() {
        cache.clear()
        println("DEBUG: Cleared memory cache")
    }

    /**
     * スワイプ方向設定
     */
    fun setReverseSwipeDirection(reverse: Boolean) {
        _reverseSwipeDirection.value = reverse
        prefs.edit(commit = true) {
            putBoolean(KEY_REVERSE_SWIPE_DIRECTION, reverse)
        }
        println("DEBUG: Reverse swipe direction set to: $reverse")
    }

    /**
     * キャッシュ機能設定
     */
    fun setCacheEnabled(enabled: Boolean) {
        _cacheEnabled.value = enabled
        prefs.edit(commit = true) {
            putBoolean(KEY_CACHE_ENABLED, enabled)
        }
        println("DEBUG: Cache enabled set to: $enabled")
    }

    /**
     * デバッグ用: 状態を表示
     */
    fun debugPrintState(filePath: String) {
        val state = getState(filePath)
        println("=== ReadingStateManager Debug ===")
        println("File: ${File(filePath).name}")
        println("Path: ${state.filePath}")
        println("Status: ${state.status}")
        println("Current Page: ${state.currentPage}")
        println("Total Pages: ${state.totalPages}")
        println("Last Updated: ${state.lastUpdated}")
        println("In Cache: ${cache.containsKey(normalizeFilePath(filePath))}")
        println("=================================")
    }
}

/**
 * 読書状態データクラス
 */
@Serializable
data class ReadingState(
    val status: ReadingStatus,
    val currentPage: Int,
    val totalPages: Int,
    val lastUpdated: Long,
    val filePath: String = "" // シリアライズ時のみ使用
) {
    /**
     * 進捗率（0.0 - 1.0）
     */
    val progressRatio: Float
        get() = if (totalPages > 0) (currentPage + 1).toFloat() / totalPages else 0f

    /**
     * 進捗テキスト
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

    /**
     * 完了済みかどうか
     */
    val isCompleted: Boolean
        get() = status == ReadingStatus.COMPLETED

    /**
     * 読書中かどうか
     */
    val isReading: Boolean
        get() = status == ReadingStatus.READING

    /**
     * 未読かどうか
     */
    val isUnread: Boolean
        get() = status == ReadingStatus.UNREAD
}

/**
 * 読書進捗情報（互換性用）
 */
data class ReadingProgress(
    val status: ReadingStatus,
    val currentIndex: Int
) {
    val isCompleted: Boolean get() = status == ReadingStatus.COMPLETED
    val isReading: Boolean get() = status == ReadingStatus.READING
    val isUnread: Boolean get() = status == ReadingStatus.UNREAD
}
