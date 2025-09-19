package com.celstech.satendroid.utils

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
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
 * シンプル化された読書データ管理クラス（Age機能付き統合版）
 *
 * 特徴:
 * - 単一データクラスで全情報管理
 * - 即座保存（バッチ処理なし）
 * - JSONシリアライズで1キー1ファイル
 * - Age機能による古いデータの管理
 * - 使用頻度による自動クリーンアップ
 */
class SimpleReadingDataManager(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "simple_reading_data",
        Context.MODE_PRIVATE
    )

    // 設定関連のStateFlow
    private val _reverseSwipeDirection =
        MutableStateFlow(prefs.getBoolean(KEY_REVERSE_SWIPE_DIRECTION, false))
    val reverseSwipeDirection: StateFlow<Boolean> = _reverseSwipeDirection.asStateFlow()

    private val _cacheEnabled = MutableStateFlow(prefs.getBoolean(KEY_CACHE_ENABLED, true))
    val cacheEnabled: StateFlow<Boolean> = _cacheEnabled.asStateFlow()

    // メモリキャッシュ（単一キャッシュレイヤー）
    private val cache = ConcurrentHashMap<String, FileReadingData>()

    // JSON serializer
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    companion object {
        private const val KEY_REVERSE_SWIPE_DIRECTION = "reverse_swipe_direction"
        private const val KEY_CACHE_ENABLED = "cache_enabled"
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
     * 読書データを保存（即座保存・アクセス情報更新）
     */
    fun saveReadingData(
        filePath: String,
        currentPage: Int,
        totalPages: Int,
        status: ReadingStatus = determineStatus(currentPage, totalPages)
    ) {
        val normalizedPath = normalizeFilePath(filePath)
        val currentTime = System.currentTimeMillis()

        // 既存データを取得してアクセス情報を更新
        val existingData = getReadingDataInternal(normalizedPath)

        val data = FileReadingData(
            filePath = normalizedPath,
            currentPage = currentPage,
            totalPages = totalPages,
            status = status,
            lastUpdated = currentTime,
            createdAt = existingData?.createdAt ?: currentTime,
            accessCount = (existingData?.accessCount ?: 0) + 1,
            lastAccessTime = currentTime
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
        println("DEBUG:   AccessCount: ${data.accessCount}, Age: ${data.ageInDays} days")
    }

    /**
     * 読書データを取得（アクセス情報更新あり）
     */
    fun getReadingData(filePath: String): FileReadingData {
        val normalizedPath = normalizeFilePath(filePath)

        // メモリキャッシュから取得
        val cachedData = cache[normalizedPath]
        if (cachedData != null) {
            // アクセス情報を更新
            val updatedData = cachedData.copy(
                accessCount = cachedData.accessCount + 1,
                lastAccessTime = System.currentTimeMillis()
            )
            cache[normalizedPath] = updatedData
            updateStoredData(updatedData)
            return updatedData
        }

        val data = getReadingDataInternal(normalizedPath)

        // アクセス情報を更新
        val updatedData = data.copy(
            accessCount = data.accessCount + 1,
            lastAccessTime = System.currentTimeMillis()
        )

        // メモリキャッシュに保存
        cache[normalizedPath] = updatedData
        updateStoredData(updatedData)

        return updatedData
    }

    /**
     * 読書データを内部的に取得（アクセス情報更新なし）
     */
    private fun getReadingDataInternal(normalizedPath: String): FileReadingData {
        // SharedPreferencesから読み込み
        val key = getStorageKey(normalizedPath)
        val jsonString = prefs.getString(key, null)

        return if (jsonString != null) {
            try {
                json.decodeFromString<FileReadingData>(jsonString)
            } catch (e: Exception) {
                println("ERROR: Failed to decode JSON for $normalizedPath: ${e.message}")
                // デフォルトデータを作成
                createDefaultData(normalizedPath)
            }
        } else {
            // データが存在しない場合のデフォルト
            createDefaultData(normalizedPath)
        }
    }

    /**
     * デフォルトデータを作成
     */
    private fun createDefaultData(normalizedPath: String): FileReadingData {
        val currentTime = System.currentTimeMillis()
        return FileReadingData(
            filePath = normalizedPath,
            currentPage = 0,
            totalPages = 0,
            status = ReadingStatus.UNREAD,
            lastUpdated = currentTime,
            createdAt = currentTime,
            accessCount = 0,
            lastAccessTime = currentTime
        )
    }

    /**
     * ストレージのデータを更新
     */
    private fun updateStoredData(data: FileReadingData) {
        val key = getStorageKey(data.filePath)
        val jsonString = json.encodeToString(data)
        prefs.edit {
            putString(key, jsonString)
        }
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
     * 読書位置を取得（同期版・互換性用）
     */
    fun getReadingPositionSync(filePath: String): Int {
        return getCurrentPage(filePath)
    }

    /**
     * 読書状況を取得（同期版・互換性用）
     */
    fun getReadingStatusSync(filePath: String): ReadingStatus {
        return getReadingStatus(filePath)
    }

    /**
     * 読書進捗を取得（同期版・互換性用）
     */
    fun getReadingProgressSync(filePath: String): ReadingProgress {
        return getReadingProgress(filePath)
    }

    /**
     * 総画像数を保存（互換性用）
     */
    fun saveTotalImages(filePath: String, totalImages: Int) {
        val data = getReadingDataInternal(normalizeFilePath(filePath))
        val updatedData = data.copy(
            totalPages = totalImages,
            lastUpdated = System.currentTimeMillis()
        )

        val normalizedPath = normalizeFilePath(filePath)
        cache[normalizedPath] = updatedData
        updateStoredData(updatedData)
    }

    /**
     * URI/File対応の位置取得（UnifiedReadingDataManager互換）
     */
    fun getSavedPosition(zipUri: Uri, zipFile: File? = null): Int {
        val filePath = generateFileIdentifier(zipUri, zipFile)
        return getCurrentPage(filePath)
    }

    /**
     * URI/File対応の位置保存（UnifiedReadingDataManager互換）
     */
    fun saveCurrentPosition(zipUri: Uri, imageIndex: Int, zipFile: File? = null) {
        val filePath = generateFileIdentifier(zipUri, zipFile)
        saveReadingData(
            filePath = filePath,
            currentPage = imageIndex,
            totalPages = 0 // 後で更新される
        )
    }

    /**
     * ファイル識別子生成（UnifiedReadingDataManager互換）
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
     * Age-based クリーンアップ: 古いデータの識別
     */
    fun getOldDataForCleanup(
        maxAgeInDays: Int = 30,
        minAccessCount: Int = 1,
        maxDaysSinceLastAccess: Int = 14
    ): List<CleanupCandidate> {
        val candidates = mutableListOf<CleanupCandidate>()
        val allKeys = prefs.all.keys.filter { it.startsWith(DATA_PREFIX) }

        allKeys.forEach { key ->
            try {
                val jsonString = prefs.getString(key, null)
                if (jsonString != null) {
                    val data = json.decodeFromString<FileReadingData>(jsonString)

                    val shouldCleanup =
                        (data.ageInDays > maxAgeInDays && data.accessCount < minAccessCount) ||
                                data.daysSinceLastAccess > maxDaysSinceLastAccess

                    if (shouldCleanup) {
                        candidates.add(
                            CleanupCandidate(
                                filePath = data.filePath,
                                ageInDays = data.ageInDays,
                                accessCount = data.accessCount,
                                daysSinceLastAccess = data.daysSinceLastAccess,
                                usageFrequency = data.usageFrequency,
                                reason = when {
                                    data.daysSinceLastAccess > maxDaysSinceLastAccess -> "Long time since last access"
                                    data.ageInDays > maxAgeInDays && data.accessCount < minAccessCount -> "Old with low usage"
                                    else -> "Unknown"
                                }
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                println("ERROR: Failed to parse data for cleanup analysis: ${e.message}")
            }
        }

        return candidates.sortedByDescending { it.daysSinceLastAccess }
    }

    /**
     * 指定された基準でデータをクリーンアップ
     */
    fun cleanupOldData(criteria: CleanupCriteria = CleanupCriteria()): CleanupResult {
        val candidates = getOldDataForCleanup(
            criteria.maxAgeInDays,
            criteria.minAccessCount,
            criteria.maxDaysSinceLastAccess
        )

        var deletedCount = 0
        var failedCount = 0

        candidates.forEach { candidate ->
            try {
                clearFileData(candidate.filePath)
                deletedCount++
                println("DEBUG: Cleaned up old data: ${File(candidate.filePath).name} (${candidate.reason})")
            } catch (e: Exception) {
                failedCount++
                println("ERROR: Failed to cleanup data for ${candidate.filePath}: ${e.message}")
            }
        }

        val result = CleanupResult(
            candidatesFound = candidates.size,
            deletedCount = deletedCount,
            failedCount = failedCount,
            deletedFiles = candidates.take(deletedCount).map { File(it.filePath).name }
        )

        println("DEBUG: Cleanup completed - Found: ${result.candidatesFound}, Deleted: ${result.deletedCount}, Failed: ${result.failedCount}")

        return result
    }

    /**
     * ファイルデータをクリア
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
     * キャッシュ機能設定
     */
    fun setCacheEnabled(enabled: Boolean) {
        _cacheEnabled.value = enabled
        prefs.edit {
            putBoolean(KEY_CACHE_ENABLED, enabled)
        }
        println("DEBUG: Cache enabled set to: $enabled")
    }

    /**
     * 統計情報を取得
     */
    fun getStatistics(): DataStatistics {
        val allKeys = prefs.all.keys.filter { it.startsWith(DATA_PREFIX) }
        var totalFiles = 0
        var totalAccess = 0
        var oldFiles = 0
        var recentFiles = 0

        allKeys.forEach { key ->
            try {
                val jsonString = prefs.getString(key, null)
                if (jsonString != null) {
                    val data = json.decodeFromString<FileReadingData>(jsonString)
                    totalFiles++
                    totalAccess += data.accessCount

                    if (data.daysSinceLastAccess > 14) {
                        oldFiles++
                    } else {
                        recentFiles++
                    }
                }
            } catch (e: Exception) {
                // 解析エラーは無視
            }
        }

        return DataStatistics(
            totalFiles = totalFiles,
            averageAccessCount = if (totalFiles > 0) totalAccess.toFloat() / totalFiles else 0f,
            oldFiles = oldFiles,
            recentFiles = recentFiles
        )
    }

    /**
     * 統合システムからの移行処理
     */
    private fun migrateFromUnifiedSystem() {
        try {
            println("DEBUG: Starting migration from UnifiedReadingDataManager...")

            val unifiedPrefs =
                context.getSharedPreferences("unified_reading_data", Context.MODE_PRIVATE)
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

                    key == "cache_enabled" -> {
                        val enabled = unifiedPrefs.getBoolean(key, true)
                        setCacheEnabled(enabled)
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
                    lastUpdated = updated,
                    createdAt = updated, // 移行データは作成日時を更新日時と同じにする
                    accessCount = 1, // 移行データは1回アクセスとする
                    lastAccessTime = updated
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
        val data = getReadingDataInternal(normalizeFilePath(filePath))
        println("=== SimpleReadingDataManager Debug ===")
        println("File: ${File(filePath).name}")
        println("Normalized Path: ${data.filePath}")
        println("Current Page: ${data.currentPage}")
        println("Total Pages: ${data.totalPages}")
        println("Status: ${data.status}")
        println("Last Updated: ${data.lastUpdated}")
        println("Created At: ${data.createdAt}")
        println("Access Count: ${data.accessCount}")
        println("Last Access: ${data.lastAccessTime}")
        println("Age in Days: ${data.ageInDays}")
        println("Days Since Last Access: ${data.daysSinceLastAccess}")
        println("Usage Frequency: ${data.usageFrequency}")
        println("Cache Hit: ${cache.containsKey(normalizeFilePath(filePath))}")
        println("=== End Debug ===")
    }

    /**
     * Age機能のデモ：古いデータの自動クリーンアップを実行
     */
    fun performAutomaticCleanup(): CleanupResult {
        // 30日以上古く、アクセス回数が1回以下、かつ14日以上アクセスがないデータをクリーンアップ
        return cleanupOldData(
            CleanupCriteria(
                maxAgeInDays = 30,
                minAccessCount = 1,
                maxDaysSinceLastAccess = 14
            )
        )
    }

    /**
     * Age機能のデモ：統計とクリーンアップ候補を表示
     */
    fun printAgeStatistics() {
        val statistics = getStatistics()
        val candidates = getOldDataForCleanup()

        println("=== Age機能統計 ===")
        println("総ファイル数: ${statistics.totalFiles}")
        println("平均アクセス回数: ${statistics.averageAccessCount}")
        println("最近使用したファイル: ${statistics.recentFiles}")
        println("古いファイル: ${statistics.oldFiles}")
        println("クリーンアップ候補: ${candidates.size}")

        if (candidates.isNotEmpty()) {
            println("\n=== クリーンアップ候補 ===")
            candidates.take(5).forEach { candidate ->
                println("- ${File(candidate.filePath).name}")
                println("  Age: ${candidate.ageInDays}日, アクセス: ${candidate.accessCount}回")
                println("  最終アクセス: ${candidate.daysSinceLastAccess}日前")
                println("  理由: ${candidate.reason}")
            }
            if (candidates.size > 5) {
                println("... and ${candidates.size - 5} more files")
            }
        }
        println("=== 統計終了 ===")
    }
}

/**
 * Age機能付きファイル読書データクラス
 */
@Serializable
data class FileReadingData(
    val filePath: String,           // 正規化されたファイルパス
    val currentPage: Int,           // 現在のページ（0ベース）
    val totalPages: Int,            // 総ページ数
    val status: ReadingStatus,      // 読書状況
    val lastUpdated: Long,          // 最終更新時刻
    val createdAt: Long = System.currentTimeMillis(), // 作成日時
    val accessCount: Int = 0,       // アクセス回数
    val lastAccessTime: Long = System.currentTimeMillis() // 最終アクセス時刻
) {
    /**
     * 作成からの経過日数
     */
    val ageInDays: Int
        get() = ((System.currentTimeMillis() - createdAt) / (24 * 60 * 60 * 1000)).toInt()

    /**
     * 最終アクセスからの経過日数
     */
    val daysSinceLastAccess: Int
        get() = ((System.currentTimeMillis() - lastAccessTime) / (24 * 60 * 60 * 1000)).toInt()

    /**
     * 使用頻度（1日あたりのアクセス回数）
     */
    val usageFrequency: Float
        get() = if (ageInDays > 0) accessCount.toFloat() / ageInDays else accessCount.toFloat()

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

/**
 * クリーンアップ基準
 */
data class CleanupCriteria(
    val maxAgeInDays: Int = 30,            // 最大保持日数
    val minAccessCount: Int = 1,           // 最小アクセス回数
    val maxDaysSinceLastAccess: Int = 14   // 最終アクセスからの最大日数
)

/**
 * クリーンアップ候補
 */
data class CleanupCandidate(
    val filePath: String,
    val ageInDays: Int,
    val accessCount: Int,
    val daysSinceLastAccess: Int,
    val usageFrequency: Float,
    val reason: String
)

/**
 * クリーンアップ結果
 */
data class CleanupResult(
    val candidatesFound: Int,
    val deletedCount: Int,
    val failedCount: Int,
    val deletedFiles: List<String>
)

/**
 * データ統計情報
 */
data class DataStatistics(
    val totalFiles: Int,
    val averageAccessCount: Float,
    val oldFiles: Int,      // 14日以上アクセスなし
    val recentFiles: Int    // 14日以内にアクセスあり
)
