package com.celstech.satendroid.ui.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.celstech.satendroid.ui.models.HeaderState
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.sign

/**
 * Phase 2: 高度なスワイプ検出機能付きヘッダーコンポーネント
 * - スワイプ速度を考慮した状態変更
 * - 部分的なスワイプでのプレビュー表示
 * - タップによる即座の状態切り替え
 */
@Composable
fun SwipeableHeader(
    headerState: HeaderState,
    onHeaderStateChange: (HeaderState) -> Unit,
    onCollapseAfterDelay: () -> Unit,
    swipeThreshold: Float = 100f,
    autoCollapseDelay: Long = 5000L,
    enableTapToToggle: Boolean = true,
    enableVelocityDetection: Boolean = true,
    content: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { swipeThreshold.dp.toPx() }
    val previewThresholdPx = swipeThresholdPx * 0.3f
    val velocityThresholdPx = with(density) { 800.dp.toPx() }

    var dragOffset by remember { mutableFloatStateOf(0f) }
    var isSwipeInProgress by remember { mutableStateOf(false) }
    var swipeStartTime by remember { mutableLongStateOf(0L) }
    var lastDragPosition by remember { mutableStateOf(Offset.Zero) }
    
    var isPreviewMode by remember { mutableStateOf(false) }
    var previewDirection by remember { mutableIntStateOf(0) }

    // 自動折りたたみのタイマー
    LaunchedEffect(headerState, autoCollapseDelay) {
        val shouldAutoCollapse = headerState == HeaderState.EXPANDED || 
                                headerState == HeaderState.PREVIEW_EXPAND
        if (shouldAutoCollapse && autoCollapseDelay > 0) {
            delay(autoCollapseDelay)
            onCollapseAfterDelay()
        }
    }

    // プレビューモード管理
    LaunchedEffect(isPreviewMode, previewDirection) {
        if (isPreviewMode) {
            when {
                previewDirection > 0 && (headerState == HeaderState.COLLAPSED || 
                                       headerState == HeaderState.PREVIEW_COLLAPSE) -> {
                    onHeaderStateChange(HeaderState.PREVIEW_EXPAND)
                }
                previewDirection < 0 && (headerState == HeaderState.EXPANDED || 
                                       headerState == HeaderState.PREVIEW_EXPAND) -> {
                    onHeaderStateChange(HeaderState.PREVIEW_COLLAPSE)
                }
            }
        } else {
            when (headerState) {
                HeaderState.PREVIEW_EXPAND -> {
                    delay(100)
                    onHeaderStateChange(HeaderState.COLLAPSED)
                }
                HeaderState.PREVIEW_COLLAPSE -> {
                    delay(100)
                    onHeaderStateChange(HeaderState.EXPANDED)
                }
                else -> { /* 何もしない */ }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(headerState) {
                detectDragGestures(
                    onDragStart = { offset ->
                        isSwipeInProgress = true
                        dragOffset = 0f
                        swipeStartTime = System.currentTimeMillis()
                        lastDragPosition = offset
                        isPreviewMode = false
                    },
                    onDragEnd = {
                        val currentTime = System.currentTimeMillis()
                        val timeDelta = currentTime - swipeStartTime
                        val velocity = if (timeDelta > 0) {
                            abs(dragOffset / timeDelta * 1000)
                        } else 0f
                        
                        val isHighVelocity = enableVelocityDetection && velocity > velocityThresholdPx
                        val isThresholdExceeded = abs(dragOffset) > swipeThresholdPx
                        
                        val targetState = when {
                            isHighVelocity && abs(dragOffset) > swipeThresholdPx * 0.5f -> {
                                when {
                                    dragOffset > 0 && (headerState == HeaderState.COLLAPSED || 
                                                     headerState == HeaderState.PREVIEW_COLLAPSE) -> 
                                        HeaderState.EXPANDED
                                    dragOffset < 0 && (headerState == HeaderState.EXPANDED || 
                                                     headerState == HeaderState.PREVIEW_EXPAND) -> 
                                        HeaderState.COLLAPSED
                                    else -> null
                                }
                            }
                            isThresholdExceeded -> {
                                when {
                                    dragOffset > swipeThresholdPx && (headerState == HeaderState.COLLAPSED || 
                                                                    headerState == HeaderState.PREVIEW_COLLAPSE) -> 
                                        HeaderState.EXPANDED
                                    dragOffset < -swipeThresholdPx && (headerState == HeaderState.EXPANDED || 
                                                                     headerState == HeaderState.PREVIEW_EXPAND) -> 
                                        HeaderState.COLLAPSED
                                    else -> null
                                }
                            }
                            else -> null
                        }
                        
                        targetState?.let { onHeaderStateChange(it) }
                        
                        dragOffset = 0f
                        isSwipeInProgress = false
                        isPreviewMode = false
                        previewDirection = 0
                    }
                ) { change, dragAmount ->
                    if (isSwipeInProgress) {
                        dragOffset += dragAmount.y
                        lastDragPosition = change.position
                        
                        // プレビュー状態の更新
                        val absOffset = abs(dragOffset)
                        val shouldShowPreview = absOffset > previewThresholdPx && absOffset < swipeThresholdPx
                        
                        if (shouldShowPreview) {
                            val direction = sign(dragOffset).toInt()
                            val canPreview = when {
                                direction > 0 && (headerState == HeaderState.COLLAPSED || 
                                                headerState == HeaderState.PREVIEW_COLLAPSE) -> true
                                direction < 0 && (headerState == HeaderState.EXPANDED || 
                                                headerState == HeaderState.PREVIEW_EXPAND) -> true
                                else -> false
                            }
                            
                            if (canPreview) {
                                if (!isPreviewMode || previewDirection != direction) {
                                    isPreviewMode = true
                                    previewDirection = direction
                                }
                            } else {
                                isPreviewMode = false
                            }
                        } else {
                            isPreviewMode = false
                        }
                    }
                }
            }
            .let { baseModifier ->
                if (enableTapToToggle) {
                    baseModifier.pointerInput(headerState) {
                        detectTapGestures(
                            onTap = { _ ->
                                val newState = when (headerState) {
                                    HeaderState.COLLAPSED, HeaderState.PREVIEW_COLLAPSE -> HeaderState.EXPANDED
                                    HeaderState.EXPANDED, HeaderState.PREVIEW_EXPAND -> HeaderState.COLLAPSED
                                    HeaderState.TRANSITIONING -> return@detectTapGestures
                                }
                                onHeaderStateChange(newState)
                            }
                        )
                    }
                } else {
                    baseModifier
                }
            }
    ) {
        content()
    }
}
