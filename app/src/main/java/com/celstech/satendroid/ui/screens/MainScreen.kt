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
import androidx.core.net.toUri
import com.celstech.satendroid.LocalDropboxAuthManager
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

    // Zip image handler
    val zipImageHandler = remember { ZipImageHandler(context) }

    // Permission state for external storage
    val storagePermissionState = rememberPermissionState(
        android.Manifest.permission.READ_EXTERNAL_STORAGE
    )

    // Zip file picker launcher
    val zipPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            isLoading = true

            // Clear previous extracted files if any
            if (imageFiles.isNotEmpty()) {
                zipImageHandler.clearExtractedFiles(context, imageFiles)
            }

            // Extract images from ZIP
            coroutineScope.launch {
                try {
                    imageFiles = zipImageHandler.extractImagesFromZip(uri)
                    if (imageFiles.isNotEmpty()) {
                        currentView = ViewState.ImageViewer
                    }
                } catch (_: Exception) {
                    // Handle error silently for now
                } finally {
                    isLoading = false
                }
            }
        }
    }

    // Pager state for image viewing
    val pagerState = rememberPagerState { imageFiles.size }

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
                onFileSelected = { file ->
                    isLoading = true
                    // Clear previous extracted files if any
                    if (imageFiles.isNotEmpty()) {
                        zipImageHandler.clearExtractedFiles(context, imageFiles)
                    }

                    coroutineScope.launch {
                        try {
                            imageFiles = zipImageHandler.extractImagesFromZip(Uri.fromFile(file))
                            if (imageFiles.isNotEmpty()) {
                                currentView = ViewState.ImageViewer
                            }
                        } catch (_: Exception) {
                            // Handle error
                        } finally {
                            isLoading = false
                        }
                    }
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
                isLoading = isLoading
            )
        }

        is ViewState.ImageViewer -> {
            ImageViewerScreen(
                imageFiles = imageFiles,
                pagerState = pagerState,
                showTopBar = showTopBar,
                onToggleTopBar = { showTopBar = !showTopBar },
                onBackToFiles = {
                    zipImageHandler.clearExtractedFiles(context, imageFiles)
                    imageFiles = emptyList()
                    currentView = ViewState.LocalFileList
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
    }
}
