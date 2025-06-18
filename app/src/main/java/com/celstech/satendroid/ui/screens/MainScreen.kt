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
    
    // State for directory navigation - ファイル選択画面の現在のパスを保持
    var savedDirectoryPath by remember { mutableStateOf("") }

    // Zip image handler
    val zipImageHandler = remember { ZipImageHandler(context) }
    
    // File navigation manager
    val fileNavigationManager = remember { FileNavigationManager(context) }

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
            isNavigatingToNewFile = true
            isLoading = true
            
            // Clear previous extracted files if any
            if (imageFiles.isNotEmpty()) {
                zipImageHandler.clearExtractedFiles(context, imageFiles)
            }

            // Extract images from ZIP
            coroutineScope.launch {
                try {
                    // まず状態をクリア
                    imageFiles = emptyList()
                    
                    // 新しいファイル情報を設定
                    currentZipUri = uri
                    currentZipFile = null
                    
                    // 画像を抽出
                    val extractedImages = zipImageHandler.extractImagesFromZip(uri)
                    
                    if (extractedImages.isNotEmpty()) {
                        imageFiles = extractedImages
                        currentView = ViewState.ImageViewer
                        println("DEBUG: Successfully loaded ZIP from device with ${extractedImages.size} images")
                    }
                } catch (e: Exception) {
                    println("DEBUG: Error loading ZIP from device: ${e.message}")
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
        
        // ファイル移動中フラグを設定
        isNavigatingToNewFile = true
        
        // Clear current images
        if (imageFiles.isNotEmpty()) {
            println("DEBUG: Clearing ${imageFiles.size} existing images")
            zipImageHandler.clearExtractedFiles(context, imageFiles)
        }
        
        isLoading = true
        
        coroutineScope.launch {
            try {
                // まず状態をクリア
                println("DEBUG: Clearing imageFiles state")
                imageFiles = emptyList()
                
                // PagerStateを即座にリセット（アニメーションなし）
                println("DEBUG: Resetting pager to page 0")
                pagerState.scrollToPage(0)
                
                // 新しいファイル情報を設定
                println("DEBUG: Setting new file info")
                currentZipUri = Uri.fromFile(newZipFile)
                currentZipFile = newZipFile
                
                // 画像を抽出
                println("DEBUG: Extracting images from ${newZipFile.name}")
                val extractedImages = zipImageHandler.extractImagesFromZip(Uri.fromFile(newZipFile))
                println("DEBUG: Extracted ${extractedImages.size} images from ${newZipFile.name}")
                
                if (extractedImages.isNotEmpty()) {
                    // ファイルナビゲーション情報を更新
                    println("DEBUG: Updating file navigation info")
                    fileNavigationInfo = fileNavigationManager.getNavigationInfo(newZipFile)
                    
                    // 画像リストを最後に更新（これによりUI更新がトリガーされる）
                    println("DEBUG: Setting imageFiles to ${extractedImages.size} images")
                    imageFiles = extractedImages
                    
                    // 確実に最初のページを表示
                    println("DEBUG: Final scroll to page 0")
                    pagerState.scrollToPage(0)
                    
                    println("DEBUG: Successfully navigated to ${newZipFile.name}, showing page 0 of ${extractedImages.size}")
                } else {
                    println("DEBUG: No images found in ${newZipFile.name}")
                }
            } catch (e: Exception) {
                println("DEBUG: Error navigating to file ${newZipFile.name}: ${e.message}")
                e.printStackTrace()
            } finally {
                println("DEBUG: Completing navigation - setting isLoading=false, isNavigatingToNewFile=false")
                isLoading = false
                // ファイル移動中フラグをクリア
                isNavigatingToNewFile = false
            }
        }
    }

    // 現在の位置を保存
    LaunchedEffect(pagerState.currentPage, currentZipUri) {
        if (currentZipUri != null && imageFiles.isNotEmpty()) {
            zipImageHandler.saveCurrentPosition(currentZipUri!!, pagerState.currentPage, currentZipFile)
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
            zipImageHandler.clearExtractedFiles(context, imageFiles)
        }
    }

    // 画面遷移のハンドリング
    when (currentView) {
        is ViewState.LocalFileList -> {
            FileSelectionScreen(
                initialPath = savedDirectoryPath,
                onFileSelected = { file ->
                    println("DEBUG: File selected: ${file.name}")
                    isNavigatingToNewFile = true
                    isLoading = true
                    
                    // Clear previous extracted files if any
                    if (imageFiles.isNotEmpty()) {
                        zipImageHandler.clearExtractedFiles(context, imageFiles)
                    }

                    coroutineScope.launch {
                        try {
                            // まず状態をクリア
                            imageFiles = emptyList()
                            
                            // 新しいファイル情報を設定
                            currentZipUri = Uri.fromFile(file)
                            currentZipFile = file
                            
                            // 画像を抽出
                            val extractedImages = zipImageHandler.extractImagesFromZip(Uri.fromFile(file))
                            
                            if (extractedImages.isNotEmpty()) {
                                // 画像リストを更新
                                imageFiles = extractedImages
                                
                                // Update file navigation info
                                fileNavigationInfo = fileNavigationManager.getNavigationInfo(file)
                                currentView = ViewState.ImageViewer
                                
                                println("DEBUG: Successfully loaded ${file.name} with ${extractedImages.size} images")
                            }
                        } catch (e: Exception) {
                            println("DEBUG: Error loading file ${file.name}: ${e.message}")
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
                isLoading = isLoading
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
                    zipImageHandler.clearExtractedFiles(context, imageFiles)
                    imageFiles = emptyList()
                    currentZipUri = null
                    currentZipFile = null
                    fileNavigationInfo = null
                    currentView = ViewState.LocalFileList
                },
                onNavigateToPreviousFile = {
                    println("DEBUG: Previous file button clicked")
                    fileNavigationInfo?.previousFile?.let { previousFile ->
                        println("DEBUG: Navigating to previous file: ${previousFile.name}")
                        navigateToZipFile(previousFile)
                    } ?: println("DEBUG: No previous file available")
                },
                onNavigateToNextFile = {
                    println("DEBUG: Next file button clicked")
                    fileNavigationInfo?.nextFile?.let { nextFile ->
                        println("DEBUG: Navigating to next file: ${nextFile.name}")
                        navigateToZipFile(nextFile)
                    } ?: println("DEBUG: No next file available")
                },
                fileNavigationInfo = fileNavigationInfo,
                cacheManager = zipImageHandler.getCacheManager()
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
