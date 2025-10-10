package com.celstech.satendroid.ui.models

import android.net.Uri
import com.celstech.satendroid.navigation.FileNavigationManager
import com.celstech.satendroid.utils.ZipImageEntry
import java.io.File

/**
 * アプリケーションの主要画面状態を管理するsealedクラス
 */
sealed class ViewState {
    object LocalFileList : ViewState()
    object ImageViewer : ViewState()
    object DropboxBrowser : ViewState()
    object DownloadQueue : ViewState()  // 新しいダウンロードキュー画面
    object Settings : ViewState()
}

/**
 * ヘッダーの展開状態を管理するenum
 * Phase 2: より詳細な状態管理
 */
enum class HeaderState {
    COLLAPSED,      // 折りたたまれた状態
    EXPANDED,       // 展開された状態
    TRANSITIONING,  // アニメーション中
    PREVIEW_EXPAND, // 展開プレビュー
    PREVIEW_COLLAPSE // 折りたたみプレビュー
}

/**
 * 画像ビューアの状態を管理するデータクラス
 */
data class ImageViewerState(
    val imageEntries: List<ZipImageEntry>,
    val currentZipUri: Uri,
    val currentZipFile: File?,
    val fileNavigationInfo: FileNavigationManager.NavigationInfo?,
    val initialPage: Int
) {
    /**
     * ファイルの一意識別子
     */
    val fileId: String
        get() = currentZipFile?.absolutePath ?: currentZipUri.toString()
}

/**
 * アプリケーション全体の設定状態
 * Phase 2: 設定管理の統合
 */
data class AppSettingsState(
    val headerSettings: HeaderSettings = HeaderSettings(),
    val cloudProviders: List<CloudProviderInfo> = emptyList(),
    val displayMode: HeaderDisplayMode = HeaderDisplayMode.STANDARD,
    val isFirstLaunch: Boolean = true,
    val lastHeaderState: HeaderState? = null
)

/**
 * UI操作の結果状態
 */
sealed class UiActionResult {
    object Success : UiActionResult()
    data class Error(val message: String, val exception: Throwable? = null) : UiActionResult()
    object Loading : UiActionResult()
    object Cancelled : UiActionResult()
}
