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

    // 読書状態更新のための状態
    var lastReadingProgress by remember { mutableStateOf<Pair<File, Int>?>(null) }
    var fileCompletionUpdate by remember { mutableStateOf<File?>(null) }
    var showTopBar by remember { mutableStateOf(false) }

    // State for directory navigation
    var savedDirectoryPath by remember { mutableStateOf("") }

    // 直接ZIP画像ハンドラー
    val directZipHandler = remember { DirectZipImageHandler(context) }

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

    // ファイルが変更されたときに保存されたページに移動
    LaunchedEffect(currentImageViewerState?.fileId, currentImageViewerState?.imageEntries?.size) {
        val state = currentImageViewerState
        if (state != null && state.imageEntries.isNotEmpty() && !stateMachine.isLoading()) {
            val targetPage = state.initialPage.coerceIn(0, state.imageEntries.size - 1)
            println("DEBUG: Moving to saved position: $targetPage for file: ${state.fileId} (total: ${state.imageEntries.size})")
            
            if (pagerState.currentPage != targetPage && pagerState.pageCount == state.imageEntries.size) {
                try {
                    pagerState.scrollToPage(targetPage)
                    println("DEBUG: Successfully moved to page $targetPage")
                } catch (e: Exception) {
                    println("ERROR: Failed to scroll to page $targetPage: ${e.message}")
                }
            }
        } else if (state != null) {
            println("DEBUG: Skipping page move - loading: ${stateMachine.isLoading()}, entries: ${state.imageEntries.size}")
        }
    }



    // 完全な状態クリア関数（強化版）
    fun clearAllStateAndMemory() {
        println("DEBUG: Starting enhanced cleanup of all state and memory")
        try {
            // 段階的クリーンアップ
            println("DEBUG: Step 1 - Clearing memory cache")
            directZipHandler.clearMemoryCache()
            
            println("DEBUG: Step 2 - Closing ZIP file")
            directZipHandler.closeCurrentZipFile()
            
            println("DEBUG: Step 3 - Resetting state machine")
            stateMachine.processAction(FileLoadingStateMachine.LoadingAction.Reset)
            
            println("DEBUG: Step 4 - Clearing UI state")
            lastReadingProgress = null
            fileCompletionUpdate = null
            showTopBar = false
            
            println("DEBUG: Step 5 - Requesting garbage collection")
            System.gc()
            
            println("DEBUG: Enhanced cleanup completed successfully")
        } catch (e: Exception) {
            println("ERROR: Enhanced cleanup failed: ${e.message}")
            e.printStackTrace()
            
            // フォールバック：強制クリーンアップ
            try {
                println("DEBUG: Performing fallback cleanup")
                directZipHandler.cleanup()
                System.gc()
                System.runFinalization()
            } catch (fallbackError: Exception) {
                println("ERROR: Fallback cleanup also failed: ${fallbackError.message}")
            }
        }
    }

    // 緊急クリーンアップ関数（メモリ不足時など）
    fun emergencyCleanup(reason: String = "Unknown") {
        println("EMERGENCY: Starting emergency cleanup - Reason: $reason")
        try {
            // 即座に全てのコルーチンを停止
            supervisorJob.cancel()
            
            // 即座にZIPファイルを閉じる
            directZipHandler.closeCurrentZipFile()
            
            // メモリキャッシュを強制クリア
            directZipHandler.clearMemoryCache()
            
            // State Machineを強制リセット
            stateMachine.processAction(FileLoadingStateMachine.LoadingAction.Reset)
            
            // UI状態をクリア
            lastReadingProgress = null
            fileCompletionUpdate = null
            showTopBar = false
            
            // 強制ガベージコレクション
            System.gc()
            System.runFinalization()
            
            // ファイルリスト画面に戻る
            currentView = ViewState.LocalFileList
            
            println("EMERGENCY: Emergency cleanup completed")
        } catch (e: Exception) {
            println("CRITICAL: Emergency cleanup failed: ${e.message}")
            e.printStackTrace()
        }
    }

    // State Machine状態変化の監視と処理
    LaunchedEffect(loadingState) {
        when (val state = loadingState) {
            is FileLoadingStateMachine.LoadingState.StoppingUI -> {
                println("DEBUG: State Machine - UI Stopping")
                stateMachine.processAction(FileLoadingStateMachine.LoadingAction.UIStoppedComplete)
            }
            
            is FileLoadingStateMachine.LoadingState.CleaningResources -> {
                println("DEBUG: State Machine - Cleaning Resources")
                mainScope.launch {
                    try {
                        println("DEBUG: Clearing memory resources")
                        directZipHandler.clearMemoryCache()
                        directZipHandler.closeCurrentZipFile()
                        println("DEBUG: Resources cleaned successfully")
                        
                        stateMachine.processAction(FileLoadingStateMachine.LoadingAction.ResourcesClearedComplete)
                    } catch (e: Exception) {
                        println("DEBUG: Error during resource cleanup: ${e.message}")
                        e.printStackTrace()
                        stateMachine.processAction(
                            FileLoadingStateMachine.LoadingAction.FilePreparationFailed(e)
                        )
                    }
                }
            }
            
            is FileLoadingStateMachine.LoadingState.PreparingFile -> {
                println("DEBUG: State Machine - Preparing File")
                pendingRequest?.let { currentRequest ->
                    mainScope.launch {
                        try {
                            println("DEBUG: Opening ZIP file (state machine): ${currentRequest.file?.name ?: currentRequest.uri}")
                            
                            // Structured Concurrencyでタイムアウト処理を管理
                            val preparationJob = launch {
                                val imageEntryList = withContext(Dispatchers.IO) {
                                    directZipHandler.getImageEntriesFromZip(currentRequest.uri, currentRequest.file)
                                }
                                
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
                                        println("DEBUG: ZIP file opened successfully - ${imageEntryList.size} images, initial page: $initialPage")
                                    }
                                }
                                
                                println("DEBUG: Sending FilePreparationComplete action")
                                stateMachine.processAction(
                                    FileLoadingStateMachine.LoadingAction.FilePreparationComplete(newState)
                                )
                            }
                            
                            // タイムアウト処理も子コルーチンとして管理
                            val timeoutJob = launch {
                                kotlinx.coroutines.delay(30000) // 30秒でタイムアウト
                                println("ERROR: File preparation timeout")
                                preparationJob.cancel() // 準備ジョブをキャンセル
                                stateMachine.processAction(
                                    FileLoadingStateMachine.LoadingAction.FilePreparationFailed(
                                        Exception("File preparation timeout")
                                    )
                                )
                            }
                            
                            // 準備が完了したらタイムアウトジョブをキャンセル
                            preparationJob.invokeOnCompletion { exception ->
                                if (exception == null) {
                                    timeoutJob.cancel()
                                }
                            }
                            
                        } catch (e: Exception) {
                            println("DEBUG: Error during file preparation: ${e.message}")
                            e.printStackTrace()
                            
                            try {
                                directZipHandler.closeCurrentZipFile()
                                println("DEBUG: Error recovery - ZIP file closed")
                            } catch (cleanupError: Exception) {
                                println("DEBUG: Error during cleanup: ${cleanupError.message}")
                            }
                            
                            stateMachine.processAction(
                                FileLoadingStateMachine.LoadingAction.FilePreparationFailed(e)
                            )
                        }
                    }
                }
            }
            
            is FileLoadingStateMachine.LoadingState.Ready -> {
                println("DEBUG: State Machine - Ready with new file")
                currentView = ViewState.ImageViewer
            }
            
            is FileLoadingStateMachine.LoadingState.Error -> {
                println("DEBUG: State Machine - Error: ${state.message}")
                state.throwable?.printStackTrace()
                
                // エラー時の自動クリーンアップ
                try {
                    println("DEBUG: Performing automatic cleanup due to error")
                    clearAllStateAndMemory()
                    
                    // エラーが重大な場合は緊急クリーンアップ
                    val errorMessage = state.message.lowercase()
                    if (errorMessage.contains("memory") || 
                        errorMessage.contains("timeout") || 
                        errorMessage.contains("crash")) {
                        println("WARNING: Critical error detected, performing emergency cleanup")
                        emergencyCleanup("Critical error: ${state.message}")
                    }
                } catch (cleanupError: Exception) {
                    println("ERROR: Cleanup during error handling failed: ${cleanupError.message}")
                    emergencyCleanup("Cleanup failure during error handling")
                }
                
                currentView = ViewState.LocalFileList
                stateMachine.processAction(FileLoadingStateMachine.LoadingAction.Reset)
            }
            
            is FileLoadingStateMachine.LoadingState.Idle -> {
                println("DEBUG: State Machine - Idle")
            }
        }
    }



    // ファイルナビゲーション処理（強化されたクリーンアップ対応版）
    fun navigateToZipFile(newZipFile: File, isNextFile: Boolean = false) {
        println("DEBUG: Starting navigation to new file: ${newZipFile.name} (isNextFile: $isNextFile)")
        
        mainScope.launch {
            try {
                // ファイル切り替え前の包括的リソースクリア
                println("DEBUG: Performing comprehensive resource cleanup before navigation")
                
                // Phase 1: 現在のファイル状態を保存
                currentImageViewerState?.let { state ->
                    if (pagerState.currentPage >= 0) {
                        directZipHandler.saveCurrentPosition(
                            state.currentZipUri,
                            pagerState.currentPage,
                            state.currentZipFile
                        )
                        println("DEBUG: Current position saved before navigation: ${pagerState.currentPage}")
                    }
                }
                
                // Phase 2: IO処理でリソースクリア
                withContext(Dispatchers.IO) {
                    try {
                        directZipHandler.clearMemoryCache()
                        directZipHandler.closeCurrentZipFile()
                        println("DEBUG: File handles and cache cleared")
                    } catch (ioError: Exception) {
                        println("ERROR: IO cleanup failed: ${ioError.message}")
                        throw ioError
                    }
                }
                
                // Phase 3: ガベージコレクション
                System.gc()
                
                // Phase 4: メモリ使用量チェック
                val runtime = Runtime.getRuntime()
                val usedMemory = runtime.totalMemory() - runtime.freeMemory()
                val maxMemory = runtime.maxMemory()
                val memoryUsagePercent = (usedMemory.toDouble() / maxMemory * 100).toInt()
                
                if (memoryUsagePercent > 80) {
                    println("WARNING: High memory usage before navigation: ${memoryUsagePercent}%")
                    // 追加のクリーンアップ
                    directZipHandler.clearMemoryCache()
                    System.gc()
                    kotlinx.coroutines.delay(100) // GCの完了を待つ
                }
                
                println("DEBUG: Starting state machine action for: ${newZipFile.name}")
                val success = stateMachine.processAction(
                    FileLoadingStateMachine.LoadingAction.StartLoading(
                        Uri.fromFile(newZipFile), 
                        newZipFile,
                        isNextFile
                    )
                )
                
                if (!success) {
                    println("ERROR: Failed to start navigation - state machine rejected action")
                    // State Machineが処理を受け付けない場合の復旧処理
                    println("DEBUG: Attempting recovery with cleanup and reset")
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
                
                // エラー発生時の緊急対応
                try {
                    clearAllStateAndMemory()
                    stateMachine.processAction(FileLoadingStateMachine.LoadingAction.Reset)
                } catch (recoveryError: Exception) {
                    println("CRITICAL: Recovery failed: ${recoveryError.message}")
                    emergencyCleanup("Navigation exception with recovery failure")
                }
            }
        }
    }

    // ファイル読み込み開始処理（強化されたクリーンアップ対応版）
    fun startFileLoading(uri: Uri, file: File? = null) {
        println("DEBUG: Starting file loading: ${file?.name ?: uri}")
        
        mainScope.launch {
            try {
                // ファイル読み込み前の予防的クリーンアップ
                println("DEBUG: Performing preventive cleanup before file loading")
                
                // 現在のリソースをクリア
                withContext(Dispatchers.IO) {
                    directZipHandler.clearMemoryCache()
                    directZipHandler.closeCurrentZipFile()
                }
                
                // メモリ状況をチェック
                val runtime = Runtime.getRuntime()
                val usedMemory = runtime.totalMemory() - runtime.freeMemory()
                val maxMemory = runtime.maxMemory()
                val memoryUsagePercent = (usedMemory.toDouble() / maxMemory * 100).toInt()
                
                println("DEBUG: Memory usage before loading: ${memoryUsagePercent}%")
                
                if (memoryUsagePercent > 75) {
                    println("WARNING: High memory usage detected, performing aggressive cleanup")
                    System.gc()
                    kotlinx.coroutines.delay(100)
                    
                    val newUsedMemory = runtime.totalMemory() - runtime.freeMemory()
                    val newMemoryUsagePercent = (newUsedMemory.toDouble() / maxMemory * 100).toInt()
                    println("DEBUG: Memory usage after cleanup: ${newMemoryUsagePercent}%")
                    
                    if (newMemoryUsagePercent > 85) {
                        println("ERROR: Memory usage still critical, aborting file loading")
                        emergencyCleanup("Critical memory usage before file loading")
                        return@launch
                    }
                }
                
                println("DEBUG: Starting state machine action for file loading")
                val success = stateMachine.processAction(
                    FileLoadingStateMachine.LoadingAction.StartLoading(uri, file, isNextFile = false)
                )
                
                if (!success) {
                    println("ERROR: Failed to start file loading - state machine rejected action")
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
                
                // エラー発生時の緊急対応
                try {
                    clearAllStateAndMemory()
                    stateMachine.processAction(FileLoadingStateMachine.LoadingAction.Reset)
                } catch (recoveryError: Exception) {
                    println("CRITICAL: File loading recovery failed: ${recoveryError.message}")
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

    // 現在の位置を保存（重複実行防止）
    LaunchedEffect(pagerState.currentPage, currentImageViewerState?.fileId) {
        val state = currentImageViewerState
        if (state != null && pagerState.currentPage >= 0) {
            // ナビゲーション中でない場合のみ保存
            if (!stateMachine.isLoading()) {
                println("DEBUG: Saving position ${pagerState.currentPage} for file: ${state.fileId}")
                directZipHandler.saveCurrentPosition(
                    state.currentZipUri,
                    pagerState.currentPage,
                    state.currentZipFile
                )
            } else {
                println("DEBUG: Skipping position save during loading")
            }
        }
    }

    // Auto-hide top bar (UIタイマー - ユーザビリティのため維持)
    LaunchedEffect(showTopBar) {
        if (showTopBar && currentImageViewerState != null) {
            kotlinx.coroutines.delay(3000) // UIタイマー：3秒後に自動でトップバーを隠す
            showTopBar = false
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

    // StateMachine専用のクリーンアップ
    DisposableEffect(stateMachine) {
        onDispose {
            println("DEBUG: StateMachine disposing")
            try {
                stateMachine.processAction(FileLoadingStateMachine.LoadingAction.Reset)
                println("DEBUG: StateMachine cleanup completed")
            } catch (e: Exception) {
                println("ERROR: StateMachine cleanup failed: ${e.message}")
                e.printStackTrace()
            }
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

    // メモリ使用量監視とクリーンアップ
    DisposableEffect(currentImageViewerState) {
        val memoryMonitorJob = mainScope.launch {
            while (true) {
                try {
                    val runtime = Runtime.getRuntime()
                    val usedMemory = runtime.totalMemory() - runtime.freeMemory()
                    val maxMemory = runtime.maxMemory()
                    val memoryUsagePercent = (usedMemory.toDouble() / maxMemory * 100).toInt()
                    
                    println("DEBUG: Memory usage: ${memoryUsagePercent}% (${usedMemory / 1024 / 1024}MB / ${maxMemory / 1024 / 1024}MB)")
                    
                    // メモリ使用量が80%を超えた場合は予防的クリーンアップ
                    if (memoryUsagePercent > 80) {
                        println("WARNING: High memory usage detected! Performing preventive cleanup...")
                        directZipHandler.clearMemoryCache()
                        
                        // メモリ使用量が90%を超えた場合は緊急クリーンアップ
                        if (memoryUsagePercent > 90) {
                            println("CRITICAL: Critical memory usage! Performing emergency cleanup...")
                            emergencyCleanup("Critical memory usage: ${memoryUsagePercent}%")
                            break // 監視を停止して処理を優先
                        } else {
                            System.gc() // 通常のガベージコレクション
                        }
                        
                        // クリーンアップ効果をチェック
                        kotlinx.coroutines.delay(1000)
                        val newUsedMemory = runtime.totalMemory() - runtime.freeMemory()
                        val newMemoryUsagePercent = (newUsedMemory.toDouble() / maxMemory * 100).toInt()
                        println("DEBUG: Memory usage after cleanup: ${newMemoryUsagePercent}%")
                        
                        if (newMemoryUsagePercent > memoryUsagePercent - 5) {
                            println("WARNING: Cleanup was not effective, memory usage still high")
                            // クリーンアップ効果が低い場合は監視頻度を上げる
                            kotlinx.coroutines.delay(2000) // 2秒後に再チェック
                        } else {
                            println("DEBUG: Cleanup successful, memory usage reduced by ${memoryUsagePercent - newMemoryUsagePercent}%")
                        }
                    }
                    
                    kotlinx.coroutines.delay(5000) // 5秒ごとにチェック
                } catch (e: Exception) {
                    println("ERROR: Memory monitoring failed: ${e.message}")
                    break
                }
            }
        }
        
        onDispose {
            println("DEBUG: Memory monitor disposing")
            memoryMonitorJob.cancel()
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

    // 画面遷移のハンドリング
    when (currentView) {
        is ViewState.LocalFileList -> {
            LaunchedEffect(currentView) {
                println("DEBUG: Returned to file list - resetting state machine")
                stateMachine.processAction(FileLoadingStateMachine.LoadingAction.Reset)
                directZipHandler.closeCurrentZipFile()
                // 前の画面から戻った時の状態をクリア
                lastReadingProgress = null
                fileCompletionUpdate = null
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
            LaunchedEffect(currentView) {
                println("DEBUG: Moved to Dropbox browser - resetting state machine")
                stateMachine.processAction(FileLoadingStateMachine.LoadingAction.Reset)
                directZipHandler.closeCurrentZipFile()
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
            LaunchedEffect(currentView) {
                println("DEBUG: Moved to Settings - resetting state machine")
                stateMachine.processAction(FileLoadingStateMachine.LoadingAction.Reset)
                directZipHandler.closeCurrentZipFile()
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
