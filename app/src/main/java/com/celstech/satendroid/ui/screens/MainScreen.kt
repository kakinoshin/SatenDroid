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
import com.celstech.satendroid.utils.ZipImageHandler
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import java.io.File

/**
 * メイン画面 - 全体的な画面遷移とステート管理を行う
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalPermissionsApi::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Main state for the current view
    var currentView by remember { mutableStateOf<ViewState>(ViewState.LocalFileList) }

    // State for image viewing
    var imageFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var showTopBar by remember { mutableStateOf(false) }
    var currentZipUri by remember { mutableStateOf<Uri?>(null) }
    var currentZipFile by remember { mutableStateOf<File?>(null) }

    // State for file navigation
    var fileNavigationInfo by remember { mutableStateOf<FileNavigationManager.NavigationInfo?>(null) }

    // フラグ：ファイル移動中かどうかを追跡
    var isNavigatingToNewFile by remember { mutableStateOf(false) }

    // コルーチンジョブの管理（メモリリーク防止）
    var currentLoadingJob by remember { mutableStateOf<Job?>(null) }

    // State for directory navigation - ファイル選択画面の現在のパスを保持
    var savedDirectoryPath by remember { mutableStateOf("") }

    // 読書状態更新のための状態（non-nullファイルのみ）
    var lastReadingProgress by remember { mutableStateOf<Pair<File, Int>?>(null) }

    // ファイルナビゲーション時の読書状態更新用
    var fileCompletionUpdate by remember { mutableStateOf<File?>(null) }

    // Zip image handler
    val zipImageHandler = remember { ZipImageHandler(context) }

    // File navigation manager
    val fileNavigationManager = remember { FileNavigationManager(context) }

    // メモリクリア関数（状態は保持）
    fun clearMemoryResources() {
        println("DEBUG: Clearing memory resources only")

        // ファイルを削除
        if (imageFiles.isNotEmpty()) {
            zipImageHandler.clearExtractedFiles(context, imageFiles)
        }

        // ガベージコレクションを強制実行
        System.gc()

        println("DEBUG: Memory resources cleared")
    }

    // 完全な状態クリア関数
    fun clearAllStateAndMemory() {
        println("DEBUG: Starting comprehensive cleanup")

        // リソースをクリア
        clearMemoryResources()

        // 状態をクリア
        imageFiles = emptyList()
        currentZipUri = null
        currentZipFile = null
        fileNavigationInfo = null

        println("DEBUG: All state and memory cleared")
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

            // 既存のジョブをキャンセル
            currentLoadingJob?.cancel()

            isNavigatingToNewFile = true
            isLoading = true

            currentLoadingJob = coroutineScope.launch {
                try {
                    // リソースクリア（状態は保持）
                    clearMemoryResources()

                    // 画像を抽出
                    val extractedImages = zipImageHandler.extractImagesFromZip(uri)

                    if (extractedImages.isNotEmpty()) {
                        // 新しい状態を一度に設定
                        currentZipUri = uri
                        currentZipFile = null
                        imageFiles = extractedImages
                        currentView = ViewState.ImageViewer

                        println("DEBUG: Successfully loaded ZIP from device with ${extractedImages.size} images")
                    } else {
                        println("DEBUG: No images found in selected ZIP")
                        // 画像が見つからない場合は元の状態を保持
                    }
                } catch (e: Exception) {
                    println("DEBUG: Error loading ZIP from device: ${e.message}")
                    e.printStackTrace()
                } finally {
                    isLoading = false
                    isNavigatingToNewFile = false
                }
            }
        }
    }

    // Pager state for image viewing
    val pagerState = rememberPagerState { imageFiles.size }

    // 保存された位置を復元（ファイル移動中でない場合のみ）
    LaunchedEffect(imageFiles, currentZipUri, isNavigatingToNewFile) {
        println("DEBUG: LaunchedEffect for position restore - imageFiles: ${imageFiles.size}, currentZipUri: $currentZipUri, isNavigating: $isNavigatingToNewFile")
        if (imageFiles.isNotEmpty() && currentZipUri != null && !isNavigatingToNewFile) {
            val savedPosition = zipImageHandler.getSavedPosition(currentZipUri!!, currentZipFile)
            println("DEBUG: Saved position for file: $savedPosition")
            if (savedPosition != null && savedPosition < imageFiles.size) {
                println("DEBUG: Restoring position to: $savedPosition")
                pagerState.animateScrollToPage(savedPosition)
            }
        }
    }

    // Function to navigate to a new ZIP file
    fun navigateToZipFile(newZipFile: File) {
        println("DEBUG: Navigating to new file: ${newZipFile.name}")

        // 既存のジョブをキャンセル
        currentLoadingJob?.cancel()

        // ファイル移動中フラグを設定
        isNavigatingToNewFile = true
        isLoading = true

        currentLoadingJob = coroutineScope.launch {
            try {
                // 古いリソースをクリア（状態は保持してUIを安定させる）
                clearMemoryResources()

                // PagerStateを即座にリセット（アニメーションなし）
                println("DEBUG: Resetting pager to page 0")
                pagerState.scrollToPage(0)

                // 新しいファイル情報を準備
                println("DEBUG: Preparing new file info")
                val newZipUri = Uri.fromFile(newZipFile)

                // 画像を抽出
                println("DEBUG: Extracting images from ${newZipFile.name}")
                val extractedImages = zipImageHandler.extractImagesFromZip(newZipUri)
                println("DEBUG: Extracted ${extractedImages.size} images from ${newZipFile.name}")

                if (extractedImages.isNotEmpty()) {
                    // ファイルナビゲーション情報を更新
                    println("DEBUG: Updating file navigation info")
                    val newNavigationInfo = fileNavigationManager.getNavigationInfo(newZipFile)

                    // 全ての新しい状態を一度に設定（UIの一貫性を保つ）
                    println("DEBUG: Updating all state atomically")
                    currentZipUri = newZipUri
                    currentZipFile = newZipFile
                    fileNavigationInfo = newNavigationInfo
                    imageFiles = extractedImages

                    // 確実に最初のページを表示
                    println("DEBUG: Final scroll to page 0")
                    pagerState.scrollToPage(0)

                    println("DEBUG: Successfully navigated to ${newZipFile.name}, showing page 0 of ${extractedImages.size}")
                } else {
                    println("DEBUG: No images found in ${newZipFile.name}")
                    // 画像が見つからない場合は状態をリセット
                    currentZipUri = null
                    currentZipFile = null
                    fileNavigationInfo = null
                    imageFiles = emptyList()
                }
            } catch (e: Exception) {
                println("DEBUG: Error navigating to file ${newZipFile.name}: ${e.message}")
                e.printStackTrace()

                // エラー時は状態をリセット
                currentZipUri = null
                currentZipFile = null
                fileNavigationInfo = null
                imageFiles = emptyList()
            } finally {
                println("DEBUG: Completing navigation - setting isLoading=false, isNavigatingToNewFile=false")
                isLoading = false
                isNavigatingToNewFile = false
            }
        }
    }

    // 現在の位置を保存
    LaunchedEffect(pagerState.currentPage, currentZipUri) {
        if (currentZipUri != null && imageFiles.isNotEmpty()) {
            zipImageHandler.saveCurrentPosition(
                currentZipUri!!,
                pagerState.currentPage,
                currentZipFile
            )
        }
    }

    // Auto-hide top bar after 3 seconds when manually shown
    LaunchedEffect(showTopBar) {
        if (showTopBar && imageFiles.isNotEmpty()) {
            delay(3000)
            showTopBar = false
        }
    }

    // Cleanup extracted files when component is disposed
    DisposableEffect(Unit) {
        onDispose {
            println("DEBUG: MainScreen disposing - cleaning up all resources")

            // ジョブをキャンセル
            currentLoadingJob?.cancel()

            // 完全なクリア
            clearAllStateAndMemory()

            println("DEBUG: MainScreen cleanup completed")
        }
    }

    // 画面遷移のハンドリング
    when (currentView) {
        is ViewState.LocalFileList -> {
            FileSelectionScreen(
                initialPath = savedDirectoryPath,
                onFileSelected = { file ->
                    println("DEBUG: File selected: ${file.name}")

                    // 既存のジョブをキャンセル
                    currentLoadingJob?.cancel()

                    isNavigatingToNewFile = true
                    isLoading = true

                    currentLoadingJob = coroutineScope.launch {
                        try {
                            // リソースクリア（状態は保持）
                            clearMemoryResources()

                            // 画像を抽出
                            val extractedImages =
                                zipImageHandler.extractImagesFromZip(Uri.fromFile(file))

                            if (extractedImages.isNotEmpty()) {
                                // 新しい状態を一度に設定
                                currentZipUri = Uri.fromFile(file)
                                currentZipFile = file
                                imageFiles = extractedImages
                                fileNavigationInfo = fileNavigationManager.getNavigationInfo(file)
                                currentView = ViewState.ImageViewer

                                println("DEBUG: Successfully loaded ${file.name} with ${extractedImages.size} images")
                            } else {
                                println("DEBUG: No images found in ${file.name}")
                                // 画像が見つからない場合は元の状態を保持
                            }
                        } catch (e: Exception) {
                            println("DEBUG: Error loading file ${file.name}: ${e.message}")
                            e.printStackTrace()
                        } finally {
                            isLoading = false
                            isNavigatingToNewFile = false
                        }
                    }
                },
                onDirectoryChanged = { path ->
                    // 現在のディレクトリパスを保存
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
                        println("DEBUG: Returned from image viewer - reading status will be updated")
                        // readingStatusUpdateとして渡された後、lastReadingProgressをクリア
                    }
                } else null,
                readingStatusUpdate = lastReadingProgress?.also {
                    // 読書状態更新データが使用されたらクリア
                    lastReadingProgress = null
                },
                fileCompletionUpdate = fileCompletionUpdate?.also {
                    // 既読マークデータが使用されたらクリア
                    fileCompletionUpdate = null
                }
            )
        }

        is ViewState.ImageViewer -> {
            ImageViewerScreen(
                imageFiles = imageFiles,
                currentZipFile = currentZipFile,
                pagerState = pagerState,
                showTopBar = showTopBar,
                onToggleTopBar = { showTopBar = !showTopBar },
                onBackToFiles = {
                    // ジョブをキャンセル
                    currentLoadingJob?.cancel()

                    // 完全な状態とメモリクリア
                    clearAllStateAndMemory()

                    currentView = ViewState.LocalFileList
                },
                onNavigateToPreviousFile = {
                    println("DEBUG: Previous file button clicked")

                    // 現在のファイルの読書状態を保存（現在のページで）
                    currentZipFile?.let { currentFile ->
                        lastReadingProgress = Pair(currentFile, pagerState.currentPage)
                        println("DEBUG: Saving current progress before moving to previous file: page ${pagerState.currentPage + 1}")
                    }

                    fileNavigationInfo?.previousFile?.let { previousFile ->
                        println("DEBUG: Navigating to previous file: ${previousFile.name}")
                        navigateToZipFile(previousFile)
                    } ?: println("DEBUG: No previous file available")
                },
                onNavigateToNextFile = {
                    println("DEBUG: Next file button clicked")

                    // 現在のファイルを既読にマーク（最後まで読んだとして扱う）
                    currentZipFile?.let { currentFile ->
                        fileCompletionUpdate = currentFile
                        println("DEBUG: Marking current file as completed before moving to next file: ${currentFile.name}")
                    }

                    fileNavigationInfo?.nextFile?.let { nextFile ->
                        println("DEBUG: Navigating to next file: ${nextFile.name}")
                        navigateToZipFile(nextFile)
                    } ?: println("DEBUG: No next file available")
                },
                fileNavigationInfo = fileNavigationInfo,
                cacheManager = zipImageHandler.getCacheManager(),
                onPageChanged = { currentPage, totalPages, zipFile ->
                    // 読書進捗を保存
                    lastReadingProgress = Pair(zipFile, currentPage)
                    println("DEBUG: Reading progress updated - Page: ${currentPage + 1}/$totalPages, File: ${zipFile.name}")

                    // 最後のページに到達した場合は自動的に既読にマーク
                    if (currentPage >= totalPages - 1) {
                        println("DEBUG: Reached last page, marking as completed automatically")
                        fileCompletionUpdate = zipFile
                    }
                }
            )
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
            SettingsScreen(
                cacheManager = zipImageHandler.getCacheManager(),
                onBackPressed = {
                    currentView = ViewState.LocalFileList
                }
            )
        }
    }
}
