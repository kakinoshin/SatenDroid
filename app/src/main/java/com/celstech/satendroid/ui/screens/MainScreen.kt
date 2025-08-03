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
import com.celstech.satendroid.ui.models.ImageViewerState
import com.celstech.satendroid.ui.models.ViewState
import com.celstech.satendroid.utils.DirectZipImageHandler
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
 * メイン画面 - シンプルで確実に動作する版
 * 複雑な State Machine を削除し、基本機能の確実な動作を優先
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

    // 画像ビューア用の状態
    var currentImageViewerState by remember { mutableStateOf<ImageViewerState?>(null) }

    // 読書状態更新のための状態
    var lastReadingProgress by remember { mutableStateOf<Pair<File, Int>?>(null) }
    var fileCompletionUpdate by remember { mutableStateOf<File?>(null) }
    var showTopBar by remember { mutableStateOf(false) }
    var savedDirectoryPath by remember { mutableStateOf("") }

    // 直接ZIP画像ハンドラー
    val directZipHandler = remember {
        DirectZipImageHandler(context).also {
            println("DEBUG: DirectZipImageHandler created")
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

    // シンプルなファイル読み込み関数
    fun startSimpleFileLoading(uri: Uri, file: File? = null) {
        coroutineScope.launch {
            try {
                isLoading = true
                loadingMessage = "ファイルを準備中..."
                errorMessage = null

                println("DEBUG: Starting simple file loading for ${file?.name ?: uri}")

                // リソースクリア
                withContext(Dispatchers.IO) {
                    directZipHandler.clearMemoryCache()
                    directZipHandler.closeCurrentZipFile()
                }

                loadingMessage = "ZIP内の画像を検索中..."

                // 画像エントリ取得
                val imageEntryList = withContext(Dispatchers.IO) {
                    directZipHandler.getImageEntriesFromZip(uri, file)
                }

                if (imageEntryList.isEmpty()) {
                    errorMessage = "このZIPファイルには表示可能な画像がありません"
                    isLoading = false
                    return@launch
                }

                // 初期ページの決定
                val initialPage = run {
                    val savedPosition = directZipHandler.getSavedPosition(uri, file) ?: 0
                    savedPosition.coerceIn(0, imageEntryList.size - 1)
                }

                // ナビゲーション情報の取得
                val navigationInfo = file?.let { f ->
                    fileNavigationManager.getNavigationInfo(f)
                }

                // 状態設定
                currentImageViewerState = ImageViewerState(
                    imageEntries = imageEntryList,
                    currentZipUri = uri,
                    currentZipFile = file,
                    fileNavigationInfo = navigationInfo,
                    initialPage = initialPage
                )

                isLoading = false
                currentView = ViewState.ImageViewer

                println("DEBUG: File loading completed successfully - ${imageEntryList.size} images")

            } catch (e: Exception) {
                println("ERROR: File loading failed: ${e.message}")
                e.printStackTrace()
                errorMessage = "ファイルの読み込みに失敗しました: ${e.message}"
                isLoading = false
            }
        }
    }

    // ファイルナビゲーション処理
    fun navigateToZipFile(newZipFile: File, isNextFile: Boolean = false) {
        coroutineScope.launch {
            try {
                println("DEBUG: Navigating to file: ${newZipFile.name} (isNext: $isNextFile)")

                // 現在の位置を保存
                currentImageViewerState?.let { state ->
                    if (pagerState.currentPage >= 0) {
                        directZipHandler.saveCurrentPosition(
                            state.currentZipUri,
                            pagerState.currentPage,
                            state.currentZipFile
                        )
                        println("DEBUG: Saved position ${pagerState.currentPage} for ${state.currentZipFile?.name}")
                    }
                }

                // 新しいファイルを読み込み
                startSimpleFileLoading(Uri.fromFile(newZipFile), newZipFile)

            } catch (e: Exception) {
                println("ERROR: Navigation failed: ${e.message}")
                errorMessage = "ファイルの切り替えに失敗しました: ${e.message}"
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

    // ページ変更時の保存処理
    LaunchedEffect(pagerState.currentPage, currentImageViewerState?.fileId) {
        val state = currentImageViewerState
        val currentPage = pagerState.currentPage

        if (state != null && currentPage >= 0 && !isLoading) {
            delay(1000) // 1秒後に保存

            // 状態が変わっていないことを確認
            if (currentImageViewerState?.fileId == state.fileId &&
                pagerState.currentPage == currentPage &&
                !isLoading
            ) {
                try {
                    directZipHandler.saveCurrentPosition(
                        state.currentZipUri,
                        currentPage,
                        state.currentZipFile
                    )
                    println("DEBUG: Auto-saved position $currentPage")
                } catch (e: Exception) {
                    println("ERROR: Failed to save position: ${e.message}")
                }
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

    // クリーンアップ
    DisposableEffect(Unit) {
        onDispose {
            println("DEBUG: MainScreen disposing")
            try {
                runBlocking {
                    directZipHandler.clearMemoryCache()
                    directZipHandler.closeCurrentZipFile()
                    directZipHandler.cleanup()
                }
                println("DEBUG: Cleanup completed")
            } catch (e: Exception) {
                println("ERROR: Cleanup error: ${e.message}")
            }
        }
    }

    // UI表示
    when (currentView) {
        is ViewState.LocalFileList -> {
            FileSelectionScreen(
                initialPath = savedDirectoryPath,
                onFileSelected = { file ->
                    startSimpleFileLoading(Uri.fromFile(file), file)
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
                isLoading = isLoading,
                onReturnFromViewer = if (lastReadingProgress != null) {
                    { /* 処理 */ }
                } else null,
                readingStatusUpdate = lastReadingProgress,
                fileCompletionUpdate = fileCompletionUpdate
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
                        currentView = ViewState.LocalFileList
                    },
                    onNavigateToPreviousFile = {
                        coroutineScope.launch {
                            state.currentZipFile?.let { currentFile ->
                                lastReadingProgress = Pair(currentFile, pagerState.currentPage)
                            }

                            state.fileNavigationInfo?.previousFile?.let { previousFile ->
                                navigateToZipFile(previousFile)
                            }
                        }
                    },
                    onNavigateToNextFile = {
                        coroutineScope.launch {
                            state.currentZipFile?.let { currentFile ->
                                fileCompletionUpdate = currentFile
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