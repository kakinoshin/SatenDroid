package com.celstech.satendroid.utils

import android.content.Context
import android.content.SharedPreferences
import com.celstech.satendroid.ui.models.ReadingStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ZIPファイルの読書状況を管理するクラス
 */
class ReadingStatusManager(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "reading_status_prefs", 
        Context.MODE_PRIVATE
    )
    
    companion object {
        private const val STATUS_PREFIX = "status_"
        private const val CURRENT_INDEX_PREFIX = "current_index_"
        private const val TOTAL_COUNT_PREFIX = "total_count_"
    }
    
    /**
     * ファイルの読書状況を保存
     */
    suspend fun saveReadingStatus(
        filePath: String, 
        status: ReadingStatus,
        currentIndex: Int = 0,
        totalCount: Int = 0
    ) = withContext(Dispatchers.IO) {
        prefs.edit().apply {
            putString(STATUS_PREFIX + filePath, status.name)
            putInt(CURRENT_INDEX_PREFIX + filePath, currentIndex)
            putInt(TOTAL_COUNT_PREFIX + filePath, totalCount)
        }.apply()
    }
    
    /**
     * ファイルの読書状況を取得
     */
    suspend fun getReadingStatus(filePath: String): ReadingStatus = withContext(Dispatchers.IO) {
        val statusString = prefs.getString(STATUS_PREFIX + filePath, ReadingStatus.UNREAD.name)
        try {
            ReadingStatus.valueOf(statusString ?: ReadingStatus.UNREAD.name)
        } catch (e: IllegalArgumentException) {
            ReadingStatus.UNREAD
        }
    }
    
    /**
     * ファイルの現在の画像インデックスを取得
     */
    suspend fun getCurrentImageIndex(filePath: String): Int = withContext(Dispatchers.IO) {
        prefs.getInt(CURRENT_INDEX_PREFIX + filePath, 0)
    }
    
    /**
     * ファイルの総画像数を取得
     */
    suspend fun getTotalImageCount(filePath: String): Int = withContext(Dispatchers.IO) {
        prefs.getInt(TOTAL_COUNT_PREFIX + filePath, 0)
    }
    
    /**
     * ファイルの読書進捗情報を一括取得
     */
    suspend fun getReadingProgress(filePath: String): ReadingProgress = withContext(Dispatchers.IO) {
        ReadingProgress(
            status = getReadingStatus(filePath),
            currentIndex = getCurrentImageIndex(filePath),
            totalCount = getTotalImageCount(filePath)
        )
    }
    
    /**
     * ファイルを既読にマーク
     */
    suspend fun markAsCompleted(filePath: String, totalCount: Int) {
        saveReadingStatus(filePath, ReadingStatus.COMPLETED, totalCount - 1, totalCount)
    }
    
    /**
     * ファイルを読書中にマーク
     */
    suspend fun markAsReading(filePath: String, currentIndex: Int, totalCount: Int) {
        saveReadingStatus(filePath, ReadingStatus.READING, currentIndex, totalCount)
    }
    
    /**
     * ファイルを未読にリセット
     */
    suspend fun markAsUnread(filePath: String) {
        saveReadingStatus(filePath, ReadingStatus.UNREAD, 0, 0)
    }
    
    /**
     * 読書状況をクリア（ファイル削除時などに使用）
     */
    suspend fun clearReadingStatus(filePath: String) = withContext(Dispatchers.IO) {
        prefs.edit().apply {
            remove(STATUS_PREFIX + filePath)
            remove(CURRENT_INDEX_PREFIX + filePath)
            remove(TOTAL_COUNT_PREFIX + filePath)
        }.apply()
    }
    
    /**
     * 全ての読書状況をクリア
     */
    suspend fun clearAllReadingStatus() = withContext(Dispatchers.IO) {
        prefs.edit().clear().apply()
    }
}

/**
 * 読書進捗情報を保持するデータクラス
 */
data class ReadingProgress(
    val status: ReadingStatus,
    val currentIndex: Int,
    val totalCount: Int
) {
    /**
     * 進捗率を取得（0.0 - 1.0）
     */
    val progress: Float
        get() = if (totalCount > 0) {
            currentIndex.toFloat() / totalCount.toFloat()
        } else {
            0f
        }
    
    /**
     * 読了済みかどうか
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
