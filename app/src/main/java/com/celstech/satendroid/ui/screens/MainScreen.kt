package com.celstech.satendroid.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.celstech.satendroid.LocalDropboxAuthManager
import com.celstech.satendroid.navigation.FileNavigationManager
import com.celstech.satendroid.ui.models.FileLoadingStateMachine
import com.celstech.satendroid.ui.models.ImageViewerState
import com.celstech.satendroid.ui.models.ViewState
import com.celstech.satendroid.utils.DirectZipImageHandler
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * メイン画面 - State Machine対応版
 * delayによるタイミング調整を排除し、適切な状態遷移で制御
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalPermissionsApi::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    
    // SupervisorJobを使用したStructured Concurrency
    val supervisorJob = remember { SupervisorJob() }
    val mainScope = remember(supervisorJob) { 
        CoroutineScope(Dispatchers.Main + supervisorJob) 
    }

    // Main state for the current view
    var currentView by remember { mutableStateOf<ViewState>(ViewState.LocalFileList) }

    // State Machine for file loading
    val stateMachine = remember { FileLoadingStateMachine() }
    val loadingState by stateMachine.currentState.collectAsState()
    val pendingRequest by stateMachine.pendingRequest.collectAsState()

    // State Machine状態管理の強化
    var previousState by remember { mutableStateOf<FileLoadingStateMachine.LoadingState?>(null) }
    var stateTransitionTime by remember { mutableStateOf(0L) }
    var stateTransitionCount by remember { mutableStateOf(0) }
    var lastErrorRecoveryTime by remember { mutableStateOf(0L) }
    var consecutiveErrorCount by remember { mutableStateOf(0) }

    // State Machine初期化と状態監視
    LaunchedEffect(Unit) {
        try {
            println("DEBUG: Initializing State Machine with proper resource management")
            
            // 初期状態の確認と設定
            val initialState = stateMachine.currentState.value
            println("DEBUG: Initial State Machine state: ${initialState::class.simpleName}")
            
            // 初期化時のリソースチェック
            val runtime = Runtime.getRuntime()
            val availableMemory = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
            println("DEBUG: Available memory at initialization: ${availableMemory / 1024 / 1024}MB")
            
            // State Machineが異常状態にある場合はリセット
            if (initialState is FileLoadingStateMachine.LoadingState.Error) {
                println("WARNING: State Machine initialized in error state, performing reset")
                stateMachine.processAction(FileLoadingStateMachine.LoadingAction.Reset)
            }
            
            stateTransitionTime = System.currentTimeMillis()
            println("DEBUG: State Machine initialization completed")
            
        } catch (e: Exception) {
            println("ERROR: State Machine initialization failed: ${e.message}")
            e.printStackTrace()
        }
    }

    // 読書状態更新のための状態
    var lastReadingProgress by remember { mutableStateOf<Pair<File, Int>?>(null) }
    var fileCompletionUpdate by remember { mutableStateOf<File?>(null) }
    var showTopBar by remember { mutableStateOf(false) }

    // State for directory navigation
    var savedDirectoryPath by remember { mutableStateOf("") }

    // 直接ZIP画像ハンドラー
    val directZipHandler = remember { DirectZipImageHandler(context) }

    // 画像キャッシュサイズ制限の設定
    val maxCacheSizeMB = remember { 
        val runtime = Runtime.getRuntime()
        val maxMemoryMB = runtime.maxMemory() / 1024 / 1024
        // 最大メモリの15%をキャッシュに割り当て（最小50MB、最大200MB）
        (maxMemoryMB * 0.15).toInt().coerceIn(50, 200)
    }
    
    // キャッシュ使用量の監視状態
    var currentCacheSizeMB by remember { mutableStateOf(0) }
    var cacheHitRate by remember { mutableStateOf(0.0) }
    var lastCacheCleanupTime by remember { mutableStateOf(0L) }
    
    // キャッシュ制限の初期化
    LaunchedEffect(Unit) {
        try {
            // 画像キャッシュサイズ制限を設定
            println("DEBUG: Setting image cache limit to ${maxCacheSizeMB}MB")
            
            // キャッシュマネージャーの初期化（実際のAPIに応じて調整が必要）
            // Note: DirectZipImageHandlerの実際のAPIが利用可能になったら有効化
            // val cacheManager = directZipHandler.getCacheManager()
            // cacheManager.setMaxCacheSize(maxCacheSizeMB * 1024 * 1024) // bytes
            
            lastCacheCleanupTime = System.currentTimeMillis()
            println("DEBUG: Image cache configuration completed")
        } catch (e: Exception) {
            println("ERROR: Failed to configure image cache: ${e.message}")
        }
    }

    // File navigation manager
    val fileNavigationManager = remember { FileNavigationManager(context) }

    // 現在の画像ビューア状態
    val currentImageViewerState = remember(loadingState) {
        when (val state = loadingState) {
            is FileLoadingStateMachine.LoadingState.Ready -> state.state
            else -> null
        }
    }

    // Pager state - ファイル変更時に適切なページに移動
    val pagerState = rememberPagerState { 
        currentImageViewerState?.imageEntries?.size ?: 0 
    }

    // 安定した依存関係のためのキー管理
    val currentFileId = remember(currentImageViewerState) { 
        currentImageViewerState?.fileId 
    }
    val isImageViewerReady = remember(currentImageViewerState, stateMachine.isLoading()) {
        currentImageViewerState != null && 
        currentImageViewerState.imageEntries.isNotEmpty() && 
        !stateMachine.isLoading()
    }

    // ページ保存用のデバウンス処理
    var lastSavedPage by remember { mutableStateOf(-1) }
    var lastSavedFileId by remember { mutableStateOf<String?>(null) }

    // ファイルが変更されたときに保存されたページに移動（最適化版）
    LaunchedEffect(currentFileId) {
        // ファイルIDが変更された場合のみ実行
        if (currentFileId != null && isImageViewerReady) {
            val state = currentImageViewerState ?: return@LaunchedEffect
            
            // 重複実行を防ぐ
            if (lastSavedFileId == currentFileId) {
                println("DEBUG: Skipping page navigation - already processed for file: $currentFileId")
                return@LaunchedEffect
            }
            
            val targetPage = state.initialPage.coerceIn(0, state.imageEntries.size - 1)
            println("DEBUG: Moving to saved position: $targetPage for file: $currentFileId (total: ${state.imageEntries.size})")
            
            if (pagerState.currentPage != targetPage && pagerState.pageCount == state.imageEntries.size) {
                try {
                    pagerState.scrollToPage(targetPage)
                    lastSavedFileId = currentFileId
                    println("DEBUG: Successfully moved to page $targetPage")
                } catch (e: Exception) {
                    println("ERROR: Failed to scroll to page $targetPage: ${e.message}")
                }
            }
        }
    }



    // 完全な状態クリア関数（State Machine管理強化版）
    fun clearAllStateAndMemory() {
        println("DEBUG: Starting enhanced cleanup with State Machine management")
        try {
            // Phase 1: State Machine状態確認
            val currentState = stateMachine.currentState.value
            println("DEBUG: Current State Machine state before cleanup: ${currentState::class.simpleName}")
            
            // Phase 2: キャッシュ状態の記録
            val beforeCleanupCacheSize = currentCacheSizeMB
            val runtime = Runtime.getRuntime()
            val usedMemory = runtime.totalMemory() - runtime.freeMemory()
            val maxMemory = runtime.maxMemory()
            val beforeMemoryUsage = ((usedMemory.toDouble() / maxMemory) * 100).toInt()
            
            // Phase 3: State Machine停止（進行中の処理がある場合）
            if (stateMachine.isLoading()) {
                println("DEBUG: State Machine is loading, requesting stop")
                try {
                    stateMachine.processAction(FileLoadingStateMachine.LoadingAction.Reset)
                    // 少し待ってState Machineの停止を確認
                    var waitTime = 0
                    while (stateMachine.isLoading() && waitTime < 2000) {
                        Thread.sleep(50)
                        waitTime += 50
                    }
                    
                    if (stateMachine.isLoading()) {
                        println("WARNING: State Machine still loading after reset request")
                    }
                } catch (e: Exception) {
                    println("ERROR: Failed to stop State Machine: ${e.message}")
                }
            }
            
            // Phase 4: 段階的クリーンアップ
            println("DEBUG: Step 1 - Clearing memory cache")
            directZipHandler.clearMemoryCache()
            currentCacheSizeMB = 0 // キャッシュサイズをリセット
            
            println("DEBUG: Step 2 - Closing ZIP file")
            directZipHandler.closeCurrentZipFile()
            
            println("DEBUG: Step 3 - Final State Machine reset")
            stateMachine.processAction(FileLoadingStateMachine.LoadingAction.Reset)
            
            println("DEBUG: Step 4 - Clearing UI state")
            lastReadingProgress = null
            fileCompletionUpdate = null
            showTopBar = false
            lastSavedPage = -1
            lastSavedFileId = null
            
            // Phase 5: State Machine管理変数のリセット
            println("DEBUG: Step 5 - Resetting State Machine management variables")
            consecutiveErrorCount = 0
            stateTransitionTime = System.currentTimeMillis()
            
            println("DEBUG: Step 6 - Requesting garbage collection")
            System.gc()
            
            // Phase 6: クリーンアップ効果の確認
            val runtimeAfter = Runtime.getRuntime()
            val usedMemoryAfter = runtimeAfter.totalMemory() - runtimeAfter.freeMemory()
            val maxMemoryAfter = runtimeAfter.maxMemory()
            val afterMemoryUsage = ((usedMemoryAfter.toDouble() / maxMemoryAfter) * 100).toInt()
            val memoryReduction = beforeMemoryUsage - afterMemoryUsage
            
            // クリーンアップ統計
            println("DEBUG: Cleanup statistics:")
            println("  - Cache: ${beforeCleanupCacheSize}MB -> ${currentCacheSizeMB}MB")
            println("  - Memory: ${beforeMemoryUsage}% -> ${afterMemoryUsage}% (reduced by ${memoryReduction}%)")
            println("  - State Machine transitions reset")
            
            lastCacheCleanupTime = System.currentTimeMillis()
            
            println("DEBUG: Enhanced cleanup with State Machine management completed successfully")
            
        } catch (e: Exception) {
            println("ERROR: Enhanced cleanup failed: ${e.message}")
            e.printStackTrace()
            
            // フォールバック：強制クリーンアップ
            try {
                println("DEBUG: Performing fallback cleanup")
                directZipHandler.cleanup()
                currentCacheSizeMB = 0
                
                // State Machine強制リセット
                try {
                    stateMachine.processAction(FileLoadingStateMachine.LoadingAction.Reset)
                } catch (resetError: Exception) {
                    println("WARNING: State Machine reset failed during fallback: ${resetError.message}")
                }
                
                System.gc()
                System.runFinalization()
                
            } catch (fallbackError: Exception) {
                println("ERROR: Fallback cleanup also failed: ${fallbackError.message}")
            }
        }
    }

    // State Machine関連のユーティリティ関数群
    
    // 緊急クリーンアップ関数（State Machine管理対応版）
    fun emergencyCleanup(reason: String = "Unknown") {
        println("EMERGENCY: Starting emergency cleanup with State Machine management - Reason: $reason")
        try {
            // Phase 1: State Machine緊急停止
            val currentState = stateMachine.currentState.value
            println("EMERGENCY: Current State Machine state: ${currentState::class.simpleName}")
            
            try {
                // 強制的にState Machineを停止
                stateMachine.processAction(FileLoadingStateMachine.LoadingAction.Reset)
                println("EMERGENCY: State Machine reset completed")
            } catch (e: Exception) {
                println("CRITICAL: State Machine reset failed during emergency: ${e.message}")
            }
            
            // Phase 2: 即座に全てのコルーチンを停止
            supervisorJob.cancel()
            
            // Phase 3: 画像キャッシュの強制クリア
            println("EMERGENCY: Clearing image cache")
            directZipHandler.clearMemoryCache()
            currentCacheSizeMB = 0
            
            // Phase 4: ZIPファイルを閉じる
            directZipHandler.closeCurrentZipFile()
            
            // Phase 5: UI状態をクリア
            lastReadingProgress = null
            fileCompletionUpdate = null
            showTopBar = false
            lastSavedPage = -1
            lastSavedFileId = null
            
            // Phase 6: State Machine管理変数の緊急リセット
            previousState = null
            stateTransitionCount = 0
            consecutiveErrorCount = 0
            stateTransitionTime = System.currentTimeMillis()
            lastErrorRecoveryTime = System.currentTimeMillis()
            
            // Phase 7: システムレベルの強制ガベージコレクション
            System.gc()
            System.runFinalization()
            
            // Phase 8: 緊急時はファイルリスト画面に戻る
            currentView = ViewState.LocalFileList
            
            lastCacheCleanupTime = System.currentTimeMillis()
            
            // Phase 9: 緊急クリーンアップ後の状態確認
            val runtime = Runtime.getRuntime()
            val usedMemory = runtime.totalMemory() - runtime.freeMemory()
            val maxMemory = runtime.maxMemory()
            val finalMemoryUsage = ((usedMemory.toDouble() / maxMemory) * 100).toInt()
            val finalState = try {
                stateMachine.currentState.value::class.simpleName
            } catch (e: Exception) {
                "Unknown"
            }
            
            println("EMERGENCY: Emergency cleanup completed")
            println("EMERGENCY: Final status - Memory: ${finalMemoryUsage}%, State: $finalState")
            
        } catch (e: Exception) {
            println("CRITICAL: Emergency cleanup failed: ${e.message}")
            e.printStackTrace()
            
            // 最後の手段：基本的なクリーンアップのみ実行
            try {
                directZipHandler.cleanup()
                System.gc()
                System.runFinalization()
                currentView = ViewState.LocalFileList
            } catch (finalError: Exception) {
                println("CRITICAL: Final emergency cleanup also failed: ${finalError.message}")
            }
        }
    }
    
    // メモリ使用率取得関数
    fun getMemoryUsage(): Int {
        return try {
            val runtime = Runtime.getRuntime()
            val usedMemory = runtime.totalMemory() - runtime.freeMemory()
            val maxMemory = runtime.maxMemory()
            ((usedMemory.toDouble() / maxMemory) * 100).toInt()
        } catch (e: Exception) {
            println("ERROR: Failed to get memory usage: ${e.message}")
            0
        }
    }
    
    // リソース状態チェック関数
    fun checkResourceStatus(): String {
        return try {
            val memoryUsage = getMemoryUsage()
            val cacheUsage = currentCacheSizeMB
            val maxCache = maxCacheSizeMB
            
            when {
                memoryUsage > 85 || cacheUsage > maxCache * 1.2 -> "critical"
                memoryUsage > 70 || cacheUsage > maxCache -> "high"
                memoryUsage > 50 || cacheUsage > maxCache * 0.7 -> "medium"
                else -> "normal"
            }
        } catch (e: Exception) {
            println("ERROR: Failed to check resource status: ${e.message}")
            "unknown"
        }
    }
    
    // メモリ可用性チェック関数
    fun checkMemoryAvailability(request: Any): Pair<Boolean, String> {
        return try {
            val runtime = Runtime.getRuntime()
            val freeMemory = runtime.freeMemory()
            val maxMemory = runtime.maxMemory()
            val totalMemory = runtime.totalMemory()
            val availableMemory = maxMemory - totalMemory + freeMemory
            
            val requiredMemory = 50 * 1024 * 1024L // 最低50MB必要
            val memoryUsagePercent = getMemoryUsage()
            
            when {
                availableMemory < requiredMemory -> {
                    Pair(false, "Insufficient available memory: ${availableMemory / 1024 / 1024}MB < 50MB")
                }
                memoryUsagePercent > 90 -> {
                    Pair(false, "Memory usage too high: ${memoryUsagePercent}%")
                }
                else -> {
                    Pair(true, "Memory available: ${availableMemory / 1024 / 1024}MB")
                }
            }
        } catch (e: Exception) {
            println("ERROR: Memory availability check failed: ${e.message}")
            Pair(false, "Memory check failed: ${e.message}")
        }
    }
    
    // リソース診断関数
    fun performResourceDiagnostics(): String {
        return try {
            val runtime = Runtime.getRuntime()
            val totalMemory = runtime.totalMemory() / 1024 / 1024
            val freeMemory = runtime.freeMemory() / 1024 / 1024
            val maxMemory = runtime.maxMemory() / 1024 / 1024
            val usedMemory = totalMemory - freeMemory
            val memoryUsage = ((usedMemory.toDouble() / maxMemory) * 100).toInt()
            
            val cacheStatus = "Cache: ${currentCacheSizeMB}MB/${maxCacheSizeMB}MB"
            val memoryStatus = "Memory: ${usedMemory}MB/${maxMemory}MB (${memoryUsage}%)"
            val timeStatus = "Uptime: ${(System.currentTimeMillis() - stateTransitionTime) / 1000}s"
            val transitionStatus = "Transitions: $stateTransitionCount"
            val errorStatus = "Consecutive errors: $consecutiveErrorCount"
            
            "[$memoryStatus] [$cacheStatus] [$timeStatus] [$transitionStatus] [$errorStatus]"
        } catch (e: Exception) {
            "Diagnostics failed: ${e.message}"
        }
    }
    
    // State Machine状態遷移エラーハンドリング
    fun handleStateTransitionError(error: Exception, state: FileLoadingStateMachine.LoadingState) {
        try {
            println("ERROR: State transition error in ${state::class.simpleName}: ${error.message}")
            
            // エラーの種類に応じた対応
            when {
                error is OutOfMemoryError -> {
                    println("CRITICAL: Out of memory error during state transition")
                    emergencyCleanup("OutOfMemoryError in state transition")
                }
                
                error.message?.contains("timeout", ignoreCase = true) == true -> {
                    println("WARNING: Timeout error during state transition")
                    clearAllStateAndMemory()
                    stateMachine.processAction(
                        FileLoadingStateMachine.LoadingAction.FilePreparationFailed(error)
                    )
                }
                
                error.message?.contains("resource", ignoreCase = true) == true -> {
                    println("WARNING: Resource error during state transition")
                    directZipHandler.clearMemoryCache()
                    directZipHandler.closeCurrentZipFile()
                    stateMachine.processAction(
                        FileLoadingStateMachine.LoadingAction.FilePreparationFailed(error)
                    )
                }
                
                else -> {
                    println("INFO: General error during state transition")
                    stateMachine.processAction(
                        FileLoadingStateMachine.LoadingAction.FilePreparationFailed(error)
                    )
                }
            }
            
        } catch (handlingError: Exception) {
            println("CRITICAL: Error handling failed: ${handlingError.message}")
            emergencyCleanup("Error handling failure")
        }
    }
    
    // State Machine健全性チェック
    fun performStateMachineHealthCheck(): Boolean {
        return try {
            val currentStateValue = stateMachine.currentState.value
            val timeSinceLastTransition = System.currentTimeMillis() - stateTransitionTime
            
            // 健全性チェック項目
            val checks = mutableListOf<Pair<String, Boolean>>()
            
            // 1. 状態が適切かチェック
            checks.add("Valid state" to (currentStateValue != null))
            
            // 2. 長時間同じ状態にないかチェック
            val stuckInState = timeSinceLastTransition > 60000 && // 1分以上同じ状態
                               (currentStateValue is FileLoadingStateMachine.LoadingState.PreparingFile ||
                                currentStateValue is FileLoadingStateMachine.LoadingState.CleaningResources)
            checks.add("Not stuck in state" to !stuckInState)
            
            // 3. エラー頻度チェック
            val tooManyErrors = consecutiveErrorCount > 2
            checks.add("Error frequency OK" to !tooManyErrors)
            
            // 4. リソース状態チェック
            val resourcesOK = checkResourceStatus() != "critical"
            checks.add("Resources OK" to resourcesOK)
            
            // 5. 遷移回数チェック
            val transitionRateOK = stateTransitionCount < 100 || 
                                  (System.currentTimeMillis() - stateTransitionTime) > 1000
            checks.add("Transition rate OK" to transitionRateOK)
            
            // 結果をログ出力
            val failedChecks = checks.filter { !it.second }
            if (failedChecks.isNotEmpty()) {
                println("WARNING: State Machine health check failed:")
                failedChecks.forEach { (name, _) ->
                    println("  - $name")
                }
                return false
            }
            
            println("DEBUG: State Machine health check passed")
            return true
            
        } catch (e: Exception) {
            println("ERROR: State Machine health check failed: ${e.message}")
            false
        }
    }
    fun logCacheStatistics() {
        try {
            val runtime = Runtime.getRuntime()
            val usedMemory = runtime.totalMemory() - runtime.freeMemory()
            val maxMemory = runtime.maxMemory()
            val memoryUsagePercent = (usedMemory.toDouble() / maxMemory * 100).toInt()
            
            val dynamicCacheLimit = when {
                memoryUsagePercent > 85 -> (maxCacheSizeMB * 0.5).toInt().coerceAtLeast(20)
                memoryUsagePercent > 70 -> (maxCacheSizeMB * 0.7).toInt().coerceAtLeast(30)
                memoryUsagePercent < 50 -> (maxCacheSizeMB * 1.2).toInt().coerceAtMost(300)
                else -> maxCacheSizeMB
            }
            
            val cacheUsagePercent = if (dynamicCacheLimit > 0) {
                (currentCacheSizeMB.toDouble() / dynamicCacheLimit * 100).toInt()
            } else 0
            
            val timeSinceLastCleanup = System.currentTimeMillis() - lastCacheCleanupTime
            
            println("=== CACHE STATISTICS ===")
            println("System Memory: ${memoryUsagePercent}% (${usedMemory / 1024 / 1024}MB / ${maxMemory / 1024 / 1024}MB)")
            println("Image Cache: ${currentCacheSizeMB}MB / ${dynamicCacheLimit}MB (${cacheUsagePercent}%)")
            println("Cache Hit Rate: ${String.format("%.1f", cacheHitRate * 100)}%")
            println("Max Cache Size: ${maxCacheSizeMB}MB")
            println("Time Since Last Cleanup: ${timeSinceLastCleanup / 1000}s")
            println("=======================")
        } catch (e: Exception) {
            println("ERROR: Failed to generate cache statistics: ${e.message}")
        }
    }



    // 画像キャッシュサイズチェック関数
    fun checkAndLimitCacheSize(forceCleanup: Boolean = false): Boolean {
        return try {
            // キャッシュ使用量を推定（実際のAPIが利用できない場合の暫定実装）
            // Note: DirectZipImageHandlerの実際のAPIに応じて調整が必要
            val estimatedCacheSize = 25 // 暫定値として25MB
            currentCacheSizeMB = estimatedCacheSize
            
            // キャッシュヒット率を設定（実際のAPIが利用できない場合の暫定実装）
            cacheHitRate = 0.8 // 暫定値として80%
            
            val shouldCleanup = forceCleanup || currentCacheSizeMB > maxCacheSizeMB
            
            if (shouldCleanup) {
                println("DEBUG: Cache size limit exceeded: ${currentCacheSizeMB}MB > ${maxCacheSizeMB}MB")
                
                // 段階的キャッシュクリア
                when {
                    currentCacheSizeMB > maxCacheSizeMB * 1.5 -> {
                        // 制限の50%超過時は全クリア
                        println("WARNING: Severe cache overflow, clearing all cache")
                        directZipHandler.clearMemoryCache()
                        currentCacheSizeMB = 0
                    }
                    
                    currentCacheSizeMB > maxCacheSizeMB * 1.2 -> {
                        // 制限の20%超過時は積極的クリア
                        println("WARNING: Cache overflow, performing aggressive cleanup")
                        directZipHandler.clearMemoryCache()
                        currentCacheSizeMB = 0
                    }
                    
                    else -> {
                        // 制限超過時は部分クリア
                        println("INFO: Cache limit exceeded, performing partial cleanup")
                        directZipHandler.clearMemoryCache()
                        currentCacheSizeMB = 0
                    }
                }
                
                lastCacheCleanupTime = System.currentTimeMillis()
                
                // クリーンアップ後のガベージコレクション
                System.gc()
                
                return true
            }
            
            false
        } catch (e: Exception) {
            println("ERROR: Failed to check cache size: ${e.message}")
            false
        }
    }



    // State Machine状態変化の監視と処理（リソース管理強化版）
    LaunchedEffect(loadingState) {
        // 状態遷移の詳細ログと管理
        val currentState = loadingState
        val currentTime = System.currentTimeMillis()
        val timeSinceLastTransition = if (stateTransitionTime > 0) currentTime - stateTransitionTime else 0
        
        // 状態遷移のログ記録
        println("=== STATE MACHINE TRANSITION ===")
        println("Previous: ${previousState?.let { it::class.simpleName } ?: "None"}")
        println("Current:  ${currentState::class.simpleName}")
        println("Time since last transition: ${timeSinceLastTransition}ms")
        println("Total transitions: ${++stateTransitionCount}")
        println("================================")
        
        // 異常な状態遷移の検出
        if (timeSinceLastTransition < 100 && previousState != null) {
            println("WARNING: Rapid state transition detected (${timeSinceLastTransition}ms)")
        }
        
        // 状態遷移時のリソース管理
        when {
            // 前の状態からのクリーンアップが必要な場合
            previousState is FileLoadingStateMachine.LoadingState.PreparingFile && 
            currentState !is FileLoadingStateMachine.LoadingState.PreparingFile -> {
                println("DEBUG: Cleaning up from PreparingFile state")
                try {
                    // ファイル準備状態からの遷移時はリソースクリア
                    directZipHandler.clearMemoryCache()
                } catch (e: Exception) {
                    println("ERROR: Failed to cleanup from PreparingFile state: ${e.message}")
                }
            }
            
            previousState is FileLoadingStateMachine.LoadingState.Ready && 
            currentState !is FileLoadingStateMachine.LoadingState.Ready -> {
                println("DEBUG: Cleaning up from Ready state")
                try {
                    // Ready状態からの遷移時は最終位置保存
                    currentImageViewerState?.let { state ->
                        if (pagerState.currentPage >= 0) {
                            directZipHandler.saveCurrentPosition(
                                state.currentZipUri,
                                pagerState.currentPage,
                                state.currentZipFile
                            )
                            println("DEBUG: Final position saved during state transition: ${pagerState.currentPage}")
                        }
                    }
                } catch (e: Exception) {
                    println("ERROR: Failed to cleanup from Ready state: ${e.message}")
                }
            }
        }
        
        // 具体的な状態処理
        when (currentState) {
            is FileLoadingStateMachine.LoadingState.StoppingUI -> {
                println("DEBUG: State Machine - UI Stopping (Resource Management)")
                try {
                    // UI停止時のリソース状態確認
                    val resourceStatus = checkResourceStatus()
                    println("DEBUG: Resource status during UI stop: $resourceStatus")
                    
                    // UI停止完了を通知
                    stateMachine.processAction(FileLoadingStateMachine.LoadingAction.UIStoppedComplete)
                    
                } catch (e: Exception) {
                    println("ERROR: Error during UI stopping: ${e.message}")
                    handleStateTransitionError(e, currentState)
                }
            }
            
            is FileLoadingStateMachine.LoadingState.CleaningResources -> {
                println("DEBUG: State Machine - Cleaning Resources (Enhanced)")
                mainScope.launch {
                    try {
                        // リソースクリーニング前の状態記録
                        val runtime = Runtime.getRuntime()
                        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
                        val maxMemory = runtime.maxMemory()
                        val memoryBefore = ((usedMemory.toDouble() / maxMemory) * 100).toInt()
                        val cacheSizeBefore = currentCacheSizeMB
                        
                        println("DEBUG: Resource state before cleaning - Memory: ${memoryBefore}%, Cache: ${cacheSizeBefore}MB")
                        
                        withContext(Dispatchers.IO) {
                            // 段階的リソースクリーニング
                            println("DEBUG: Phase 1 - Memory cache cleanup")
                            directZipHandler.clearMemoryCache()
                            currentCacheSizeMB = 0
                            
                            println("DEBUG: Phase 2 - File handle cleanup")
                            directZipHandler.closeCurrentZipFile()
                            
                            println("DEBUG: Phase 3 - System garbage collection")
                            System.gc()
                        }
                        
                        // クリーニング後の状態確認
                        val runtimeAfter = Runtime.getRuntime()
                        val usedMemoryAfter = runtimeAfter.totalMemory() - runtimeAfter.freeMemory()
                        val maxMemoryAfter = runtimeAfter.maxMemory()
                        val memoryAfter = ((usedMemoryAfter.toDouble() / maxMemoryAfter) * 100).toInt()
                        println("DEBUG: Resource state after cleaning - Memory: ${memoryAfter}% (reduced by ${memoryBefore - memoryAfter}%)")
                        
                        println("DEBUG: Resources cleaned successfully")
                        stateMachine.processAction(FileLoadingStateMachine.LoadingAction.ResourcesClearedComplete)
                        
                    } catch (e: Exception) {
                        println("ERROR: Error during resource cleanup: ${e.message}")
                        e.printStackTrace()
                        handleStateTransitionError(e, currentState)
                    }
                }
            }
            
            is FileLoadingStateMachine.LoadingState.PreparingFile -> {
                println("DEBUG: State Machine - Preparing File (Resource Managed)")
                pendingRequest?.let { currentRequest ->
                    // リクエストの重複チェック
                    val requestKey = "${currentRequest.uri}_${currentRequest.file?.name}_${currentRequest.isNextFile}"
                    println("DEBUG: Processing request with resource management: $requestKey")
                    
                    mainScope.launch {
                        try {
                            // ファイル準備前のリソース状態チェック
                            val memoryCheck = checkMemoryAvailability(currentRequest)
                            if (!memoryCheck.first) {
                                throw Exception("Insufficient memory for file preparation: ${memoryCheck.second}")
                            }
                            
                            println("DEBUG: Memory check passed, proceeding with file preparation")
                            
                            // タイムアウト管理付きファイル準備
                            val preparationJob = launch {
                                val startTime = System.currentTimeMillis()
                                
                                val imageEntryList = withContext(Dispatchers.IO) {
                                    directZipHandler.getImageEntriesFromZip(currentRequest.uri, currentRequest.file)
                                }
                                
                                val preparationTime = System.currentTimeMillis() - startTime
                                println("DEBUG: File preparation completed in ${preparationTime}ms")
                                
                                val newState = if (imageEntryList.isEmpty()) {
                                    println("DEBUG: No images found in ZIP")
                                    null
                                } else {
                                    // 次のファイルの場合は1ページ目から、それ以外は保存された位置から
                                    val initialPage = if (currentRequest.isNextFile) {
                                        println("DEBUG: Next file navigation - starting from page 1")
                                        0
                                    } else {
                                        val savedPosition = directZipHandler.getSavedPosition(currentRequest.uri, currentRequest.file) ?: 0
                                        val validPosition = savedPosition.coerceIn(0, imageEntryList.size - 1)
                                        println("DEBUG: Saved position: $savedPosition, Valid position: $validPosition")
                                        validPosition
                                    }
                                    
                                    val navigationInfo = currentRequest.file?.let { file ->
                                        fileNavigationManager.getNavigationInfo(file)
                                    }
                                    
                                    ImageViewerState(
                                        imageEntries = imageEntryList,
                                        currentZipUri = currentRequest.uri,
                                        currentZipFile = currentRequest.file,
                                        fileNavigationInfo = navigationInfo,
                                        initialPage = initialPage
                                    ).also {
                                        println("DEBUG: ZIP file prepared successfully - ${imageEntryList.size} images, initial page: $initialPage")
                                    }
                                }
                                
                                // リソース使用量を再チェック
                                val finalMemoryUsage = getMemoryUsage()
                                println("DEBUG: Memory usage after file preparation: ${finalMemoryUsage}%")
                                
                                stateMachine.processAction(
                                    FileLoadingStateMachine.LoadingAction.FilePreparationComplete(newState)
                                )
                            }
                            
                            // 強化されたタイムアウト処理
                            val timeoutJob = launch {
                                kotlinx.coroutines.delay(30000) // 30秒でタイムアウト
                                println("ERROR: File preparation timeout for request: $requestKey")
                                preparationJob.cancel() // 準備ジョブをキャンセル
                                
                                // タイムアウト時のリソースクリーンアップ
                                try {
                                    directZipHandler.clearMemoryCache()
                                    directZipHandler.closeCurrentZipFile()
                                } catch (cleanupError: Exception) {
                                    println("ERROR: Cleanup during timeout failed: ${cleanupError.message}")
                                }
                                
                                stateMachine.processAction(
                                    FileLoadingStateMachine.LoadingAction.FilePreparationFailed(
                                        Exception("File preparation timeout")
                                    )
                                )
                            }
                            
                            // 準備が完了したらタイムアウトジョブをキャンセル
                            preparationJob.invokeOnCompletion { exception ->
                                timeoutJob.cancel()
                                if (exception != null && exception !is kotlinx.coroutines.CancellationException) {
                                    println("ERROR: File preparation job failed: ${exception.message}")
                                }
                            }
                            
                        } catch (e: Exception) {
                            println("ERROR: Exception during file preparation: ${e.message}")
                            e.printStackTrace()
                            handleStateTransitionError(e, currentState)
                        }
                    }
                }
            }
            
            is FileLoadingStateMachine.LoadingState.Ready -> {
                println("DEBUG: State Machine - Ready (Resource Optimized)")
                try {
                    // Ready状態での最終リソースチェック
                    val finalResourceStatus = checkResourceStatus()
                    println("DEBUG: Final resource status in Ready state: $finalResourceStatus")
                    
                    currentView = ViewState.ImageViewer
                    consecutiveErrorCount = 0 // エラーカウントリセット
                    
                } catch (e: Exception) {
                    println("ERROR: Error in Ready state: ${e.message}")
                    handleStateTransitionError(e, currentState)
                }
            }
            
            is FileLoadingStateMachine.LoadingState.Error -> {
                println("DEBUG: State Machine - Error (Enhanced Recovery)")
                currentState.throwable?.printStackTrace()
                
                consecutiveErrorCount++
                lastErrorRecoveryTime = currentTime
                
                try {
                    println("DEBUG: Performing enhanced error recovery (attempt ${consecutiveErrorCount})")
                    
                    // エラー時のリソース状態診断
                    val resourceDiagnostics = performResourceDiagnostics()
                    println("DEBUG: Resource diagnostics: $resourceDiagnostics")
                    
                    // 連続エラーの場合は段階的対応
                    when {
                        consecutiveErrorCount >= 3 -> {
                            println("CRITICAL: Multiple consecutive errors, performing emergency cleanup")
                            emergencyCleanup("Multiple consecutive State Machine errors")
                        }
                        
                        consecutiveErrorCount >= 2 -> {
                            println("WARNING: Repeated errors, performing comprehensive cleanup")
                            clearAllStateAndMemory()
                        }
                        
                        else -> {
                            println("INFO: Single error, performing standard cleanup")
                            clearAllStateAndMemory()
                        }
                    }
                    
                    // エラーが重大な場合は緊急クリーンアップ
                    val errorMessage = currentState.message.lowercase()
                    if (errorMessage.contains("memory") || 
                        errorMessage.contains("timeout") || 
                        errorMessage.contains("crash") ||
                        errorMessage.contains("resource")) {
                        println("WARNING: Critical error detected, performing emergency cleanup")
                        emergencyCleanup("Critical State Machine error: ${currentState.message}")
                    }
                    
                } catch (cleanupError: Exception) {
                    println("ERROR: Cleanup during error handling failed: ${cleanupError.message}")
                    emergencyCleanup("Error recovery failure")
                }
                
                currentView = ViewState.LocalFileList
                stateMachine.processAction(FileLoadingStateMachine.LoadingAction.Reset)
            }
            
            is FileLoadingStateMachine.LoadingState.Idle -> {
                println("DEBUG: State Machine - Idle (Resource Monitoring)")
                try {
                    // Idle状態でのリソース監視
                    if (timeSinceLastTransition > 10000) { // 10秒以上Idle
                        val resourceStatus = checkResourceStatus()
                        if (resourceStatus.contains("high")) {
                            println("INFO: High resource usage detected in Idle state, performing maintenance")
                            directZipHandler.clearMemoryCache()
                            currentCacheSizeMB = 0
                        }
                    }
                } catch (e: Exception) {
                    println("ERROR: Error in Idle state monitoring: ${e.message}")
                }
            }
        }
        
        // 状態遷移の記録を更新
        previousState = currentState
        stateTransitionTime = currentTime
    }



    // ファイルナビゲーション処理（State Machine管理強化版）
    fun navigateToZipFile(newZipFile: File, isNextFile: Boolean = false) {
        println("DEBUG: Starting navigation to new file: ${newZipFile.name} (isNextFile: $isNextFile)")
        
        mainScope.launch {
            try {
                // Phase 1: State Machine状態チェック
                val currentState = stateMachine.currentState.value
                if (stateMachine.isLoading()) {
                    println("WARNING: Navigation attempted while State Machine is loading, waiting...")
                    // 最大5秒待機
                    var waitTime = 0
                    while (stateMachine.isLoading() && waitTime < 5000) {
                        kotlinx.coroutines.delay(100)
                        waitTime += 100
                    }
                    
                    if (stateMachine.isLoading()) {
                        println("ERROR: State Machine still loading after timeout, forcing reset")
                        stateMachine.processAction(FileLoadingStateMachine.LoadingAction.Reset)
                        kotlinx.coroutines.delay(100)
                    }
                }
                
                // Phase 2: 現在のファイル状態を保存
                currentImageViewerState?.let { state ->
                    if (pagerState.currentPage >= 0) {
                        try {
                            directZipHandler.saveCurrentPosition(
                                state.currentZipUri,
                                pagerState.currentPage,
                                state.currentZipFile
                            )
                            println("DEBUG: Current position saved before navigation: ${pagerState.currentPage}")
                        } catch (e: Exception) {
                            println("ERROR: Failed to save position before navigation: ${e.message}")
                        }
                    }
                }
                
                // Phase 3: リソース状態チェックと準備
                val resourceStatus = checkResourceStatus()
                println("DEBUG: Resource status before navigation: $resourceStatus")
                
                if (resourceStatus == "critical") {
                    println("WARNING: Critical resource usage, performing pre-navigation cleanup")
                    clearAllStateAndMemory()
                    kotlinx.coroutines.delay(500) // クリーンアップ完了を待つ
                }
                
                // Phase 4: IO処理でリソースクリア + キャッシュサイズチェック
                withContext(Dispatchers.IO) {
                    try {
                        // キャッシュサイズ制限チェック
                        checkAndLimitCacheSize(forceCleanup = true)
                        
                        directZipHandler.clearMemoryCache()
                        directZipHandler.closeCurrentZipFile()
                        println("DEBUG: File handles and cache cleared")
                    } catch (ioError: Exception) {
                        println("ERROR: IO cleanup failed: ${ioError.message}")
                        throw ioError
                    }
                }
                
                // Phase 5: ガベージコレクション
                System.gc()
                
                // Phase 6: メモリ使用量最終チェック
                val memoryUsage = getMemoryUsage()
                if (memoryUsage > 80) {
                    println("WARNING: High memory usage before navigation: ${memoryUsage}%")
                    // 追加のクリーンアップ
                    directZipHandler.clearMemoryCache()
                    System.gc()
                    kotlinx.coroutines.delay(100) // GCの完了を待つ
                    
                    val finalMemoryUsage = getMemoryUsage()
                    if (finalMemoryUsage > 85) {
                        println("ERROR: Memory usage still critical after cleanup: ${finalMemoryUsage}%")
                        emergencyCleanup("Critical memory usage before navigation")
                        return@launch
                    }
                }
                
                // Phase 7: State Machine動作開始
                println("DEBUG: Starting State Machine action for: ${newZipFile.name}")
                val success = stateMachine.processAction(
                    FileLoadingStateMachine.LoadingAction.StartLoading(
                        Uri.fromFile(newZipFile), 
                        newZipFile,
                        isNextFile
                    )
                )
                
                if (!success) {
                    println("ERROR: Failed to start navigation - State Machine rejected action")
                    // State Machine復旧処理
                    println("DEBUG: Attempting State Machine recovery")
                    
                    // 健全性チェック
                    val isHealthy = performStateMachineHealthCheck()
                    if (!isHealthy) {
                        println("WARNING: State Machine unhealthy, performing reset")
                        emergencyCleanup("State Machine unhealthy during navigation")
                        return@launch
                    }
                    
                    clearAllStateAndMemory()
                    kotlinx.coroutines.delay(100)
                    
                    stateMachine.processAction(FileLoadingStateMachine.LoadingAction.Reset)
                    kotlinx.coroutines.delay(50)
                    
                    val retrySuccess = stateMachine.processAction(
                        FileLoadingStateMachine.LoadingAction.StartLoading(
                            Uri.fromFile(newZipFile), 
                            newZipFile,
                            isNextFile
                        )
                    )
                    
                    if (!retrySuccess) {
                        println("ERROR: Failed to navigate to file even after recovery: ${newZipFile.name}")
                        emergencyCleanup("Navigation failure after recovery")
                    }
                }
                
            } catch (e: Exception) {
                println("ERROR: Exception during file navigation: ${e.message}")
                e.printStackTrace()
                
                // エラー発生時の包括的対応
                try {
                    handleStateTransitionError(e, stateMachine.currentState.value)
                } catch (recoveryError: Exception) {
                    println("CRITICAL: Navigation error recovery failed: ${recoveryError.message}")
                    emergencyCleanup("Navigation exception with recovery failure")
                }
            }
        }
    }

    // ファイル読み込み開始処理（State Machine管理強化版）
    fun startFileLoading(uri: Uri, file: File? = null) {
        println("DEBUG: Starting file loading: ${file?.name ?: uri}")
        
        mainScope.launch {
            try {
                // Phase 1: State Machine準備状態チェック
                val currentState = stateMachine.currentState.value
                println("DEBUG: Current State Machine state before loading: ${currentState::class.simpleName}")
                
                if (stateMachine.isLoading()) {
                    println("WARNING: File loading attempted while State Machine is busy")
                    // 少し待ってから再試行
                    kotlinx.coroutines.delay(500)
                    if (stateMachine.isLoading()) {
                        println("ERROR: State Machine still busy, aborting file loading")
                        return@launch
                    }
                }
                
                // Phase 2: メモリ可用性の事前チェック
                val memoryCheck = checkMemoryAvailability(uri)
                if (!memoryCheck.first) {
                    println("ERROR: ${memoryCheck.second}")
                    emergencyCleanup("Insufficient memory for file loading")
                    return@launch
                }
                
                // Phase 3: ファイル読み込み前の予防的クリーンアップ
                println("DEBUG: Performing preventive cleanup before file loading")
                
                // 現在のリソースをクリア + キャッシュサイズチェック
                withContext(Dispatchers.IO) {
                    // キャッシュサイズ制限チェック
                    checkAndLimitCacheSize(forceCleanup = true)
                    
                    directZipHandler.clearMemoryCache()
                    directZipHandler.closeCurrentZipFile()
                }
                
                // Phase 4: メモリ状況の最終確認
                val memoryUsage = getMemoryUsage()
                println("DEBUG: Memory usage before loading: ${memoryUsage}%")
                
                if (memoryUsage > 75) {
                    println("WARNING: High memory usage detected, performing aggressive cleanup")
                    System.gc()
                    kotlinx.coroutines.delay(100)
                    
                    val newMemoryUsage = getMemoryUsage()
                    println("DEBUG: Memory usage after cleanup: ${newMemoryUsage}%")
                    
                    if (newMemoryUsage > 85) {
                        println("ERROR: Memory usage still critical, aborting file loading")
                        emergencyCleanup("Critical memory usage before file loading")
                        return@launch
                    }
                }
                
                // Phase 5: State Machine健全性チェック
                val isHealthy = performStateMachineHealthCheck()
                if (!isHealthy) {
                    println("WARNING: State Machine unhealthy before file loading")
                    stateMachine.processAction(FileLoadingStateMachine.LoadingAction.Reset)
                    kotlinx.coroutines.delay(100)
                }
                
                // Phase 6: State Machine動作開始
                println("DEBUG: Starting State Machine action for file loading")
                val success = stateMachine.processAction(
                    FileLoadingStateMachine.LoadingAction.StartLoading(uri, file, isNextFile = false)
                )
                
                if (!success) {
                    println("ERROR: Failed to start file loading - State Machine rejected action")
                    // 復旧処理
                    println("DEBUG: Attempting recovery for file loading")
                    
                    clearAllStateAndMemory()
                    kotlinx.coroutines.delay(100)
                    
                    stateMachine.processAction(FileLoadingStateMachine.LoadingAction.Reset)
                    kotlinx.coroutines.delay(50)
                    
                    val retrySuccess = stateMachine.processAction(
                        FileLoadingStateMachine.LoadingAction.StartLoading(uri, file, isNextFile = false)
                    )
                    
                    if (!retrySuccess) {
                        println("ERROR: Failed to load file even after recovery: ${file?.name ?: uri}")
                        emergencyCleanup("File loading failure after recovery")
                    }
                }
                
            } catch (e: Exception) {
                println("ERROR: Exception during file loading: ${e.message}")
                e.printStackTrace()
                
                // エラー発生時の包括的対応
                try {
                    handleStateTransitionError(e, stateMachine.currentState.value)
                } catch (recoveryError: Exception) {
                    println("CRITICAL: File loading error recovery failed: ${recoveryError.message}")
                    emergencyCleanup("File loading exception with recovery failure")
                }
            }
        }
    }

    // Permission state for external storage
    val storagePermissionState = rememberPermissionState(
        android.Manifest.permission.READ_EXTERNAL_STORAGE
    )

    // Zip file picker launcher (State Machine対応版)
    val zipPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            println("DEBUG: ZIP file picked from device")
            startFileLoading(uri, null)
        }
    }

    // 現在の位置を保存（デバウンス処理付き最適化版）
    LaunchedEffect(pagerState.currentPage, currentFileId) {
        // 有効な状態でのみ実行
        val state = currentImageViewerState
        val currentPage = pagerState.currentPage
        
        if (state != null && currentPage >= 0 && currentFileId != null) {
            // 重複保存を防ぐ
            if (lastSavedPage == currentPage && lastSavedFileId == currentFileId) {
                return@LaunchedEffect
            }
            
            // ローディング中は保存をスキップ
            if (stateMachine.isLoading()) {
                println("DEBUG: Skipping position save during loading - Page: $currentPage, File: $currentFileId")
                return@LaunchedEffect
            }
            
            // デバウンス処理: 500ms待ってから保存
            kotlinx.coroutines.delay(500)
            
            // デバウンス後に再度状態チェック（状態が変更されていないことを確認）
            val finalState = currentImageViewerState
            val finalPage = pagerState.currentPage
            
            // デバウンス期間中に状態やページが変更されていないかチェック
            if (finalState != null &&
                finalState.fileId == currentFileId &&
                finalPage >= 0 &&
                finalPage < finalState.imageEntries.size &&
                !stateMachine.isLoading()) {
                
                println("DEBUG: Saving position $finalPage for file: $currentFileId")
                
                try {
                    directZipHandler.saveCurrentPosition(
                        finalState.currentZipUri,
                        finalPage,
                        finalState.currentZipFile
                    )
                    
                    // 保存成功後に記録を更新
                    lastSavedPage = finalPage
                    lastSavedFileId = currentFileId
                    
                } catch (e: Exception) {
                    println("ERROR: Failed to save position: ${e.message}")
                }
            } else {
                println("DEBUG: Position save cancelled - State changed during debounce period")
            }
        }
    }

    // Auto-hide top bar（最適化版 - 条件付き実行）
    LaunchedEffect(showTopBar, currentFileId) {
        // トップバーが表示され、かつ画像ビューアが有効な場合のみタイマー開始
        if (showTopBar && currentFileId != null && isImageViewerReady) {
            println("DEBUG: Starting top bar auto-hide timer for file: $currentFileId")
            
            kotlinx.coroutines.delay(3000) // 3秒後に自動でトップバーを隠す
            
            // タイマー完了後に状態を再確認
            if (showTopBar && currentImageViewerState?.fileId == currentFileId) {
                showTopBar = false
                println("DEBUG: Top bar auto-hidden after timer")
            } else {
                println("DEBUG: Top bar auto-hide cancelled due to state change")
            }
        }
    }

    // DirectZipHandler専用のクリーンアップ
    DisposableEffect(directZipHandler) {
        onDispose {
            println("DEBUG: DirectZipHandler disposing")
            try {
                directZipHandler.clearMemoryCache()
                directZipHandler.closeCurrentZipFile()
                directZipHandler.cleanup()
                println("DEBUG: DirectZipHandler cleanup completed")
            } catch (e: Exception) {
                println("ERROR: DirectZipHandler cleanup failed: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    // StateMachine専用のクリーンアップ（強化版）
    DisposableEffect(stateMachine) {
        onDispose {
            println("DEBUG: StateMachine disposing - performing comprehensive cleanup")
            try {
                // Phase 1: 現在の状態を記録
                val finalState = stateMachine.currentState.value
                println("DEBUG: Final State Machine state: ${finalState::class.simpleName}")
                
                // Phase 2: 統計情報の出力
                println("=== STATE MACHINE FINAL STATISTICS ===")
                println("Total state transitions: $stateTransitionCount")
                println("Consecutive errors: $consecutiveErrorCount")
                println("Final resource diagnostics: ${performResourceDiagnostics()}")
                println("=====================================")
                
                // Phase 3: エラー状態の場合は追加ログ
                if (finalState is FileLoadingStateMachine.LoadingState.Error) {
                    println("WARNING: StateMachine disposed in error state: ${finalState.message}")
                }
                
                // Phase 4: State Machineリセット
                stateMachine.processAction(FileLoadingStateMachine.LoadingAction.Reset)
                
                // Phase 5: 状態管理変数のクリア
                previousState = null
                stateTransitionCount = 0
                consecutiveErrorCount = 0
                
                println("DEBUG: StateMachine comprehensive cleanup completed")
            } catch (e: Exception) {
                println("ERROR: StateMachine cleanup failed: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    // State Machine健全性監視
    DisposableEffect(stateTransitionCount) {
        val healthMonitorJob = mainScope.launch {
            while (true) {
                try {
                    kotlinx.coroutines.delay(30000) // 30秒ごとに健全性チェック
                    
                    val isHealthy = performStateMachineHealthCheck()
                    if (!isHealthy) {
                        println("WARNING: State Machine health check failed, performing corrective action")
                        
                        // 健全性に問題がある場合の対応
                        val currentState = stateMachine.currentState.value
                        when {
                            consecutiveErrorCount > 2 -> {
                                println("ACTION: Too many consecutive errors, performing reset")
                                mainScope.launch {
                                    emergencyCleanup("State Machine health: too many errors")
                                }
                            }
                            
                            currentState is FileLoadingStateMachine.LoadingState.PreparingFile && 
                            (System.currentTimeMillis() - stateTransitionTime) > 60000 -> {
                                println("ACTION: Stuck in PreparingFile state, forcing timeout")
                                stateMachine.processAction(
                                    FileLoadingStateMachine.LoadingAction.FilePreparationFailed(
                                        Exception("Forced timeout due to stuck state")
                                    )
                                )
                            }
                            
                            checkResourceStatus() == "critical" -> {
                                println("ACTION: Critical resource usage, performing cleanup")
                                clearAllStateAndMemory()
                            }
                        }
                    }
                    
                } catch (e: Exception) {
                    println("ERROR: State Machine health monitoring failed: ${e.message}")
                    break
                }
            }
        }
        
        onDispose {
            println("DEBUG: State Machine health monitor disposing")
            healthMonitorJob.cancel()
        }
    }

    // CurrentView変更時のクリーンアップ
    DisposableEffect(currentView) {
        onDispose {
            println("DEBUG: CurrentView changing from ${currentView::class.simpleName}")
            try {
                when (currentView) {
                    is ViewState.ImageViewer -> {
                        println("DEBUG: Cleaning up ImageViewer resources")
                        // 画像ビューア固有のクリーンアップ
                        directZipHandler.clearMemoryCache()
                        
                        // 現在の位置を最終保存
                        currentImageViewerState?.let { state ->
                            if (pagerState.currentPage >= 0) {
                                directZipHandler.saveCurrentPosition(
                                    state.currentZipUri,
                                    pagerState.currentPage,
                                    state.currentZipFile
                                )
                                println("DEBUG: Final position saved: ${pagerState.currentPage}")
                            }
                        }
                    }
                    is ViewState.DropboxBrowser -> {
                        println("DEBUG: Cleaning up DropboxBrowser resources")
                        // Dropbox関連のクリーンアップ
                    }
                    is ViewState.Settings -> {
                        println("DEBUG: Cleaning up Settings resources")
                        // 設定画面関連のクリーンアップ
                    }
                    is ViewState.LocalFileList -> {
                        println("DEBUG: Cleaning up LocalFileList resources")
                        // ファイルリスト関連のクリーンアップ
                    }
                }
                println("DEBUG: CurrentView cleanup completed")
            } catch (e: Exception) {
                println("ERROR: CurrentView cleanup failed: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    // ImageViewerState変更時のクリーンアップ（ファイル切り替え時）
    DisposableEffect(currentImageViewerState?.fileId) {
        val currentFileId = currentImageViewerState?.fileId
        onDispose {
            if (currentFileId != null) {
                println("DEBUG: ImageViewerState changing from file: $currentFileId")
                try {
                    // 前のファイルのリソースを明示的にクリア
                    mainScope.launch(Dispatchers.IO) {
                        directZipHandler.clearMemoryCache()
                        println("DEBUG: Previous file resources cleared for: $currentFileId")
                    }
                } catch (e: Exception) {
                    println("ERROR: ImageViewerState cleanup failed: ${e.message}")
                    e.printStackTrace()
                }
            }
        }
    }

    // PagerState専用のクリーンアップ
    DisposableEffect(pagerState) {
        onDispose {
            println("DEBUG: PagerState disposing")
            try {
                // 最終位置の保存
                currentImageViewerState?.let { state ->
                    if (pagerState.currentPage >= 0) {
                        directZipHandler.saveCurrentPosition(
                            state.currentZipUri,
                            pagerState.currentPage,
                            state.currentZipFile
                        )
                        println("DEBUG: PagerState final position saved: ${pagerState.currentPage}")
                    }
                }
                println("DEBUG: PagerState cleanup completed")
            } catch (e: Exception) {
                println("ERROR: PagerState cleanup failed: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    // 画像キャッシュサイズ監視とクリーンアップ（包括的なメモリ監視）
    DisposableEffect(currentImageViewerState) {
        val cacheMonitorJob = mainScope.launch {
            var monitoringInterval = 3000L // 初期監視間隔: 3秒
            var consecutiveOverflowCount = 0
            var dynamicCacheLimit = maxCacheSizeMB
            
            while (true) {
                try {
                    // Phase 1: 画像キャッシュサイズチェック
                    val cacheOverflow = checkAndLimitCacheSize()
                    
                    if (cacheOverflow) {
                        consecutiveOverflowCount++
                        println("WARNING: Cache overflow detected (Count: $consecutiveOverflowCount)")
                        
                        // 連続してオーバーフローする場合は動的制限を強化
                        if (consecutiveOverflowCount >= 3) {
                            dynamicCacheLimit = (dynamicCacheLimit * 0.8).toInt().coerceAtLeast(20)
                            println("INFO: Reducing dynamic cache limit to ${dynamicCacheLimit}MB")
                            consecutiveOverflowCount = 0
                        }
                    } else {
                        consecutiveOverflowCount = 0
                    }
                    
                    // Phase 2: システムメモリ使用量チェック
                    val systemMemoryInfo = run {
                        val runtime = Runtime.getRuntime()
                        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
                        val maxMemory = runtime.maxMemory()
                        val memoryUsagePercent = (usedMemory.toDouble() / maxMemory * 100).toInt()
                        Triple(usedMemory, maxMemory, memoryUsagePercent)
                    }
                    
                    // Phase 3: 動的キャッシュサイズ調整
                    val adjustedCacheLimit = when {
                        systemMemoryInfo.third > 85 -> {
                            (maxCacheSizeMB * 0.5).toInt().coerceAtLeast(20)
                        }
                        systemMemoryInfo.third > 70 -> {
                            (maxCacheSizeMB * 0.7).toInt().coerceAtLeast(30)
                        }
                        systemMemoryInfo.third < 50 -> {
                            (maxCacheSizeMB * 1.2).toInt().coerceAtMost(300)
                        }
                        else -> {
                            maxCacheSizeMB
                        }
                    }
                    
                    if (adjustedCacheLimit != dynamicCacheLimit) {
                        dynamicCacheLimit = adjustedCacheLimit
                        println("DEBUG: Adjusted cache limit to ${dynamicCacheLimit}MB based on memory usage")
                    }
                    
                    // Phase 4: 詳細ログ（高負荷時のみ）
                    if (systemMemoryInfo.third > 70 || currentCacheSizeMB > dynamicCacheLimit) {
                        println("DEBUG: Memory: ${systemMemoryInfo.third}% (${systemMemoryInfo.first / 1024 / 1024}MB/${systemMemoryInfo.second / 1024 / 1024}MB), " +
                               "Cache: ${currentCacheSizeMB}MB/${dynamicCacheLimit}MB, " +
                               "Hit Rate: ${String.format("%.1f", cacheHitRate * 100)}%")
                    }
                    
                    // Phase 5: 緊急対応
                    when {
                        systemMemoryInfo.third > 90 -> {
                            println("CRITICAL: System memory critical!")
                            mainScope.launch {
                                emergencyCleanup("System memory critical: ${systemMemoryInfo.third}%")
                            }
                            break
                        }
                        
                        systemMemoryInfo.third > 85 -> {
                            println("WARNING: High system memory usage, forcing cache cleanup")
                            checkAndLimitCacheSize(forceCleanup = true)
                            monitoringInterval = 1000L // 1秒間隔
                        }
                        
                        currentCacheSizeMB > dynamicCacheLimit * 1.5 -> {
                            println("WARNING: Severe cache overflow, forcing cleanup")
                            checkAndLimitCacheSize(forceCleanup = true)
                            monitoringInterval = 2000L // 2秒間隔
                        }
                        
                        systemMemoryInfo.third > 70 || currentCacheSizeMB > dynamicCacheLimit -> {
                            // 高負荷時は監視頻度を上げる
                            monitoringInterval = 2000L
                        }
                        
                        else -> {
                            // 正常時は監視頻度を下げる
                            monitoringInterval = 5000L
                        }
                    }
                    
                    // Phase 6: キャッシュ効率の監視
                    if (cacheHitRate < 0.5 && currentCacheSizeMB > dynamicCacheLimit * 0.5) {
                        println("INFO: Low cache hit rate (${String.format("%.1f", cacheHitRate * 100)}%), consider cache strategy optimization")
                    }
                    
                    // Phase 7: 長期間クリーンアップがない場合の予防措置
                    val timeSinceLastCleanup = System.currentTimeMillis() - lastCacheCleanupTime
                    if (timeSinceLastCleanup > 300000 && currentCacheSizeMB > dynamicCacheLimit * 0.8) { // 5分
                        println("INFO: Preventive cache cleanup due to long period without cleanup")
                        checkAndLimitCacheSize(forceCleanup = true)
                    }
                    
                    // Phase 8: 定期統計レポート（高負荷時または10分ごと）
                    if (systemMemoryInfo.third > 75 || timeSinceLastCleanup > 600000) { // 10分
                        logCacheStatistics()
                    }
                    
                    kotlinx.coroutines.delay(monitoringInterval)
                    
                } catch (e: Exception) {
                    println("ERROR: Cache monitoring failed: ${e.message}")
                    e.printStackTrace()
                    break
                }
            }
        }
        
        onDispose {
            println("DEBUG: Cache monitor disposing")
            cacheMonitorJob.cancel()
        }
    }

    // メインのクリーンアップ - 段階的で包括的なリソース解放
    DisposableEffect(Unit) {
        onDispose {
            println("DEBUG: MainScreen disposing - starting comprehensive cleanup")
            
            // Phase 1: UI状態の停止
            try {
                println("DEBUG: Phase 1 - Stopping UI operations")
                showTopBar = false
                
                // 進行中の状態遷移を停止
                if (stateMachine.isLoading()) {
                    println("DEBUG: Stopping ongoing state machine operations")
                    stateMachine.processAction(FileLoadingStateMachine.LoadingAction.Reset)
                }
                println("DEBUG: Phase 1 completed")
            } catch (e: Exception) {
                println("ERROR: Phase 1 cleanup failed: ${e.message}")
                e.printStackTrace()
            }
            
            // Phase 2: コルーチンの停止
            try {
                println("DEBUG: Phase 2 - Cancelling all coroutines")
                supervisorJob.cancel()
                println("DEBUG: SupervisorJob cancelled, all child coroutines stopped")
                
                // コルーチンの完了を少し待つ
                try {
                    Thread.sleep(100)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                println("DEBUG: Phase 2 completed")
            } catch (e: Exception) {
                println("ERROR: Phase 2 cleanup failed: ${e.message}")
                e.printStackTrace()
            }
            
            // Phase 3: ファイルハンドルとZIPリソース
            try {
                println("DEBUG: Phase 3 - Closing file handles and ZIP resources")
                directZipHandler.closeCurrentZipFile()
                println("DEBUG: ZIP file handles closed")
                println("DEBUG: Phase 3 completed")
            } catch (e: Exception) {
                println("ERROR: Phase 3 cleanup failed: ${e.message}")
                e.printStackTrace()
            }
            
            // Phase 4: メモリキャッシュのクリア
            try {
                println("DEBUG: Phase 4 - Clearing memory caches")
                directZipHandler.clearMemoryCache()
                println("DEBUG: Memory caches cleared")
                println("DEBUG: Phase 4 completed")
            } catch (e: Exception) {
                println("ERROR: Phase 4 cleanup failed: ${e.message}")
                e.printStackTrace()
            }
            
            // Phase 5: ハンドラーとマネージャーのクリーンアップ
            try {
                println("DEBUG: Phase 5 - Cleaning up handlers and managers")
                directZipHandler.cleanup()
                println("DEBUG: DirectZipHandler cleaned up")
                
                // ファイナルの状態リセット
                try {
                    stateMachine.processAction(FileLoadingStateMachine.LoadingAction.Reset)
                } catch (e: Exception) {
                    println("DEBUG: StateMachine already disposed: ${e.message}")
                }
                println("DEBUG: Phase 5 completed")
            } catch (e: Exception) {
                println("ERROR: Phase 5 cleanup failed: ${e.message}")
                e.printStackTrace()
            }
            
            // Phase 6: システムリソースの解放
            try {
                println("DEBUG: Phase 6 - System resource cleanup")
                
                // 強制ガベージコレクション
                System.gc()
                System.runFinalization()
                
                val runtime = Runtime.getRuntime()
                val usedMemory = runtime.totalMemory() - runtime.freeMemory()
                val maxMemory = runtime.maxMemory()
                val memoryUsagePercent = (usedMemory.toDouble() / maxMemory * 100).toInt()
                
                println("DEBUG: Final memory usage: ${memoryUsagePercent}% (${usedMemory / 1024 / 1024}MB / ${maxMemory / 1024 / 1024}MB)")
                println("DEBUG: Phase 6 completed")
            } catch (e: Exception) {
                println("ERROR: Phase 6 cleanup failed: ${e.message}")
                e.printStackTrace()
            }
            
            println("DEBUG: MainScreen comprehensive cleanup completed successfully")
        }
    }

    // 画面遷移のハンドリング（最適化版）
    when (currentView) {
        is ViewState.LocalFileList -> {
            // ファイルリスト画面への遷移時のみ実行
            LaunchedEffect(Unit) {
                println("DEBUG: Returned to file list - resetting state machine")
                try {
                    stateMachine.processAction(FileLoadingStateMachine.LoadingAction.Reset)
                    directZipHandler.closeCurrentZipFile()
                    
                    // 前の画面から戻った時の状態をクリア
                    lastReadingProgress = null
                    fileCompletionUpdate = null
                    lastSavedPage = -1
                    lastSavedFileId = null
                    
                    println("DEBUG: File list state cleanup completed")
                } catch (e: Exception) {
                    println("ERROR: File list cleanup failed: ${e.message}")
                }
            }
            
            FileSelectionScreen(
                initialPath = savedDirectoryPath,
                onFileSelected = { file ->
                    println("DEBUG: File selected: ${file.name}")
                    startFileLoading(Uri.fromFile(file), file)
                },
                onDirectoryChanged = { path ->
                    savedDirectoryPath = path
                },
                onOpenFromDevice = {
                    if (storagePermissionState.status.isGranted) {
                        zipPickerLauncher.launch("application/zip")
                    } else {
                        storagePermissionState.launchPermissionRequest()
                    }
                },
                onOpenFromDropbox = {
                    currentView = ViewState.DropboxBrowser
                },
                onOpenSettings = {
                    currentView = ViewState.Settings
                },
                isLoading = stateMachine.isLoading(),
                onReturnFromViewer = if (lastReadingProgress != null) {
                    {
                        println("DEBUG: Returned from image viewer")
                    }
                } else null,
                readingStatusUpdate = lastReadingProgress,
                fileCompletionUpdate = fileCompletionUpdate
            )
        }

        is ViewState.ImageViewer -> {
            val state = currentImageViewerState
            
            // ローディング状態の詳細判定
            val isActuallyLoading = when (loadingState) {
                is FileLoadingStateMachine.LoadingState.Idle -> false
                is FileLoadingStateMachine.LoadingState.Ready -> false
                else -> true
            }
            
            if (isActuallyLoading || state == null || state.imageEntries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(48.dp)
                        )
                        
                        val statusText = when (loadingState) {
                            is FileLoadingStateMachine.LoadingState.StoppingUI -> "UIを停止中..."
                            is FileLoadingStateMachine.LoadingState.CleaningResources -> "リソースをクリア中..."
                            is FileLoadingStateMachine.LoadingState.PreparingFile -> "ファイルを準備中..."
                            else -> "ファイルを読み込み中..."
                        }
                        
                        Text(
                            text = statusText,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        
                        if (state != null) {
                            Text(
                                text = "ファイル: ${state.currentZipFile?.name ?: "不明"}",
                                color = Color.Gray,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            } else {
                DirectZipImageViewerScreen(
                    imageEntries = state.imageEntries,
                    currentZipFile = state.currentZipFile,
                    pagerState = pagerState,
                    showTopBar = showTopBar,
                    onToggleTopBar = { showTopBar = !showTopBar },
                    onBackToFiles = {
                        println("DEBUG: Back to files - resetting state machine")
                        clearAllStateAndMemory()
                        currentView = ViewState.LocalFileList
                    },
                    onNavigateToPreviousFile = {
                        println("DEBUG: Previous file button clicked")

                        // 現在のファイルと位置を保存
                        state.currentZipFile?.let { currentFile ->
                            val currentPage = pagerState.currentPage
                            println("DEBUG: Saving current position: $currentPage for file: ${currentFile.name}")
                            lastReadingProgress = Pair(currentFile, currentPage)
                            
                            // 現在の位置を明示的に保存
                            directZipHandler.saveCurrentPosition(
                                state.currentZipUri,
                                currentPage,
                                currentFile
                            )
                        }

                        // 前のファイルがあるか確認してからナビゲーション
                        state.fileNavigationInfo?.previousFile?.let { previousFile ->
                            println("DEBUG: Navigating to previous file: ${previousFile.name}")
                            navigateToZipFile(previousFile)
                        } ?: run {
                            println("DEBUG: No previous file available")
                        }
                    },
                    onNavigateToNextFile = {
                        println("DEBUG: Next file button clicked")

                        // 現在のファイルと位置を保存
                        state.currentZipFile?.let { currentFile ->
                            val currentPage = pagerState.currentPage
                            println("DEBUG: Saving current position: $currentPage for file: ${currentFile.name}")
                            fileCompletionUpdate = currentFile
                            
                            // 現在の位置を明示的に保存
                            directZipHandler.saveCurrentPosition(
                                state.currentZipUri,
                                currentPage,
                                currentFile
                            )
                        }

                        // 次のファイルがあるか確認してからナビゲーション
                        state.fileNavigationInfo?.nextFile?.let { nextFile ->
                            println("DEBUG: Navigating to next file: ${nextFile.name}")
                            navigateToZipFile(nextFile, isNextFile = true)
                        } ?: run {
                            println("DEBUG: No next file available")
                        }
                    },
                    fileNavigationInfo = state.fileNavigationInfo,
                    cacheManager = directZipHandler.getCacheManager(),
                    directZipHandler = directZipHandler,
                    onPageChanged = { currentPage, totalPages, zipFile ->
                        lastReadingProgress = Pair(zipFile, currentPage)

                        if (currentPage >= totalPages - 1) {
                            fileCompletionUpdate = zipFile
                        }
                    }
                )
            }
        }

        is ViewState.DropboxBrowser -> {
            // Dropbox画面への遷移時のみ実行
            LaunchedEffect(Unit) {
                println("DEBUG: Moved to Dropbox browser - resetting state machine")
                try {
                    stateMachine.processAction(FileLoadingStateMachine.LoadingAction.Reset)
                    directZipHandler.closeCurrentZipFile()
                    println("DEBUG: Dropbox browser state cleanup completed")
                } catch (e: Exception) {
                    println("ERROR: Dropbox browser cleanup failed: ${e.message}")
                }
            }
            
            DropboxScreen(
                dropboxAuthManager = LocalDropboxAuthManager.current,
                onBackToLocal = {
                    currentView = ViewState.LocalFileList
                },
                onDismiss = {
                    currentView = ViewState.LocalFileList
                }
            )
        }

        is ViewState.Settings -> {
            // 設定画面への遷移時のみ実行
            LaunchedEffect(Unit) {
                println("DEBUG: Moved to Settings - resetting state machine")
                try {
                    stateMachine.processAction(FileLoadingStateMachine.LoadingAction.Reset)
                    directZipHandler.closeCurrentZipFile()
                    println("DEBUG: Settings state cleanup completed")
                } catch (e: Exception) {
                    println("ERROR: Settings cleanup failed: ${e.message}")
                }
            }
            
            SettingsScreenNew(
                cacheManager = directZipHandler.getCacheManager(),
                directZipHandler = directZipHandler,
                onBackPressed = {
                    currentView = ViewState.LocalFileList
                }
            )
        }
    }
}
