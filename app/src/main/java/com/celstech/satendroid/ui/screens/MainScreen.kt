package com.celstech.satendroid.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.celstech.satendroid.LocalDropboxAuthManager
import com.celstech.satendroid.navigation.FileNavigationManager
import com.celstech.satendroid.ui.models.ViewState
import com.celstech.satendroid.utils.DirectZipImageHandler
import com.celstech.satendroid.utils.ZipImageEntry
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import java.io.File

/**
 * データクラス：画像ビューア用の完全な状態（初期ページ位置を含む）
 */
data class ImageViewerState(
    val imageEntries: List<ZipImageEntry>,
    val currentZipUri: Uri,
    val currentZipFile: File?,
    val fileNavigationInfo: FileNavigationManager.NavigationInfo?,
    val initialPage: Int = 0  // 初期表示ページ（保存位置から取得）
) {
    val fileId: String
        get() = currentZipFile?.absolutePath ?: currentZipUri.toString()
}

/**
 * メイン画面 - 最適化されたZipファイル管理版
 * 1. ファイルを開いて 2. 前回の状態を取得 3. 該当するイメージを表示
 * Zipファイルを開いたままにして再オープンを回避
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalPermissionsApi::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Main state for the current view
    var currentView by remember { mutableStateOf<ViewState>(ViewState.LocalFileList) }

    // 統合された画像ビューア状態
    var imageViewerState by remember { mutableStateOf<ImageViewerState?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var showTopBar by remember { mutableStateOf(false) }

    // コルーチンジョブの管理（メモリリーク防止）
    var currentLoadingJob by remember { mutableStateOf<Job?>(null) }

    // State for directory navigation
    var savedDirectoryPath by remember { mutableStateOf("") }

    // 読書状態更新のための状態
    var lastReadingProgress by remember { mutableStateOf<Pair<File, Int>?>(null) }
    var fileCompletionUpdate by remember { mutableStateOf<File?>(null) }

    // 直接ZIP画像ハンドラー
    val directZipHandler = remember { DirectZipImageHandler(context) }

    // File navigation manager
    val fileNavigationManager = remember { FileNavigationManager(context) }

    // Pager state - ファイル変更時に適切なページに移動
    val pagerState = rememberPagerState { 
        imageViewerState?.imageEntries?.size ?: 0 
    }

    // ファイルが変更されたときに保存されたページに移動
    LaunchedEffect(imageViewerState?.fileId) {
        val state = imageViewerState
        if (state != null && state.imageEntries.isNotEmpty()) {
            val targetPage = state.initialPage.coerceIn(0, state.imageEntries.size - 1)
            println("DEBUG: Moving to saved position: $targetPage for file: ${state.fileId}")
            
            if (pagerState.currentPage != targetPage) {
                pagerState.scrollToPage(targetPage)
            }
        }
    }

    // 最適化されたファイル開く処理
    suspend fun openZipFile(zipUri: Uri, zipFile: File? = null): ImageViewerState? {
        return try {
            println("DEBUG: Opening ZIP file: ${zipFile?.name ?: zipUri}")
            
            // 1. 既存のZipファイルを閉じる
            directZipHandler.closeCurrentZipFile()
            
            // 2. ファイルを開いて画像エントリを取得
            val imageEntryList = directZipHandler.getImageEntriesFromZip(zipUri, zipFile)
            if (imageEntryList.isEmpty()) {
                println("DEBUG: No images found in ZIP")
                return null
            }
            
            // 3. Zipファイルを開いたままにする（最適化のポイント）
            // DirectZipImageHandlerが内部でZipファイルを準備する
            
            // 4. 前回の状態を取得
            val savedPosition = directZipHandler.getSavedPosition(zipUri, zipFile) ?: 0
            val validPosition = savedPosition.coerceIn(0, imageEntryList.size - 1)
            println("DEBUG: Saved position: $savedPosition, Valid position: $validPosition")
            
            // 5. ファイルナビゲーション情報を取得
            val navigationInfo = if (zipFile != null) {
                fileNavigationManager.getNavigationInfo(zipFile)
            } else null
            
            // 6. 統合状態を作成（該当するイメージ表示の準備完了）
            val result = ImageViewerState(
                imageEntries = imageEntryList,
                currentZipUri = zipUri,
                currentZipFile = zipFile,
                fileNavigationInfo = navigationInfo,
                initialPage = validPosition
            ).also {
                println("DEBUG: Created ImageViewerState - ${imageEntryList.size} images, initial page: $validPosition")
            }
            
            println("DEBUG: ZIP file opened and ready for optimized access")
            result
            
        } catch (e: Exception) {
            println("DEBUG: Error opening ZIP file: ${e.message}")
            e.printStackTrace()
            // エラー時はZipファイルを閉じる
            directZipHandler.closeCurrentZipFile()
            null
        }
    }

    // メモリクリア関数
    fun clearMemoryResources() {
        println("DEBUG: Clearing memory resources")
        directZipHandler.clearMemoryCache()
    }

    // 完全な状態クリア関数（Zipファイルも閉じる）
    fun clearAllStateAndMemory() {
        println("DEBUG: Clearing all state and memory")
        clearMemoryResources()
        directZipHandler.closeCurrentZipFile() // 最適化：Zipファイルを閉じる
        imageViewerState = null
        println("DEBUG: All state cleared")
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
            println("DEBUG: ZIP file picked from device")

            currentLoadingJob?.cancel()
            isLoading = true

            currentLoadingJob = coroutineScope.launch {
                try {
                    clearMemoryResources()
                    
                    val newState = openZipFile(uri)
                    if (newState != null) {
                        imageViewerState = newState
                        currentView = ViewState.ImageViewer
                        println("DEBUG: Successfully loaded ZIP from device")
                    } else {
                        println("DEBUG: Failed to load ZIP from device")
                    }
                } catch (e: Exception) {
                    println("DEBUG: Error loading ZIP from device: ${e.message}")
                    e.printStackTrace()
                } finally {
                    isLoading = false
                }
            }
        }
    }

    // Function to navigate to a new ZIP file
    fun navigateToZipFile(newZipFile: File) {
        println("DEBUG: Navigating to new file: ${newZipFile.name}")

        currentLoadingJob?.cancel()
        isLoading = true

        currentLoadingJob = coroutineScope.launch {
            try {
                clearMemoryResources()
                
                val newState = openZipFile(Uri.fromFile(newZipFile), newZipFile)
                if (newState != null) {
                    imageViewerState = newState
                    println("DEBUG: Successfully navigated to ${newZipFile.name}")
                } else {
                    println("DEBUG: Failed to navigate to ${newZipFile.name}")
                    imageViewerState = null
                }
            } catch (e: Exception) {
                println("DEBUG: Error navigating to file ${newZipFile.name}: ${e.message}")
                e.printStackTrace()
                imageViewerState = null
            } finally {
                isLoading = false
            }
        }
    }

    // 現在の位置を保存
    LaunchedEffect(pagerState.currentPage, imageViewerState?.fileId) {
        val state = imageViewerState
        if (state != null) {
            println("DEBUG: Saving position ${pagerState.currentPage} for file: ${state.fileId}")
            directZipHandler.saveCurrentPosition(
                state.currentZipUri,
                pagerState.currentPage,
                state.currentZipFile
            )
        }
    }

    // Auto-hide top bar
    LaunchedEffect(showTopBar) {
        if (showTopBar && imageViewerState != null) {
            delay(3000)
            showTopBar = false
        }
    }

    // Cleanup when component is disposed
    DisposableEffect(Unit) {
        onDispose {
            println("DEBUG: MainScreen disposing - cleaning up ZIP resources")
            currentLoadingJob?.cancel()
            clearAllStateAndMemory()
            directZipHandler.cleanup() // 最適化：完全なクリーンアップ
        }
    }

    // 画面遷移のハンドリング
    when (currentView) {
        is ViewState.LocalFileList -> {
            // ファイル一覧に戻った時はZipファイルを閉じる
            LaunchedEffect(currentView) {
                println("DEBUG: Returned to file list - closing ZIP file")
                directZipHandler.closeCurrentZipFile()
            }
            
            FileSelectionScreen(
                initialPath = savedDirectoryPath,
                onFileSelected = { file ->
                    println("DEBUG: File selected: ${file.name}")

                    currentLoadingJob?.cancel()
                    isLoading = true

                    currentLoadingJob = coroutineScope.launch {
                        try {
                            clearMemoryResources()
                            
                            val newState = openZipFile(Uri.fromFile(file), file)
                            if (newState != null) {
                                imageViewerState = newState
                                currentView = ViewState.ImageViewer
                                println("DEBUG: Successfully loaded ${file.name}")
                            } else {
                                println("DEBUG: Failed to load ${file.name}")
                            }
                        } catch (e: Exception) {
                            println("DEBUG: Error loading file ${file.name}: ${e.message}")
                            e.printStackTrace()
                        } finally {
                            isLoading = false
                        }
                    }
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
            val state = imageViewerState
            if (state != null) {
                DirectZipImageViewerScreen(
                    imageEntries = state.imageEntries,
                    currentZipFile = state.currentZipFile,
                    pagerState = pagerState,
                    showTopBar = showTopBar,
                    onToggleTopBar = { showTopBar = !showTopBar },
                    onBackToFiles = {
                        println("DEBUG: Back to files - closing ZIP file")
                        currentLoadingJob?.cancel()
                        clearAllStateAndMemory() // Zipファイルも閉じる
                        currentView = ViewState.LocalFileList
                    },
                    onNavigateToPreviousFile = {
                        println("DEBUG: Previous file button clicked")

                        state.currentZipFile?.let { currentFile ->
                            lastReadingProgress = Pair(currentFile, pagerState.currentPage)
                        }

                        state.fileNavigationInfo?.previousFile?.let { previousFile ->
                            navigateToZipFile(previousFile) // 新しいファイルを開く前に古いファイルを閉じる
                        } ?: println("DEBUG: No previous file available")
                    },
                    onNavigateToNextFile = {
                        println("DEBUG: Next file button clicked")

                        state.currentZipFile?.let { currentFile ->
                            fileCompletionUpdate = currentFile
                        }

                        state.fileNavigationInfo?.nextFile?.let { nextFile ->
                            navigateToZipFile(nextFile) // 新しいファイルを開く前に古いファイルを閉じる
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
            // Dropbox画面に移動する時もZipファイルを閉じる
            LaunchedEffect(currentView) {
                println("DEBUG: Moved to Dropbox browser - closing ZIP file")
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
            // 設定画面に移動する時もZipファイルを閉じる
            LaunchedEffect(currentView) {
                println("DEBUG: Moved to Settings - closing ZIP file")
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