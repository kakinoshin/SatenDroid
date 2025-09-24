package com.celstech.satendroid.ui.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.celstech.satendroid.ui.models.HeaderState
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * スワイプ検出機能付きヘッダーコンポーネント
 * Phase 1: 基本的なスワイプ機能
 */
@Composable
fun SwipeableHeader(
    headerState: HeaderState,
    onHeaderStateChange: (HeaderState) -> Unit,
    onCollapseAfterDelay: () -> Unit,
    content: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val swipeThreshold = with(density) { 100.dp.toPx() } // 100dpのスワイプで状態変更

    var dragOffset by remember { mutableFloatStateOf(0f) }
    var isSwipeInProgress by remember { mutableStateOf(false) }

    // 自動折りたたみのタイマー
    LaunchedEffect(headerState) {
        if (headerState == HeaderState.EXPANDED) {
            delay(5000) // 5秒後に自動折りたたみ
            onCollapseAfterDelay()
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        // ドラッグ開始時の処理
                        isSwipeInProgress = true
                        dragOffset = 0f
                    },
                    onDragEnd = {
                        // ドラッグ終了時の処理
                        val shouldToggle = abs(dragOffset) > swipeThreshold
                        
                        if (shouldToggle) {
                            when {
                                // 下方向スワイプ（展開）
                                dragOffset > swipeThreshold && headerState == HeaderState.COLLAPSED -> {
                                    onHeaderStateChange(HeaderState.EXPANDED)
                                }
                                // 上方向スワイプ（折りたたみ）
                                dragOffset < -swipeThreshold && headerState == HeaderState.EXPANDED -> {
                                    onHeaderStateChange(HeaderState.COLLAPSED)
                                }
                            }
                        }
                        
                        // 状態をリセット
                        dragOffset = 0f
                        isSwipeInProgress = false
                    }
                ) { change, dragAmount ->
                    // ドラッグ中の処理
                    if (isSwipeInProgress) {
                        dragOffset += dragAmount.y
                        
                        // ドラッグ方向に応じてアニメーション状態を設定
                        if (abs(dragOffset) > swipeThreshold / 3) {
                            when {
                                dragOffset > 0 && headerState == HeaderState.COLLAPSED -> {
                                    // 下方向ドラッグ中
                                    onHeaderStateChange(HeaderState.TRANSITIONING)
                                }
                                dragOffset < 0 && headerState == HeaderState.EXPANDED -> {
                                    // 上方向ドラッグ中
                                    onHeaderStateChange(HeaderState.TRANSITIONING)
                                }
                            }
                        }
                    }
                }
            }
    ) {
        content()
    }
}
