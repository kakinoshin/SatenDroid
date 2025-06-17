package com.celstech.satendroid.ui.components

import androidx.compose.runtime.Composable
import com.celstech.satendroid.ui.screens.MainScreen

/**
 * イメージビューアーのエントリーポイント
 * 後方互換性のため、MainScreenをラップしています
 */
@Composable
fun ImageViewerScreen() {
    MainScreen()
}
