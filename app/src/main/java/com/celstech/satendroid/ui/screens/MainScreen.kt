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

    // 現在のファイル識別子
    val currentFileId = remember(currentImageViewerState) {
        currentImageViewerState?.fileId
    }
    val isImageViewerReady = remember(currentImageViewerState, stateMachine.isLoading()) {
        currentImageViewerState != null &&
                currentImageViewerState.imageEntries.isNotEmpty() &&
                !stateMachine.isLoading()
    }

    // ページ保存用の状態
    var lastSavedPage by remember { mutableStateOf(-1) }
    var lastSavedFileId by remember { mutableStateOf<String?>(null) }

    // ファイルが変更されたときに保存されたページに移動
    LaunchedEffect(currentFileId) {
        if (currentFileId != null && isImageViewerReady) {
            val state = currentImageViewerState ?: return@LaunchedEffect

            if (lastSavedFileId == currentFileId) {
                return@LaunchedEffect
            }

            val targetPage = state.initialPage.coerceIn(0, state.imageEntries.size - 1)

            if (pagerState.currentPage != targetPage && pagerState.pageCount == state.imageEntries.size) {
                try {
                    pagerState.scrollToPage(targetPage)
                    lastSavedFileId = currentFileId
                } catch (e: Exception) {
                    // エラーハンドリング
                }
            }
        }
    }

    // ファイルナビゲーション処理
    fun navigateToZipFile(newZipFile: File, isNextFile: Boolean = false) {
        mainScope.launch {
            try {
                // 現在の位置を保存
                currentImageViewerState?.let { state ->
                    if (pagerState.currentPage >= 0) {
                        directZipHandler.saveCurrentPosition(
                            state.currentZipUri,
                            pagerState.currentPage,
                            state.currentZipFile
                        )
                    }
                }

                // リソースクリア
                withContext(Dispatchers.IO) {
                    directZipHandler.clearMemoryCache()
                    directZipHandler.closeCurrentZipFile()
                }

                // State Machine開始
                stateMachine.processAction(
                    FileLoadingStateMachine.LoadingAction.StartLoading(
                        Uri.fromFile(newZipFile),
                        newZipFile,
                        isNextFile
                    )
                )

            } catch (e: Exception) {
                stateMachine.processAction(
                    FileLoadingStateMachine.LoadingAction.FilePreparationFailed(e)
                )
            }
        }
    }

    // ファイル読み込み開始処理
    fun startFileLoading(uri: Uri, file: File? = null) {
        mainScope.launch {
            try {
                // リソースクリア
                withContext(Dispatchers.IO) {
                    directZipHandler.clearMemoryCache()
                    directZipHandler.closeCurrentZipFile()
                }

                // State Machine開始
                stateMachine.processAction(
                    FileLoadingStateMachine.LoadingAction.StartLoading(
                        uri,
                        file,
                        isNextFile = false
                    )
                )

            } catch (e: Exception) {
                stateMachine.processAction(
                    FileLoadingStateMachine.LoadingAction.FilePreparationFailed(e)
                )
            }
        }
    }

    // State Machine状態変化の処理
    LaunchedEffect(loadingState) {
        when (val currentState = loadingState) {
            is FileLoadingStateMachine.LoadingState.Idle -> {
                // アイドル状態では特に何もしない
            }

            is FileLoadingStateMachine.LoadingState.StoppingUI -> {
                // UI停止完了を通知して次の状態に遷移
                kotlinx.coroutines.delay(50) // 短い遅延で確実に遷移
                stateMachine.processAction(FileLoadingStateMachine.LoadingAction.UIStoppedComplete)
            }

            is FileLoadingStateMachine.LoadingState.CleaningResources -> {
                mainScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            directZipHandler.clearMemoryCache()
                            directZipHandler.closeCurrentZipFile()
                        }
                        stateMachine.processAction(FileLoadingStateMachine.LoadingAction.ResourcesClearedComplete)
                    } catch (e: Exception) {
                        stateMachine.processAction(
                            FileLoadingStateMachine.LoadingAction.FilePreparationFailed(e)
                        )
                    }
                }
            }

            is FileLoadingStateMachine.LoadingState.PreparingFile -> {
                pendingRequest?.let { currentRequest ->
                    mainScope.launch {
                        try {
                            val imageEntryList = withContext(Dispatchers.IO) {
                                directZipHandler.getImageEntriesFromZip(
                                    currentRequest.uri,
                                    currentRequest.file
                                )
                            }

                            val newState = if (imageEntryList.isEmpty()) {
                                null
                            } else {
                                val initialPage = if (currentRequest.isNextFile) {
                                    0
                                } else {
                                    val savedPosition = directZipHandler.getSavedPosition(
                                        currentRequest.uri,
                                        currentRequest.file
                                    ) ?: 0
                                    savedPosition.coerceIn(0, imageEntryList.size - 1)
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
                                )
                            }

                            stateMachine.processAction(
                                FileLoadingStateMachine.LoadingAction.FilePreparationComplete(
                                    newState
                                )
                            )
                        } catch (e: Exception) {
                            stateMachine.processAction(
                                FileLoadingStateMachine.LoadingAction.FilePreparationFailed(e)
                            )
                        }
                    }
                }
            }

            is FileLoadingStateMachine.LoadingState.Ready -> {
                currentView = ViewState.ImageViewer
            }

            is FileLoadingStateMachine.LoadingState.Error -> {
                currentView = ViewState.LocalFileList
                stateMachine.processAction(FileLoadingStateMachine.LoadingAction.Reset)
            }
        }
    }

    // Permission state for external storage
    val storagePermissionState = rememberPermissionState(
        android.Manifest.permission.READ_EXTERNAL_STORAGE
    )

    // Zip file picker launcher
    val zipPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            startFileLoading(uri, null)
        }
    }

    // 現在の位置を保存
    LaunchedEffect(pagerState.currentPage, currentFileId) {
        val state = currentImageViewerState
        val currentPage = pagerState.currentPage

        if (state != null && currentPage >= 0 && currentFileId != null) {
            if (lastSavedPage == currentPage && lastSavedFileId == currentFileId) {
                return@LaunchedEffect
            }

            if (stateMachine.isLoading()) {
                return@LaunchedEffect
            }

            kotlinx.coroutines.delay(500)

            val finalState = currentImageViewerState
            val finalPage = pagerState.currentPage

            if (finalState != null &&
                finalState.fileId == currentFileId &&
                finalPage >= 0 &&
                finalPage < finalState.imageEntries.size &&
                !stateMachine.isLoading()
            ) {

                try {
                    directZipHandler.saveCurrentPosition(
                        finalState.currentZipUri,
                        finalPage,
                        finalState.currentZipFile
                    )

                    lastSavedPage = finalPage
                    lastSavedFileId = currentFileId
                } catch (e: Exception) {
                    // エラーハンドリング
                }
            }
        }
    }

    // Auto-hide top bar
    LaunchedEffect(showTopBar, currentFileId) {
        if (showTopBar && currentFileId != null && isImageViewerReady) {
            kotlinx.coroutines.delay(3000)

            if (showTopBar && currentImageViewerState?.fileId == currentFileId) {
                showTopBar = false
            }
        }
    }

    // クリーンアップ
    DisposableEffect(Unit) {
        onDispose {
            try {
                directZipHandler.clearMemoryCache()
                directZipHandler.closeCurrentZipFile()
                directZipHandler.cleanup()
            } catch (e: Exception) {
                // エラーハンドリング
            }
        }
    }

    // 画面遷移のハンドリング
    when (currentView) {
        is ViewState.LocalFileList -> {
            FileSelectionScreen(
                initialPath = savedDirectoryPath,
                onFileSelected = { file ->
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
                        // 処理
                    }
                } else null,
                readingStatusUpdate = lastReadingProgress,
                fileCompletionUpdate = fileCompletionUpdate
            )
        }

        is ViewState.ImageViewer -> {
            val state = currentImageViewerState

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
                        currentView = ViewState.LocalFileList
                    },
                    onNavigateToPreviousFile = {
                        mainScope.launch {
                            state.currentZipFile?.let { currentFile ->
                                val currentPage = pagerState.currentPage
                                lastReadingProgress = Pair(currentFile, currentPage)

                                try {
                                    withContext(Dispatchers.IO) {
                                        directZipHandler.saveCurrentPosition(
                                            state.currentZipUri,
                                            currentPage,
                                            currentFile
                                        )
                                    }
                                } catch (e: Exception) {
                                    // エラーハンドリング
                                }
                            }

                            state.fileNavigationInfo?.previousFile?.let { previousFile ->
                                navigateToZipFile(previousFile)
                            }
                        }
                    },
                    onNavigateToNextFile = {
                        mainScope.launch {
                            state.currentZipFile?.let { currentFile ->
                                val currentPage = pagerState.currentPage
                                fileCompletionUpdate = currentFile

                                try {
                                    withContext(Dispatchers.IO) {
                                        directZipHandler.saveCurrentPosition(
                                            state.currentZipUri,
                                            currentPage,
                                            currentFile
                                        )
                                    }
                                } catch (e: Exception) {
                                    // エラーハンドリング
                                }
                            }

                            state.fileNavigationInfo?.nextFile?.let { nextFile ->
                                navigateToZipFile(nextFile, isNextFile = true)
                            }
                        }
                    },
                    fileNavigationInfo = state.fileNavigationInfo,
                    cacheManager = directZipHandler.getUnifiedDataManager(),
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
            SettingsScreenNew(
                cacheManager = directZipHandler.getUnifiedDataManager(),
                directZipHandler = directZipHandler,
                onBackPressed = {
                    currentView = ViewState.LocalFileList
                }
            )
        }
    }
}