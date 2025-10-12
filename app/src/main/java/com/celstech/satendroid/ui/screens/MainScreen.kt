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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.celstech.satendroid.LocalDropboxAuthManager
import com.celstech.satendroid.navigation.FileNavigationManager
import com.celstech.satendroid.ui.models.ViewState
import com.celstech.satendroid.ui.models.ImageViewerState
import com.celstech.satendroid.utils.DirectZipImageHandler
import com.celstech.satendroid.utils.ReadingStateManager
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/**
 * メイン画面 - ダウンロードキュー対応版
 * シンプルで確実に動作する版 + ダウンロードキュー画面追加
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalPermissionsApi::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current

    // シンプルなスコープ
    val coroutineScope = rememberCoroutineScope()

    // シンプルな状態管理
    var currentView by remember { mutableStateOf<ViewState>(ViewState.LocalFileList) }
    var isLoading by remember { mutableStateOf(false) }
    var loadingMessage by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // 現在のディレクトリパスを一元管理（Single Source of Truth）
    var currentPath by remember {
        mutableStateOf(
            context.getExternalFilesDir(null)?.absolutePath ?: ""
        )
    }

    // 画像ビューア用の状態
    var currentImageViewerState: ImageViewerState? by remember { mutableStateOf(null) }

    var showTopBar by remember { mutableStateOf(false) }

    // 直接ZIP画像ハンドラー
    val directZipHandler = remember {
        DirectZipImageHandler(context).also {
            println("DEBUG: DirectZipImageHandler created")
        }
    }

    // 読書状態マネージャー
    val readingStateManager = remember {
        ReadingStateManager(context).also {
            println("DEBUG: ReadingStateManager created")
        }
    }

    // File navigation manager
    val fileNavigationManager = remember { FileNavigationManager(context) }

    // Pager state
    val pagerState = rememberPagerState {
        currentImageViewerState?.imageEntries?.size ?: 0
    }

    // Permission state for external storage
    val storagePermissionState = rememberPermissionState(
        android.Manifest.permission.READ_EXTERNAL_STORAGE
    )

    // シンプルなファイル読み込み関数（順序保証版）
    fun startSimpleFileLoading(uri: Uri, file: File? = null) {
        coroutineScope.launch {
            try {
                println("DEBUG: Starting file loading for ${file?.name ?: uri}")

                // ★ ステップ1: UI状態を「読み込み中」に設定
                withContext(Dispatchers.Main) {
                    isLoading = true
                    loadingMessage = "ファイルを準備中..."
                    errorMessage = null
                }

                // ★ ステップ2: 現在のファイルの状態を保存（完了を待つ）
                currentImageViewerState?.let { state ->
                    if (state.imageEntries.isNotEmpty()) {
                        // 最新のページ位置で状態を保存
                        directZipHandler.saveCurrentFileState(pagerState.currentPage)
                        println("DEBUG: Saved previous file state before loading new file")
                    }
                }

                // ★ ステップ3: ZIPファイルを物理的に閉じる（完了を待つ）
                directZipHandler.closeCurrentZipFile()
                println("DEBUG: Closed previous ZIP file")

                // ★ ステップ4: メモリキャッシュをクリア（完了を待つ）
                directZipHandler.clearMemoryCache()
                println("DEBUG: Cleared memory cache")

                // ★ ステップ5: 新しいファイルの画像エントリを取得（完了を待つ）
                withContext(Dispatchers.Main) {
                    loadingMessage = "ZIP内の画像を検索中..."
                }

                val imageEntryList = withContext(Dispatchers.IO) {
                    directZipHandler.getImageEntriesFromZip(uri, file)
                }

                if (imageEntryList.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        errorMessage = "このZIPファイルには表示可能な画像がありません"
                        isLoading = false
                    }
                    return@launch
                }

                // ★ ステップ6: 初期ページの決定
                val initialPage = run {
                    val savedPosition = directZipHandler.getSavedPosition(uri, file)
                    println("DEBUG: Getting initial page for ${file?.name ?: uri}")
                    println("DEBUG: Saved position: $savedPosition")
                    println("DEBUG: Total images: ${imageEntryList.size}")

                    savedPosition.coerceIn(0, imageEntryList.size - 1)
                }

                // ★ ステップ7: ナビゲーション情報の取得
                val navigationInfo = file?.let { f ->
                    fileNavigationManager.getNavigationInfo(f)
                }

                // ★ ステップ8: UI状態を更新（メインスレッド）
                withContext(Dispatchers.Main) {
                    currentImageViewerState = ImageViewerState(
                        imageEntries = imageEntryList,
                        currentZipUri = uri,
                        currentZipFile = file,
                        fileNavigationInfo = navigationInfo,
                        initialPage = initialPage
                    )

                    // 総画像数のみ更新（既存の位置は保持）
                    val filePath = file?.absolutePath ?: uri.path ?: uri.toString()
                    val existingState = readingStateManager.getState(filePath)

                    if (existingState.totalPages != imageEntryList.size) {
                        readingStateManager.updateCurrentPage(
                            filePath = filePath,
                            page = existingState.currentPage,
                            totalPages = imageEntryList.size
                        )
                        readingStateManager.saveStateSync(filePath)
                    }

                    isLoading = false
                    currentView = ViewState.ImageViewer

                    println("DEBUG: File loading completed successfully - ${imageEntryList.size} images")
                }

            } catch (e: Exception) {
                println("ERROR: File loading failed: ${e.message}")
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    errorMessage = "ファイルの読み込みに失敗しました: ${e.message}"
                    isLoading = false
                }
            }
        }
    }

    // ファイルナビゲーション処理（順序保証版）
    fun navigateToZipFile(newZipFile: File, isNextFile: Boolean = false) {
        coroutineScope.launch {
            try {
                println("DEBUG: Navigating to file: ${newZipFile.name} (isNext: $isNextFile)")

                // ★ ステップ1: 現在のファイルの情報を先に保存（重要：状態が上書きされる前に）
                val previousFile = currentImageViewerState?.currentZipFile
                val previousUri = currentImageViewerState?.currentZipUri
                
                println("DEBUG: Previous file state before navigation:")
                println("  File: ${previousFile?.name}")

                // 現在のファイルの状態を保存（完了を待つ）
                currentImageViewerState?.let { state ->
                    if (state.imageEntries.isNotEmpty()) {
                        // ★ directZipHandler.saveCurrentFileState()を呼ぶ（startSimpleFileLoadingと同じ処理）
                        directZipHandler.saveCurrentFileState(pagerState.currentPage)
                        println("DEBUG: ★ Saved previous file state ★")
                    }
                }

                // ★ ステップ2: ZIPファイルを物理的に閉じる（完了を待つ）
                directZipHandler.closeCurrentZipFile()
                println("DEBUG: Closed previous ZIP file")

                // ★ ステップ3: メモリキャッシュをクリア（完了を待つ）
                directZipHandler.clearMemoryCache()
                println("DEBUG: Cleared memory cache")

                // ★ ステップ4: 新しいファイルを読み込み（完了を待つ）
                withContext(Dispatchers.Main) {
                    isLoading = true
                    loadingMessage = "新しいファイルを準備中..."
                }

                val imageEntryList = withContext(Dispatchers.IO) {
                    directZipHandler.getImageEntriesFromZip(Uri.fromFile(newZipFile), newZipFile)
                }

                if (imageEntryList.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        errorMessage = "このZIPファイルには表示可能な画像がありません"
                        isLoading = false
                    }
                    return@launch
                }

                // ★ ステップ5: 初期ページの決定
                val initialPage = run {
                    val savedPosition =
                        directZipHandler.getSavedPosition(Uri.fromFile(newZipFile), newZipFile)
                    println("DEBUG: Getting initial page for ${newZipFile.name}")
                    println("DEBUG: Saved position: $savedPosition")
                    println("DEBUG: Total images: ${imageEntryList.size}")

                    savedPosition.coerceIn(0, imageEntryList.size - 1)
                }

                // ★ ステップ6: ナビゲーション情報の取得
                val navigationInfo = fileNavigationManager.getNavigationInfo(newZipFile)

                // ★ ステップ7: UI状態を更新（メインスレッド）
                withContext(Dispatchers.Main) {
                    currentImageViewerState = ImageViewerState(
                        imageEntries = imageEntryList,
                        currentZipUri = Uri.fromFile(newZipFile),
                        currentZipFile = newZipFile,
                        fileNavigationInfo = navigationInfo,
                        initialPage = initialPage
                    )

                    // 総画像数のみ更新
                    val filePath = newZipFile.absolutePath
                    val existingState = readingStateManager.getState(filePath)

                    if (existingState.totalPages != imageEntryList.size) {
                        readingStateManager.updateCurrentPage(
                            filePath = filePath,
                            page = existingState.currentPage,
                            totalPages = imageEntryList.size
                        )
                        readingStateManager.saveStateSync(filePath)
                    }

                    isLoading = false
                    println("DEBUG: File navigation completed successfully - ${imageEntryList.size} images")
                }

            } catch (e: Exception) {
                println("ERROR: Navigation failed: ${e.message}")
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    errorMessage = "ファイルの切り替えに失敗しました: ${e.message}"
                    isLoading = false
                }
            }
        }
    }

    // Zip file picker launcher
    val zipPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            startSimpleFileLoading(uri, null)
        }
    }

    // ページ変更時の通知処理（保存は行わない）
    LaunchedEffect(pagerState.currentPage, currentImageViewerState?.fileId) {
        val state = currentImageViewerState
        val currentPage = pagerState.currentPage

        if (state != null && currentPage >= 0 && !isLoading) {
            delay(500) // 500ms待機してページ遷移が安定するのを待つ

            // 状態が変わっていないことを確認
            if (currentImageViewerState?.fileId == state.fileId &&
                pagerState.currentPage == currentPage &&
                !isLoading
            ) {
                // DirectZipHandlerにページ変更を通知（保存はDirectZipHandlerが管理）
                directZipHandler.updateCurrentPage(currentPage)
                println("DEBUG: Page notification sent to DirectZipHandler: $currentPage")
            }
        }
    }

    // 初期ページ設定
    LaunchedEffect(currentImageViewerState?.fileId) {
        val state = currentImageViewerState
        if (state != null && !isLoading) {
            if (pagerState.currentPage != state.initialPage &&
                pagerState.pageCount == state.imageEntries.size
            ) {
                try {
                    pagerState.scrollToPage(state.initialPage)
                    println("DEBUG: Scrolled to initial page ${state.initialPage}")
                } catch (e: Exception) {
                    println("ERROR: Failed to scroll to initial page: ${e.message}")
                }
            }
        }
    }

    // Auto-hide top bar
    LaunchedEffect(showTopBar, currentImageViewerState?.fileId) {
        if (showTopBar && currentImageViewerState != null && !isLoading) {
            delay(3000)
            if (showTopBar && currentImageViewerState?.fileId == currentImageViewerState?.fileId) {
                showTopBar = false
            }
        }
    }

    // クリーンアップ（非同期処理版）
    DisposableEffect(Unit) {
        onDispose {
            println("DEBUG: MainScreen disposing")

            // 非同期でクリーンアップを実行
            coroutineScope.launch {
                try {
                    // 現在のファイルの状態を保存
                    currentImageViewerState?.let { state ->
                        if (state.imageEntries.isNotEmpty()) {
                            directZipHandler.saveCurrentFileState(pagerState.currentPage)
                            println("DEBUG: Final state saved on dispose")
                        }
                    }

                    // リソースクリーンアップ
                    directZipHandler.clearMemoryCache()
                    directZipHandler.cleanup()

                    println("DEBUG: Cleanup completed")
                } catch (e: Exception) {
                    println("ERROR: Cleanup failed: ${e.message}")
                }
            }
        }
    }

    // UI表示
    when (currentView) {
        is ViewState.LocalFileList -> {
            FileSelectionScreen(
                initialPath = currentPath,
                onFileSelected = { file ->
                    // ファイル選択時にパスを更新
                    currentPath = file.parentFile?.absolutePath ?: currentPath
                    startSimpleFileLoading(Uri.fromFile(file), file)
                },
                onDirectoryChanged = { path ->
                    currentPath = path
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
                onOpenDownloadQueue = {
                    currentView = ViewState.DownloadQueue
                },
                onOpenSettings = {
                    currentView = ViewState.Settings
                },
                isLoading = isLoading
            )

            // エラー表示
            if (errorMessage != null) {
                LaunchedEffect(errorMessage) {
                    delay(5000)
                    errorMessage = null
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.8f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .background(Color.Red.copy(alpha = 0.9f), RoundedCornerShape(16.dp))
                            .padding(24.dp)
                    ) {
                        Text(
                            text = "エラー",
                            color = Color.White,
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Text(
                            text = errorMessage!!,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                        Button(
                            onClick = { errorMessage = null },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White,
                                contentColor = Color.Red
                            )
                        ) {
                            Text("閉じる")
                        }
                    }
                }
            }
        }

        is ViewState.ImageViewer -> {
            val state = currentImageViewerState

            if (isLoading || state == null) {
                // ローディング画面
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
                        Text(
                            text = loadingMessage.ifEmpty { "読み込み中..." },
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
                // 画像ビューア画面
                DirectZipImageViewerScreen(
                    imageEntries = state.imageEntries,
                    currentZipFile = state.currentZipFile,
                    pagerState = pagerState,
                    showTopBar = showTopBar,
                    onToggleTopBar = { showTopBar = !showTopBar },
                    onBackToFiles = {
                        coroutineScope.launch {
                            // ★ ステップ1: 現在のファイルの情報を先に保存（重要：状態が変わる前に）
                            val currentFile = currentImageViewerState?.currentZipFile
                            
                            println("DEBUG: Returning to file list - Current state:")
                            println("  File: ${currentFile?.name}")

                            // 現在のファイルの状態を保存（完了を待つ）
                            currentImageViewerState?.let { state ->
                                if (state.imageEntries.isNotEmpty()) {
                                    // ★ directZipHandler.saveCurrentFileState()を呼ぶ（startSimpleFileLoadingと同じ処理）
                                    directZipHandler.saveCurrentFileState(pagerState.currentPage)
                                    println("DEBUG: ★ Saved file state when returning to list ★")
                                }
                            }

                            // ★ ステップ2: 画面遷移（メインスレッド）
                            withContext(Dispatchers.Main) {
                                currentView = ViewState.LocalFileList
                            }
                        }
                    },
                    onNavigateToPreviousFile = {
                        state.fileNavigationInfo?.previousFile?.let { previousFile ->
                            navigateToZipFile(previousFile, isNextFile = false)
                        }
                    },
                    onNavigateToNextFile = {
                        state.fileNavigationInfo?.nextFile?.let { nextFile ->
                            navigateToZipFile(nextFile, isNextFile = true)
                        }
                    },
                    fileNavigationInfo = state.fileNavigationInfo,
                    readingStateManager = readingStateManager,
                    directZipHandler = directZipHandler,
                    onPageChanged = { currentPage, totalPages, zipFile ->
                        // ★ DirectZipHandlerにページ変更を通知
                        directZipHandler.updateCurrentPage(currentPage)
                        
                        // ★ ReadingStateManagerにも通知（メモリ更新のみ、保存はしない）
                        zipFile?.absolutePath?.let { filePath ->
                            readingStateManager.updateCurrentPage(filePath, currentPage, totalPages)
                        }

                        println("DEBUG: Page changed notification - Page: $currentPage/$totalPages")
                    }
                )
            }
        }

        is ViewState.DropboxBrowser -> {
            DropboxScreen(
                dropboxAuthManager = LocalDropboxAuthManager.current,
                currentLocalPath = currentPath, // 一元管理されたパスを渡す
                onBackToLocal = {
                    currentView = ViewState.LocalFileList
                },
                onDismiss = {
                    currentView = ViewState.LocalFileList
                },
                onOpenDownloadQueue = {
                    currentView = ViewState.DownloadQueue
                }
            )
        }

        is ViewState.DownloadQueue -> {
            DownloadQueueScreen(
                onDismiss = {
                    currentView = ViewState.LocalFileList
                },
                onOpenSettings = {
                    currentView = ViewState.Settings
                }
            )
        }

        is ViewState.Settings -> {
            SettingsScreen(
                readingStateManager = readingStateManager,
                directZipHandler = directZipHandler,
                onBackPressed = {
                    currentView = ViewState.LocalFileList
                }
            )
        }
    }
}