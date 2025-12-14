package com.celstech.satendroid.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.celstech.satendroid.ui.models.ReadingStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 既読情報管理システム - ファイルハッシュベース版
 *
 * 設計原則:
 * 1. ファイルハッシュ（MD5）による識別 - パス変更に対応
 * 2. 手動削除のみ - ファイル削除時も情報を保持
 * 3. 単一キャッシュ: メモリキャッシュが唯一の真実の源
 * 4. 永続化: SharedPreferencesに保存
 */
class ReadingStateManager(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "reading_states_v3",  // バージョンアップ
        Context.MODE_PRIVATE
    )

    // ファイルハッシュキャッシュ（ファイルパス -> MD5ハッシュ）
    private val hashCache = ConcurrentHashMap<String, String>()
    
    // 読書状態キャッシュ（ファイルハッシュ -> ReadingState）
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
        private const val KEY_HASH_PREFIX = "hash_"
        private const val KEY_REVERSE_SWIPE_DIRECTION = "reverse_swipe_direction"
        private const val KEY_CACHE_ENABLED = "cache_enabled"
    }

    /**
     * ファイルのMD5ハッシュを計算（最初の1MBのみ使用で高速化）
     */
    suspend fun calculateFileHash(file: File): String? = withContext(Dispatchers.IO) {
        try {
            if (!file.exists() || !file.canRead()) {
                println("DEBUG: Cannot read file for hash calculation: ${file.absolutePath}")
                return@withContext null
            }

            val md = MessageDigest.getInstance("MD5")
            val bufferSize = 8192
            val buffer = ByteArray(bufferSize)
            val maxBytes = 1024 * 1024 // 1MB（高速化のため）

            FileInputStream(file).use { fis ->
                var totalRead = 0
                var bytesRead: Int
                
                while (totalRead < maxBytes) {
                    bytesRead = fis.read(buffer)
                    if (bytesRead == -1) break
                    
                    val toUpdate = minOf(bytesRead, maxBytes - totalRead)
                    md.update(buffer, 0, toUpdate)
                    totalRead += toUpdate
                }
            }

            val digest = md.digest()
            val hash = digest.joinToString("") { "%02x".format(it) }
            
            println("DEBUG: Calculated file hash - File: ${file.name}, Hash: ${hash.take(8)}...")
            hash
            
        } catch (e: Exception) {
            println("ERROR: Failed to calculate file hash: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * ファイルハッシュを取得（キャッシュ優先）
     */
    private suspend fun getFileHash(file: File): String? {
        val filePath = file.absolutePath
        
        // メモリキャッシュから取得
        hashCache[filePath]?.let { 
            println("DEBUG: Using cached file hash for ${file.name}")
            return it 
        }
        
        // SharedPreferencesから取得
        val cachedHash = prefs.getString(KEY_HASH_PREFIX + filePath, null)
        if (cachedHash != null) {
            hashCache[filePath] = cachedHash
            println("DEBUG: Loaded file hash from storage for ${file.name}")
            return cachedHash
        }
        
        // 新規計算
        val newHash = calculateFileHash(file)
        if (newHash != null) {
            hashCache[filePath] = newHash
            // SharedPreferencesに保存
            prefs.edit(commit = true) {
                putString(KEY_HASH_PREFIX + filePath, newHash)
            }
        }
        
        return newHash
    }

    /**
     * ファイルパスからファイルハッシュを取得（公開API）
     */
    suspend fun getFileHashByPath(filePath: String): String? {
        val file = File(filePath)
        return getFileHash(file)
    }

    /**
     * ストレージキーを生成（ファイルハッシュベース）
     */
    private fun getStorageKey(fileHash: String): String {
        return KEY_PREFIX + fileHash
    }


    /**
     * 読書状態をメモリキャッシュに読み込む
     * ファイルハッシュベースで管理
     */
    suspend fun loadStateToCache(filePath: String) {
        val file = File(filePath)
        val fileHash = getFileHash(file) ?: run {
            println("DEBUG: Cannot load state - file hash unavailable for ${file.name}")
            return
        }
        
        // 常に最新データを読み込む
        val state = loadFromStorage(fileHash, filePath)
        cache[fileHash] = state
        
        // キャッシュ更新を通知（UIの再描画トリガー）
        _cacheUpdateTrigger.value = System.currentTimeMillis()
        
        println("DEBUG: Loaded state to cache - File: ${file.name}, Hash: ${fileHash.take(8)}..., Status: ${state.status}, Page: ${state.currentPage}/${state.totalPages}")
    }

    /**
     * Storageから読書状態を読み込む（内部用）
     */
    private fun loadFromStorage(fileHash: String, filePath: String): ReadingState {
        val key = getStorageKey(fileHash)
        val jsonString = prefs.getString(key, null)

        return if (jsonString != null) {
            try {
                json.decodeFromString<ReadingState>(jsonString).copy(
                    currentFilePath = filePath // 現在のパスを更新
                )
            } catch (e: Exception) {
                println("ERROR: Failed to decode reading state: ${e.message}")
                createDefaultState(filePath)
            }
        } else {
            createDefaultState(filePath)
        }
    }

    /**
     * デフォルト状態を作成
     */
    private fun createDefaultState(filePath: String = ""): ReadingState {
        return ReadingState(
            status = ReadingStatus.UNREAD,
            currentPage = 0,
            totalPages = 0,
            lastUpdated = System.currentTimeMillis(),
            fileHash = "",
            currentFilePath = filePath
        )
    }

    /**
     * 読書状態を取得（メモリキャッシュから）
     * UIはこのメソッドを使用する
     */
    suspend fun getState(filePath: String): ReadingState {
        val file = File(filePath)
        val fileHash = getFileHash(file) ?: return createDefaultState(filePath)
        
        // メモリキャッシュから取得
        val cachedState = cache[fileHash]
        if (cachedState != null) {
            return cachedState.copy(currentFilePath = filePath)
        }

        // キャッシュにない場合はStorageから読み込んでキャッシュに保存
        val state = loadFromStorage(fileHash, filePath)
        cache[fileHash] = state
        return state
    }

    /**
     * 現在のページを更新（RAM上のみ、保存はしない）
     */
    suspend fun updateCurrentPage(filePath: String, page: Int, totalPages: Int) {
        val file = File(filePath)
        val fileHash = getFileHash(file) ?: run {
            println("DEBUG: Cannot update page - file hash unavailable for ${file.name}")
            return
        }
        
        val currentState = cache[fileHash] ?: loadFromStorage(fileHash, filePath)

        val newState = currentState.copy(
            currentPage = page,
            totalPages = totalPages,
            status = determineStatus(page, totalPages),
            lastUpdated = System.currentTimeMillis(),
            fileHash = fileHash,
            currentFilePath = filePath
        )

        cache[fileHash] = newState
        
        // キャッシュ更新を通知（UIの再描画トリガー）
        _cacheUpdateTrigger.value = System.currentTimeMillis()
        
        println("DEBUG: Updated page in RAM - File: ${file.name}, Hash: ${fileHash.take(8)}..., Page: $page/$totalPages, Status: ${newState.status}")
    }

    /**
     * 読書状態を同期保存（ファイルを閉じる時のみ呼び出す）
     */
    suspend fun saveStateSync(filePath: String): Result<Unit> {
        return try {
            val file = File(filePath)
            val fileHash = getFileHash(file) ?: return Result.failure(
                Exception("Cannot save state - file hash unavailable")
            )
            
            val state = cache[fileHash] ?: return Result.success(Unit)

            val key = getStorageKey(fileHash)
            val stateToSave = state.copy(
                fileHash = fileHash,
                currentFilePath = filePath
            )
            val jsonString = json.encodeToString(stateToSave)

            // commit()を使用して同期保存
            val success = prefs.edit().let { editor ->
                editor.putString(key, jsonString)
                editor.commit()
            }

            if (success) {
                // キャッシュ更新を通知
                _cacheUpdateTrigger.value = System.currentTimeMillis()
                
                println("DEBUG: Saved state (SYNC) - File: ${file.name}, Hash: ${fileHash.take(8)}..., Status: ${state.status}, Page: ${state.currentPage}/${state.totalPages}")
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
     * ファイルの状態を手動削除（キャッシュマネージャから呼び出される）
     */
    suspend fun manuallyDeleteState(fileHash: String) {
        // メモリキャッシュから削除
        cache.remove(fileHash)
        
        // Storageから削除
        val key = getStorageKey(fileHash)
        prefs.edit(commit = true) {
            remove(key)
        }
        
        // キャッシュ更新を通知
        _cacheUpdateTrigger.value = System.currentTimeMillis()
        
        println("DEBUG: Manually deleted state for hash: ${fileHash.take(8)}...")
    }

    /**
     * 複数の状態を手動削除
     */
    suspend fun manuallyDeleteStates(fileHashes: List<String>) {
        fileHashes.forEach { hash ->
            cache.remove(hash)
        }
        
        prefs.edit(commit = true) {
            fileHashes.forEach { hash ->
                remove(getStorageKey(hash))
            }
        }
        
        _cacheUpdateTrigger.value = System.currentTimeMillis()
        println("DEBUG: Manually deleted ${fileHashes.size} states")
    }

    /**
     * 全状態を手動削除
     */
    suspend fun manuallyDeleteAllStates() {
        cache.clear()
        hashCache.clear()
        
        val allKeys = prefs.all.keys.filter { it.startsWith(KEY_PREFIX) || it.startsWith(KEY_HASH_PREFIX) }
        prefs.edit(commit = true) {
            allKeys.forEach { remove(it) }
        }
        
        _cacheUpdateTrigger.value = System.currentTimeMillis()
        println("DEBUG: Manually deleted all reading states and hash cache")
    }

    /**
     * 保存されている全状態を取得（キャッシュマネージャ用）
     */
    suspend fun getAllSavedStates(): List<SavedStateInfo> = withContext(Dispatchers.IO) {
        val allKeys = prefs.all.keys.filter { it.startsWith(KEY_PREFIX) }
        val states = mutableListOf<SavedStateInfo>()
        
        allKeys.forEach { key ->
            try {
                val jsonString = prefs.getString(key, null)
                if (jsonString != null) {
                    val state = json.decodeFromString<ReadingState>(jsonString)
                    val fileHash = key.removePrefix(KEY_PREFIX)
                    
                    // 現在のファイルパスが存在するかチェック
                    val fileExists = state.currentFilePath.isNotEmpty() && 
                                    File(state.currentFilePath).exists()
                    
                    states.add(
                        SavedStateInfo(
                            fileHash = fileHash,
                            fileName = state.currentFilePath.substringAfterLast('/'),
                            filePath = state.currentFilePath,
                            fileExists = fileExists,
                            status = state.status,
                            currentPage = state.currentPage,
                            totalPages = state.totalPages,
                            lastUpdated = state.lastUpdated
                        )
                    )
                }
            } catch (e: Exception) {
                println("ERROR: Failed to load saved state for key $key: ${e.message}")
            }
        }
        
        // 最終更新日時の降順でソート
        states.sortedByDescending { it.lastUpdated }
    }

    /**
     * メモリキャッシュをクリア（アプリ終了時など）
     */
    fun clearMemoryCache() {
        cache.clear()
        hashCache.clear()
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
    suspend fun debugPrintState(filePath: String) {
        val state = getState(filePath)
        val file = File(filePath)
        val fileHash = getFileHash(file)
        
        println("=== ReadingStateManager Debug ===")
        println("File: ${file.name}")
        println("Path: ${state.currentFilePath}")
        println("Hash: ${fileHash?.take(8)}...")
        println("Status: ${state.status}")
        println("Current Page: ${state.currentPage}")
        println("Total Pages: ${state.totalPages}")
        println("Last Updated: ${state.lastUpdated}")
        println("In Cache: ${fileHash?.let { cache.containsKey(it) } ?: false}")
        println("=================================")
    }
}

/**
 * 読書状態データクラス（ファイルハッシュベース）
 */
@Serializable
data class ReadingState(
    val status: ReadingStatus,
    val currentPage: Int,
    val totalPages: Int,
    val lastUpdated: Long,
    val fileHash: String = "", // ファイルのMD5ハッシュ
    val currentFilePath: String = "" // 現在のファイルパス（参考用）
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
 * 保存された状態情報（キャッシュマネージャ用）
 */
data class SavedStateInfo(
    val fileHash: String,
    val fileName: String,
    val filePath: String,
    val fileExists: Boolean,
    val status: ReadingStatus,
    val currentPage: Int,
    val totalPages: Int,
    val lastUpdated: Long
) {
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
    
    val progressRatio: Float
        get() = if (totalPages > 0) (currentPage + 1).toFloat() / totalPages else 0f
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
