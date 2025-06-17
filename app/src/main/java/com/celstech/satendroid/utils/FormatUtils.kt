package com.celstech.satendroid.utils

/**
 * フォーマット関連のユーティリティ関数
 */
object FormatUtils {
    
    /**
     * バイト数をファイルサイズとして読みやすい形式にフォーマット
     */
    fun formatFileSize(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0

        return when {
            gb >= 1 -> "%.1f GB".format(gb)
            mb >= 1 -> "%.1f MB".format(mb)
            kb >= 1 -> "%.1f KB".format(kb)
            else -> "$bytes bytes"
        }
    }

    /**
     * タイムスタンプを日付形式にフォーマット
     */
    fun formatDate(timestamp: Long): String {
        val dateFormat = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault())
        return dateFormat.format(java.util.Date(timestamp))
    }

    /**
     * ダウンロード速度をフォーマット
     */
    fun formatSpeed(bytesPerSecond: Double): String {
        val kb = bytesPerSecond / 1024.0
        val mb = kb / 1024.0

        return when {
            mb >= 1 -> "%.1f MB/s".format(mb)
            kb >= 1 -> "%.1f KB/s".format(kb)
            else -> "%.0f B/s".format(bytesPerSecond)
        }
    }

    /**
     * 秒数を時間形式にフォーマット
     */
    fun formatTime(seconds: Double): String {
        if (seconds.isInfinite() || seconds.isNaN() || seconds < 0) {
            return "∞"
        }

        val totalSeconds = seconds.toInt()
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val secs = totalSeconds % 60

        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${secs}s"
            else -> "${secs}s"
        }
    }
}
