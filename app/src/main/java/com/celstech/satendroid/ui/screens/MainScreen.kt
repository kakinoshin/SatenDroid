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
 * メイン画面 - 全体的な画面遷移とステート管理を行う
 * 直接ZIPアクセス版 - テンポラリファイルに展開せずに直接画像を表示
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalPermissionsApi::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Main state for the current view
    var currentView by remember { mutableStateOf<ViewState>(ViewState.LocalFileList) }

    // State for image viewing - 直接アクセス版
    var imageEntries by remember { mutableStateOf<List<ZipImageEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var showTopBar by remember { mutableStateOf(false) }
    var currentZipUri by remember { mutableStateOf<Uri?>(null) }
    var currentZipFile by remember { mutableStateOf<File?>(null) }

    // State for file navigation
    var fileNavigationInfo by remember { mutableStateOf<FileNavigationManager.NavigationInfo?>(null) }

    // フラグ：ファイル移動中かどうかを追跡
    var isNavigatingToNewFile by remember { mutableStateOf(false) }
    
    // フラグ：位置復元中かどうかを追跡（位置保存を一時停止するため）
    var isRestoringPosition by remember { mutableStateOf(false) }

    // コルーチンジョブの管理（メモリリーク防止）
    var currentLoadingJob by remember { mutableStateOf<Job?>(null) }

    // State for directory navigation - ファイル選択画面の現在のパスを保持
    var savedDirectoryPath by remember { mutableStateOf("") }

    // 読書状態更新のための状態（non-nullファイルのみ）
    var lastReadingProgress by remember { mutableStateOf<Pair<File, Int>?>(null) }

    // ファイルナビゲーション時の読書状態更新用
    var fileCompletionUpdate by remember { mutableStateOf<File?>(null) }

    // 直接ZIP画像ハンドラー（テンポラリファイル展開不要）
    val directZipHandler = remember { DirectZipImageHandler(context) }

    // File navigation manager
    val fileNavigationManager = remember { FileNavigationManager(context) }

    // メモリクリア関数（状態は保持）
    fun clearMemoryResources() {
        println("DEBUG: Clearing memory resources only")

        // メモリキャッシュをクリア（テンポラリファイルは使用しないため、この処理のみ）
        directZipHandler.clearMemoryCache()

        println("DEBUG: Memory resources cleared")
    }

    // 完全な状態クリア関数
    fun clearAllStateAndMemory() {
        println("DEBUG: Starting comprehensive cleanup")

        // リソースをクリア
        clearMemoryResources()

        // 状態をクリア
        imageEntries = emptyList()
        currentZipUri = null
        currentZipFile = null
        fileNavigationInfo = null
        isNavigatingToNewFile = false
        isRestoringPosition = false

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

                    // 画像エントリを取得
                    val imageEntryList = directZipHandler.getImageEntriesFromZip(uri)

                    if (imageEntryList.isNotEmpty()) {
                        // 新しい状態を順序立てて設定（currentZipUriを最初に設定してPagerState再作成をトリガー）
                        currentZipUri = uri
                        currentZipFile = null
                        imageEntries = imageEntryList
                        currentView = ViewState.ImageViewer

                        println("DEBUG: Successfully loaded ZIP from device with ${imageEntryList.size} images")
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
                    isRestoringPosition = false  // 位置復元フラグもリセット
                }
            }
        }
    }

    // Pager state for image viewing
    val pagerState = rememberPagerState { imageEntries.size }

    // ファイルが変更されたときにPagerStateを0ページにリセット
    LaunchedEffect(
        if (currentZipUri != null) directZipHandler.generateFileIdentifier(currentZipUri!!, currentZipFile) else null
    ) {
        if (currentZipUri != null && imageEntries.isNotEmpty()) {
            val fileId = directZipHandler.generateFileIdentifier(currentZipUri!!, currentZipFile)
            println("DEBUG: File changed, resetting pager to page 0: $fileId")
            isRestoringPosition = true  // 位置復元開始フラグを立てる
            pagerState.scrollToPage(0)
        }
    }

    // 保存された位置を復元（ファイル移動完了後、ページリセット後）
    LaunchedEffect(currentZipUri, imageEntries.size, isNavigatingToNewFile) {
        if (imageEntries.isNotEmpty() && currentZipUri != null && !isNavigatingToNewFile) {
            // ページリセットが完了するのを待つ
            kotlinx.coroutines.delay(150)
            
            val fileId = directZipHandler.generateFileIdentifier(currentZipUri!!, currentZipFile)
            println("DEBUG: Attempting position restore for file: $fileId, imageEntries: ${imageEntries.size}")
            
            val savedPosition = directZipHandler.getSavedPosition(currentZipUri!!, currentZipFile)
            println("DEBUG: Saved position for file: $savedPosition")
            if (savedPosition != null && savedPosition < imageEntries.size && savedPosition > 0) {
                println("DEBUG: Restoring position to: $savedPosition")
                pagerState.animateScrollToPage(savedPosition)
            } else {
                println("DEBUG: No saved position or position is 0, staying at page 0")
            }
            
            // 位置復元完了フラグを下ろす
            isRestoringPosition = false
            println("DEBUG: Position restoration completed, enabling position saving")
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

                // 新しいファイル情報を準備
                println("DEBUG: Preparing new file info")
                val newZipUri = Uri.fromFile(newZipFile)

                // 画像エントリを取得
                println("DEBUG: Getting image entries from ${newZipFile.name}")
                val imageEntryList = directZipHandler.getImageEntriesFromZip(newZipUri, newZipFile)
                println("DEBUG: Got ${imageEntryList.size} image entries from ${newZipFile.name}")

                if (imageEntryList.isNotEmpty()) {
                    // ファイルナビゲーション情報を更新
                    println("DEBUG: Updating file navigation info")
                    val newNavigationInfo = fileNavigationManager.getNavigationInfo(newZipFile)

                    // 全ての新しい状態を一度に設定（UIの一貫性を保つ）
                    // currentZipUriを最初に設定することで、PagerStateが新しく作成される
                    println("DEBUG: Updating all state atomically")
                    currentZipUri = newZipUri
                    currentZipFile = newZipFile
                    fileNavigationInfo = newNavigationInfo
                    imageEntries = imageEntryList

                    println("DEBUG: Successfully navigated to ${newZipFile.name}, new PagerState will start at page 0")
                } else {
                    println("DEBUG: No images found in ${newZipFile.name}")
                    // 画像が見つからない場合は状態をリセット
                    currentZipUri = null
                    currentZipFile = null
                    fileNavigationInfo = null
                    imageEntries = emptyList()
                }
            } catch (e: Exception) {
                println("DEBUG: Error navigating to file ${newZipFile.name}: ${e.message}")
                e.printStackTrace()

                // エラー時は状態をリセット
                currentZipUri = null
                currentZipFile = null
                fileNavigationInfo = null
                imageEntries = emptyList()
            } finally {
                println("DEBUG: Completing navigation - setting isLoading=false, isNavigatingToNewFile=false")
                isLoading = false
                isNavigatingToNewFile = false
                isRestoringPosition = false  // 位置復元フラグもリセット
            }
        }
    }

    // 現在の位置を保存
    LaunchedEffect(pagerState.currentPage, currentZipUri) {
        if (currentZipUri != null && imageEntries.isNotEmpty() && !isNavigatingToNewFile && !isRestoringPosition) {
            val fileId = directZipHandler.generateFileIdentifier(currentZipUri!!, currentZipFile)
            println("DEBUG: Saving position ${pagerState.currentPage} for file: $fileId")
            directZipHandler.saveCurrentPosition(
                currentZipUri!!,
                pagerState.currentPage,
                currentZipFile
            )
        } else if (isRestoringPosition) {
            println("DEBUG: Skipping position save during restoration (currentPage: ${pagerState.currentPage})")
        }
    }

    // Auto-hide top bar after 3 seconds when manually shown
    LaunchedEffect(showTopBar) {
        if (showTopBar && imageEntries.isNotEmpty()) {
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

                            // 画像エントリを取得
                            val imageEntryList =
                                directZipHandler.getImageEntriesFromZip(Uri.fromFile(file), file)

                            if (imageEntryList.isNotEmpty()) {
                                // 新しい状態を順序立てて設定（currentZipUriを最初に設定してPagerState再作成をトリガー）
                                currentZipUri = Uri.fromFile(file)
                                currentZipFile = file
                                imageEntries = imageEntryList
                                fileNavigationInfo = fileNavigationManager.getNavigationInfo(file)
                                currentView = ViewState.ImageViewer

                                println("DEBUG: Successfully loaded ${file.name} with ${imageEntryList.size} images")
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
                            isRestoringPosition = false  // 位置復元フラグもリセット
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
            DirectZipImageViewerScreen(
                imageEntries = imageEntries,
                currentZipFile = currentZipFile,
                pagerState = pagerState,
                showTopBar = showTopBar,
                onToggleTopBar = { showTopBar = !showTopBar },
                onBackToFiles = {
                    // ジョブをキャンセル
                    currentLoadingJob?.cancel()

                    // 完全な状態とメモリクリア（currentZipUriをnullにすることでPagerStateもリセット）
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
                cacheManager = directZipHandler.getCacheManager(),
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
                cacheManager = directZipHandler.getCacheManager(),
                onBackPressed = {
                    currentView = ViewState.LocalFileList
                }
            )
        }
    }
}
