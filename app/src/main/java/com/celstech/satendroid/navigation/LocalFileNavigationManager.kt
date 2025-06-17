package com.celstech.satendroid.navigation

/**
 * ローカルファイル画面のナビゲーション管理
 */
class LocalFileNavigationManager {
    
    /**
     * フォルダに移動する際の新しいパス履歴を計算
     */
    fun navigateToFolder(
        currentPath: String,
        currentHistory: List<String>,
        targetFolderPath: String
    ): NavigationResult {
        return NavigationResult(
            newPath = targetFolderPath,
            newHistory = currentHistory + currentPath
        )
    }

    /**
     * 戻る処理の際の新しいパス履歴を計算
     */
    fun navigateBack(
        currentHistory: List<String>
    ): NavigationResult? {
        return if (currentHistory.isNotEmpty()) {
            val previousPath = currentHistory.last()
            NavigationResult(
                newPath = previousPath,
                newHistory = currentHistory.dropLast(1)
            )
        } else null
    }

    /**
     * パスの表示用フォーマット
     */
    fun formatDisplayPath(path: String): String {
        return if (path.isEmpty()) "📁 Local Files" else "📁 $path"
    }

    /**
     * ナビゲーション結果
     */
    data class NavigationResult(
        val newPath: String,
        val newHistory: List<String>
    )
}
