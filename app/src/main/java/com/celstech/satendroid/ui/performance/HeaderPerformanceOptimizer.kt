package com.celstech.satendroid.ui.performance

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.GraphicsLayerScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * ヘッダーのパフォーマンス最適化ユーティリティ
 * Phase 2: パフォーマンス最適化（簡素版）
 */
object HeaderPerformanceOptimizer {
    
    private var lastFrameTime = 0L
    private val frameTimeHistory = mutableListOf<Long>()
    private const val FRAME_HISTORY_SIZE = 60
    
    private val _currentFps = MutableStateFlow(60f)
    val currentFps: StateFlow<Float> = _currentFps
    
    /**
     * フレーム時間を記録し、平均FPSを計算
     */
    fun recordFrameTime() {
        val currentTime = System.nanoTime()
        
        if (lastFrameTime != 0L) {
            val frameDuration = currentTime - lastFrameTime
            frameTimeHistory.add(frameDuration)
            
            if (frameTimeHistory.size > FRAME_HISTORY_SIZE) {
                frameTimeHistory.removeAt(0)
            }
            
            if (frameTimeHistory.size >= 10) {
                val averageFrameTime = frameTimeHistory.average()
                val fps = 1_000_000_000.0 / averageFrameTime
                _currentFps.value = fps.toFloat().coerceIn(1f, 120f)
            }
        }
        
        lastFrameTime = currentTime
    }
    
    /**
     * GPU最適化されたGraphicsLayer設定
     */
    fun GraphicsLayerScope.applyGpuOptimizations() {
        compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
        renderEffect = null
    }
    
    /**
     * アニメーション最適化の判定
     */
    fun shouldUseOptimizedAnimation(): Boolean {
        return _currentFps.value < 45f
    }
    
    /**
     * スワイプジェスチャーのスロットリング
     */
    class GestureThrottler(private val minInterval: Long = 16L) {
        private var lastUpdateTime = 0L
        
        fun shouldProcessGesture(): Boolean {
            val currentTime = System.currentTimeMillis()
            return if (currentTime - lastUpdateTime >= minInterval) {
                lastUpdateTime = currentTime
                true
            } else {
                false
            }
        }
    }
    
    /**
     * メモリ効率的な状態管理
     */
    @Composable
    fun rememberHeaderStateManager(): HeaderStateManager {
        return remember {
            HeaderStateManager()
        }
    }
}

/**
 * ヘッダー状態の効率的管理
 */
class HeaderStateManager {
    private val stateHistory = mutableListOf<HeaderStateEntry>()
    private val maxHistorySize = 5
    
    data class HeaderStateEntry(
        val timestamp: Long,
        val state: com.celstech.satendroid.ui.models.HeaderState,
        val trigger: String
    )
    
    fun addState(
        state: com.celstech.satendroid.ui.models.HeaderState,
        trigger: String
    ) {
        val entry = HeaderStateEntry(
            timestamp = System.currentTimeMillis(),
            state = state,
            trigger = trigger
        )
        
        stateHistory.add(entry)
        
        while (stateHistory.size > maxHistorySize) {
            stateHistory.removeAt(0)
        }
    }
    
    fun getLastState(): HeaderStateEntry? = stateHistory.lastOrNull()
    
    fun shouldPreventRapidChanges(): Boolean {
        if (stateHistory.size < 2) return false
        
        val recent = stateHistory.takeLast(2)
        val timeDiff = recent[1].timestamp - recent[0].timestamp
        
        return timeDiff < 100L
    }
    
    fun clearHistory() {
        stateHistory.clear()
    }
}

/**
 * レンダリングパフォーマンスモニター
 */
object RenderingMonitor {
    private var compositionCount = 0
    private var recompositionCount = 0
    private var lastResetTime = System.currentTimeMillis()
    
    fun recordComposition() {
        compositionCount++
    }
    
    fun recordRecomposition() {
        recompositionCount++
    }
    
    fun getPerformanceStats(): PerformanceStats {
        val currentTime = System.currentTimeMillis()
        val elapsed = currentTime - lastResetTime
        
        val stats = PerformanceStats(
            compositionsPerSecond = if (elapsed > 0) (compositionCount * 1000f / elapsed) else 0f,
            recompositionsPerSecond = if (elapsed > 0) (recompositionCount * 1000f / elapsed) else 0f,
            totalCompositions = compositionCount,
            totalRecompositions = recompositionCount
        )
        
        if (elapsed > 60_000L) {
            reset()
        }
        
        return stats
    }
    
    private fun reset() {
        compositionCount = 0
        recompositionCount = 0
        lastResetTime = System.currentTimeMillis()
    }
}

data class PerformanceStats(
    val compositionsPerSecond: Float,
    val recompositionsPerSecond: Float,
    val totalCompositions: Int,
    val totalRecompositions: Int
)

/**
 * パフォーマンス診断ユーティリティ
 */
object PerformanceDiagnostics {
    
    fun diagnosePerformanceIssues(): List<String> {
        val issues = mutableListOf<String>()
        val stats = RenderingMonitor.getPerformanceStats()
        
        if (stats.recompositionsPerSecond > 10f) {
            issues.add("高頻度のRecompositionが検出されました (${stats.recompositionsPerSecond.toInt()}/秒)")
        }
        
        if (HeaderPerformanceOptimizer.currentFps.value < 30f) {
            issues.add("フレームレートが低下しています (${HeaderPerformanceOptimizer.currentFps.value.toInt()}FPS)")
        }
        
        if (stats.compositionsPerSecond > 30f) {
            issues.add("過度なCompositionが発生しています (${stats.compositionsPerSecond.toInt()}/秒)")
        }
        
        return issues
    }
    
    fun getOptimizationRecommendations(): List<String> {
        val recommendations = mutableListOf<String>()
        val currentFps = HeaderPerformanceOptimizer.currentFps.value
        
        if (currentFps < 45f) {
            recommendations.add("アニメーション時間を短縮することを推奨します")
            recommendations.add("プレビューモードを無効化することを検討してください")
        }
        
        if (currentFps < 30f) {
            recommendations.add("自動折りたたみを無効化することを推奨します")
            recommendations.add("Cloud Provider表示を簡素化してください")
        }
        
        return recommendations
    }
}
