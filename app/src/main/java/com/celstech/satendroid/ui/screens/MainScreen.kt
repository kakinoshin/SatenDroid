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
    LaunchedEffect(currentImageViewerState?.fileId) {
        val state = currentImageViewerState
        if (state != null && state.imageEntries.isNotEmpty()) {
            val targetPage = state.initialPage.coerceIn(0, state.imageEntries.size - 1)
            println("DEBUG: Moving to saved position: $targetPage for file: ${state.fileId}")
            
            if (pagerState.currentPage != targetPage) {
                pagerState.scrollToPage(targetPage)
            }
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
                            println("DEBUG: Opening ZIP file (state machine): ${currentRequest.second?.name ?: currentRequest.first}")
                            
                            val imageEntryList = directZipHandler.getImageEntriesFromZip(currentRequest.first, currentRequest.second)
                            
                            val newState = if (imageEntryList.isEmpty()) {
                                println("DEBUG: No images found in ZIP")
                                null
                            } else {
                                val savedPosition = directZipHandler.getSavedPosition(currentRequest.first, currentRequest.second) ?: 0
                                val validPosition = savedPosition.coerceIn(0, imageEntryList.size - 1)
                                println("DEBUG: Saved position: $savedPosition, Valid position: $validPosition")
                                
                                val navigationInfo = currentRequest.second?.let { file ->
                                    fileNavigationManager.getNavigationInfo(file)
                                }
                                
                                ImageViewerState(
                                    imageEntries = imageEntryList,
                                    currentZipUri = currentRequest.first,
                                    currentZipFile = currentRequest.second,
                                    fileNavigationInfo = navigationInfo,
                                    initialPage = validPosition
                                ).also {
                                    println("DEBUG: ZIP file opened successfully - ${imageEntryList.size} images, initial page: $validPosition")
                                }
                            }
                            
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
    fun navigateToZipFile(newZipFile: File) {
        println("DEBUG: Starting navigation to new file: ${newZipFile.name}")
        
        currentLoadingJob?.cancel()
        currentLoadingJob = coroutineScope.launch {
            val success = stateMachine.processAction(
                FileLoadingStateMachine.LoadingAction.StartLoading(
                    Uri.fromFile(newZipFile), 
                    newZipFile
                )
            )
            if (!success) {
                println("DEBUG: Failed to start navigation - state machine busy")
            }
        }
    }

    // ファイル読み込み開始処理（State Machine対応版）
    fun startFileLoading(uri: Uri, file: File? = null) {
        println("DEBUG: Starting file loading: ${file?.name ?: uri}")
        
        currentLoadingJob?.cancel()
        currentLoadingJob = coroutineScope.launch {
            val success = stateMachine.processAction(
                FileLoadingStateMachine.LoadingAction.StartLoading(uri, file)
            )
            if (!success) {
                println("DEBUG: Failed to start file loading - state machine busy")
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

    // 現在の位置を保存
    LaunchedEffect(pagerState.currentPage, currentImageViewerState?.fileId) {
        val state = currentImageViewerState
        if (state != null) {
            println("DEBUG: Saving position ${pagerState.currentPage} for file: ${state.fileId}")
            directZipHandler.saveCurrentPosition(
                state.currentZipUri,
                pagerState.currentPage,
                state.currentZipFile
            )
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
            
            if (stateMachine.isLoading() || state == null) {
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

                        state.currentZipFile?.let { currentFile ->
                            lastReadingProgress = Pair(currentFile, pagerState.currentPage)
                        }

                        state.fileNavigationInfo?.previousFile?.let { previousFile ->
                            navigateToZipFile(previousFile)
                        } ?: println("DEBUG: No previous file available")
                    },
                    onNavigateToNextFile = {
                        println("DEBUG: Next file button clicked")

                        state.currentZipFile?.let { currentFile ->
                            fileCompletionUpdate = currentFile
                        }

                        state.fileNavigationInfo?.nextFile?.let { nextFile ->
                            navigateToZipFile(nextFile)
                        } ?: println("DEBUG: No next file available")
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
