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
 */
enum class HeaderState {
    COLLAPSED,    // 折りたたまれた状態
    EXPANDED,     // 展開された状態
    TRANSITIONING // アニメーション中
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
