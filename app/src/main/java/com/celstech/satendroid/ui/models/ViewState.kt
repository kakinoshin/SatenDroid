package com.celstech.satendroid.ui.models

/**
 * アプリケーションの主要画面状態を管理するsealedクラス
 */
sealed class ViewState {
    object LocalFileList : ViewState()
    object ImageViewer : ViewState()
    object DropboxBrowser : ViewState()
}
