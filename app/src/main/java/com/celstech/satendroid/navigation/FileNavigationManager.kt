package com.celstech.satendroid.navigation

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ファイル間移動を管理するクラス
 * 同一階層内のZIPファイルの前後移動を制御
 */
class FileNavigationManager(private val context: Context) {

    /**
     * 指定されたファイルと同じ階層にあるZIPファイルリストを取得し、ソート順での前後ファイルを判定
     */
    suspend fun getNavigationInfo(currentFile: File): NavigationInfo = withContext(Dispatchers.IO) {
        val parentDir = currentFile.parentFile
        
        if (parentDir == null || !parentDir.exists() || !parentDir.isDirectory) {
            return@withContext NavigationInfo(
                currentIndex = -1,
                totalFiles = 0,
                previousFile = null,
                nextFile = null,
                allFiles = emptyList()
            )
        }

        // 同じディレクトリ内のZIPファイルを取得
        val zipFiles = parentDir.listFiles { file ->
            file.isFile && file.extension.lowercase() == "zip"
        }?.toList() ?: emptyList()

        // ファイル名でソート（自然順序でのソート）
        val sortedFiles = zipFiles.sortedWith(compareBy { it.name.lowercase() })
        
        // 現在のファイルのインデックスを取得
        val currentIndex = sortedFiles.indexOfFirst { it.absolutePath == currentFile.absolutePath }
        
        if (currentIndex == -1) {
            return@withContext NavigationInfo(
                currentIndex = -1,
                totalFiles = sortedFiles.size,
                previousFile = null,
                nextFile = null,
                allFiles = sortedFiles
            )
        }

        val previousFile = if (currentIndex > 0) sortedFiles[currentIndex - 1] else null
        val nextFile = if (currentIndex < sortedFiles.size - 1) sortedFiles[currentIndex + 1] else null

        NavigationInfo(
            currentIndex = currentIndex,
            totalFiles = sortedFiles.size,
            previousFile = previousFile,
            nextFile = nextFile,
            allFiles = sortedFiles
        )
    }

    /**
     * ナビゲーション情報を保持するデータクラス
     */
    data class NavigationInfo(
        val currentIndex: Int,
        val totalFiles: Int,
        val previousFile: File?,
        val nextFile: File?,
        val allFiles: List<File>
    ) {
        /**
         * 最初のファイルかどうかを判定
         */
        val isFirstFile: Boolean
            get() = currentIndex == 0

        /**
         * 最後のファイルかどうかを判定
         */
        val isLastFile: Boolean
            get() = currentIndex == totalFiles - 1

        /**
         * 前のファイルが存在するかどうかを判定
         */
        val hasPreviousFile: Boolean
            get() = previousFile != null

        /**
         * 次のファイルが存在するかどうかを判定
         */
        val hasNextFile: Boolean
            get() = nextFile != null

        /**
         * 現在のファイル位置の表示用文字列
         */
        val positionText: String
            get() = if (currentIndex >= 0 && totalFiles > 0) {
                "${currentIndex + 1} / $totalFiles"
            } else {
                "- / $totalFiles"
            }
    }
}
