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
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/**
 * メイン画面 - State Machine対応版
 * delayによるタイミング調整を排除し、適切な状態遷移で制御
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalPermissionsApi::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

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

    // コルーチンジョブの管理（メモリリーク防止）
    var currentLoadingJob by remember { mutableStateOf<Job?>(null) }

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

    // State Machine状態変化の監視と処理
    LaunchedEffect(loadingState) {
        when (val state = loadingState) {
            is FileLoadingStateMachine.LoadingState.StoppingUI -> {
                println("DEBUG: State Machine - UI Stopping")
                stateMachine.processAction(FileLoadingStateMachine.LoadingAction.UIStoppedComplete)
            }
            
            is FileLoadingStateMachine.LoadingState.CleaningResources -> {
                println("DEBUG: State Machine - Cleaning Resources")
                coroutineScope.launch {
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
                    coroutineScope.launch {
                        try {
                            println("DEBUG: Opening ZIP file (state machine): ${currentRequest.file?.name ?: currentRequest.uri}")
                            
                            // タイムアウト処理を追加
                            val timeoutJob = launch {
                                kotlinx.coroutines.delay(30000) // 30秒でタイムアウト
                                println("ERROR: File preparation timeout")
                                stateMachine.processAction(
                                    FileLoadingStateMachine.LoadingAction.FilePreparationFailed(
                                        Exception("File preparation timeout")
                                    )
                                )
                            }
                            
                            val imageEntryList = directZipHandler.getImageEntriesFromZip(currentRequest.uri, currentRequest.file)
                            
                            // タイムアウトジョブをキャンセル
                            timeoutJob.cancel()
                            
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
                currentView = ViewState.LocalFileList
                stateMachine.processAction(FileLoadingStateMachine.LoadingAction.Reset)
            }
            
            is FileLoadingStateMachine.LoadingState.Idle -> {
                println("DEBUG: State Machine - Idle")
            }
        }
    }

    // 完全な状態クリア関数（State Machine対応版）
    fun clearAllStateAndMemory() {
        println("DEBUG: Starting cleanup of all state and memory")
        directZipHandler.clearMemoryCache()
        directZipHandler.closeCurrentZipFile()
        stateMachine.processAction(FileLoadingStateMachine.LoadingAction.Reset)
        println("DEBUG: Cleanup completed")
    }

    // ファイルナビゲーション処理（State Machine対応版）
    fun navigateToZipFile(newZipFile: File, isNextFile: Boolean = false) {
        println("DEBUG: Starting navigation to new file: ${newZipFile.name} (isNextFile: $isNextFile)")
        
        // 現在のジョブをキャンセル
        currentLoadingJob?.cancel()
        
        currentLoadingJob = coroutineScope.launch {
            try {
                // ファイル切り替え前にリソースをクリア
                println("DEBUG: Clearing resources before navigation")
                directZipHandler.clearMemoryCache()
                directZipHandler.closeCurrentZipFile()
                
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
                    // State Machineが処理を受け付けない場合は強制リセット
                    println("DEBUG: Forcing state machine reset")
                    stateMachine.processAction(FileLoadingStateMachine.LoadingAction.Reset)
                    
                    // リセット後に再実行
                    kotlinx.coroutines.delay(50)
                    val retrySuccess = stateMachine.processAction(
                        FileLoadingStateMachine.LoadingAction.StartLoading(
                            Uri.fromFile(newZipFile), 
                            newZipFile,
                            isNextFile
                        )
                    )
                    
                    if (!retrySuccess) {
                        println("ERROR: Failed to navigate to file even after reset: ${newZipFile.name}")
                    }
                }
            } catch (e: Exception) {
                println("ERROR: Exception during file navigation: ${e.message}")
                e.printStackTrace()
                // エラーが発生した場合は状態をリセット
                stateMachine.processAction(FileLoadingStateMachine.LoadingAction.Reset)
            }
        }
    }

    // ファイル読み込み開始処理（State Machine対応版）
    fun startFileLoading(uri: Uri, file: File? = null) {
        println("DEBUG: Starting file loading: ${file?.name ?: uri}")
        
        // 現在のジョブをキャンセル
        currentLoadingJob?.cancel()
        
        currentLoadingJob = coroutineScope.launch {
            try {
                println("DEBUG: Starting state machine action for file loading")
                val success = stateMachine.processAction(
                    FileLoadingStateMachine.LoadingAction.StartLoading(uri, file, isNextFile = false)
                )
                
                if (!success) {
                    println("ERROR: Failed to start file loading - state machine rejected action")
                    // State Machineが処理を受け付けない場合は強制リセット
                    println("DEBUG: Forcing state machine reset")
                    stateMachine.processAction(FileLoadingStateMachine.LoadingAction.Reset)
                    
                    // リセット後に再実行
                    kotlinx.coroutines.delay(50)
                    val retrySuccess = stateMachine.processAction(
                        FileLoadingStateMachine.LoadingAction.StartLoading(uri, file, isNextFile = false)
                    )
                    
                    if (!retrySuccess) {
                        println("ERROR: Failed to load file even after reset: ${file?.name ?: uri}")
                    }
                }
            } catch (e: Exception) {
                println("ERROR: Exception during file loading: ${e.message}")
                e.printStackTrace()
                // エラーが発生した場合は状態をリセット
                stateMachine.processAction(FileLoadingStateMachine.LoadingAction.Reset)
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

    // Cleanup when component is disposed
    DisposableEffect(Unit) {
        onDispose {
            println("DEBUG: MainScreen disposing - cleaning up ZIP resources")
            currentLoadingJob?.cancel()
            clearAllStateAndMemory()
            directZipHandler.cleanup()
        }
    }

    // 画面遷移のハンドリング
    when (currentView) {
        is ViewState.LocalFileList -> {
            LaunchedEffect(currentView) {
                println("DEBUG: Returned to file list - resetting state machine")
                stateMachine.processAction(FileLoadingStateMachine.LoadingAction.Reset)
                directZipHandler.closeCurrentZipFile()
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
                readingStatusUpdate = lastReadingProgress?.also {
                    lastReadingProgress = null
                },
                fileCompletionUpdate = fileCompletionUpdate?.also {
                    fileCompletionUpdate = null
                }
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
                        currentLoadingJob?.cancel()
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
