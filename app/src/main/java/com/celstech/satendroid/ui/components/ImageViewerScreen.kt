package com.celstech.satendroid.ui.components

import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.net.toUri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.rememberAsyncImagePainter
import com.celstech.satendroid.LocalDropboxAuthManager
import com.celstech.satendroid.dropbox.DropboxAuthManager
import com.celstech.satendroid.dropbox.DropboxAuthState
import com.celstech.satendroid.utils.ZipImageHandler
import com.celstech.satendroid.viewmodel.LocalFileViewModel
import com.celstech.satendroid.viewmodel.LocalItem
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalFoundationApi::class, ExperimentalPermissionsApi::class)
@Composable
fun ImageViewerScreen() {
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

    when (currentView) {
        is ViewState.LocalFileList -> {
            LocalFileListScreen(
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

// View state management
private sealed class ViewState {
    object LocalFileList : ViewState()
    object ImageViewer : ViewState()
    object DropboxBrowser : ViewState()
}

@Composable
private fun LocalFileListScreen(
    onFileSelected: (File) -> Unit,
    onOpenFromDevice: () -> Unit,
    onOpenFromDropbox: () -> Unit,
    isLoading: Boolean
) {
    val context = LocalContext.current
    val viewModel: LocalFileViewModel = viewModel(
        factory = LocalFileViewModel.provideFactory(context)
    )
    val uiState by viewModel.uiState.collectAsState()

    // Scan for files on first load
    LaunchedEffect(Unit) {
        viewModel.scanDirectory("")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        // Header
        Text(
            text = "SatenDroid",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Text(
            text = "ZIP Image Viewer",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            modifier = Modifier.padding(bottom = 24.dp)
        )

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onOpenFromDevice,
                modifier = Modifier.weight(1f),
                enabled = !isLoading
            ) {
                Text("📱 Open from Device")
            }

            OutlinedButton(
                onClick = onOpenFromDropbox,
                modifier = Modifier.weight(1f),
                enabled = !isLoading
            ) {
                Text("☁️ Open from Dropbox")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Navigation bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (uiState.isSelectionMode) {
                // Selection mode toolbar
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { viewModel.exitSelectionMode() }) {
                        Text("✕", style = MaterialTheme.typography.titleLarge)
                    }

                    Text(
                        text = "${uiState.selectedItems.size} selected",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    if (uiState.selectedItems.size == uiState.localItems.size && uiState.localItems.isNotEmpty()) {
                        TextButton(onClick = { viewModel.deselectAll() }) {
                            Text("Deselect All")
                        }
                    } else if (uiState.localItems.isNotEmpty()) {
                        TextButton(onClick = { viewModel.selectAll() }) {
                            Text("Select All")
                        }
                    }

                    if (uiState.selectedItems.isNotEmpty()) {
                        Button(
                            onClick = { viewModel.setShowDeleteConfirmDialog(true) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Text("🗑️ Delete (${uiState.selectedItems.size})")
                        }
                    }
                }
            } else {
                // Normal navigation bar
                if (uiState.currentPath.isNotEmpty()) {
                    Button(
                        onClick = { viewModel.navigateBack() },
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text("← Back")
                    }
                }

                Text(
                    text = if (uiState.currentPath.isEmpty()) "📁 Local Files" else "📁 ${uiState.currentPath}",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )

                TextButton(
                    onClick = { viewModel.scanDirectory(uiState.currentPath) },
                    enabled = !uiState.isRefreshing
                ) {
                    Text("🔄 Refresh")
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        when {
            isLoading -> {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Loading...")
                    }
                }
            }

            uiState.isRefreshing -> {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Scanning for files...")
                    }
                }
            }

            uiState.localItems.isEmpty() -> {
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "📁",
                            style = MaterialTheme.typography.displayLarge,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )

                        Text(
                            text = if (uiState.currentPath.isEmpty()) "No ZIP files found" else "Empty folder",
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = if (uiState.currentPath.isEmpty())
                                "Download ZIP files from Dropbox or select from your device to get started"
                            else
                                "This folder doesn't contain any ZIP files",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = uiState.localItems,
                        key = { item -> item.path }
                    ) { item ->
                        LocalItemCard(
                            item = item,
                            isSelected = uiState.selectedItems.contains(item),
                            isSelectionMode = uiState.isSelectionMode,
                            onClick = {
                                if (uiState.isSelectionMode) {
                                    viewModel.toggleItemSelection(item)
                                } else {
                                    when (item) {
                                        is LocalItem.Folder -> viewModel.navigateToFolder(item.path)
                                        is LocalItem.ZipFile -> onFileSelected(item.file)
                                    }
                                }
                            },
                            onLongClick = {
                                if (!uiState.isSelectionMode) {
                                    viewModel.enterSelectionMode(item)
                                }
                            },
                            onDeleteClick = {
                                viewModel.setItemToDelete(item)
                                viewModel.setShowDeleteConfirmDialog(true)
                            }
                        )
                    }
                }
            }
        }

        // Delete confirmation dialog
        if (uiState.showDeleteConfirmDialog) {
            AlertDialog(
                onDismissRequest = {
                    viewModel.setShowDeleteConfirmDialog(false)
                    viewModel.setItemToDelete(null)
                },
                title = {
                    Text(
                        text = if (uiState.itemToDelete != null) "Delete Item?" else "Delete ${uiState.selectedItems.size} Items?"
                    )
                },
                text = {
                    Text(
                        text = if (uiState.itemToDelete != null) {
                            when (uiState.itemToDelete) {
                                is LocalItem.Folder -> "Are you sure you want to delete the folder '${uiState.itemToDelete!!.name}' and all its contents?"
                                is LocalItem.ZipFile -> "Are you sure you want to delete the file '${uiState.itemToDelete!!.name}'? This action cannot be undone."
                                else -> ""
                            }
                        } else {
                            "Are you sure you want to delete ${uiState.selectedItems.size} selected items? This action cannot be undone."
                        }
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (uiState.itemToDelete != null) {
                                when (val item = uiState.itemToDelete!!) {
                                    is LocalItem.Folder -> {
                                        val success = viewModel.deleteFolder(item)
                                        if (!success) {
                                            println("DEBUG: Failed to delete ${uiState.itemToDelete!!.name}")
                                        }
                                        viewModel.setItemToDelete(null)
                                        viewModel.scanDirectory(uiState.currentPath)
                                        viewModel.setShowDeleteConfirmDialog(false)
                                    }

                                    is LocalItem.ZipFile -> {
                                        viewModel.setItemToDelete(item)
                                        viewModel.setShowDeleteConfirmDialog(false)
                                        viewModel.setShowDeleteZipWithPermissionDialog(true)
                                    }
                                }
                            } else {
                                viewModel.deleteSelectedItems()
                                viewModel.setShowDeleteConfirmDialog(false)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            viewModel.setShowDeleteConfirmDialog(false)
                            viewModel.setItemToDelete(null)
                        }
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }

        // ZIP file deletion with permission dialog
        if (uiState.showDeleteZipWithPermissionDialog && uiState.itemToDelete is LocalItem.ZipFile) {
            DeleteFileWithPermission(
                item = uiState.itemToDelete as LocalItem.ZipFile,
                onDeleteResult = { success ->
                    if (!success) {
                        println("DEBUG: Failed to delete ${uiState.itemToDelete!!.name}")
                    }
                    viewModel.setItemToDelete(null)
                    viewModel.setShowDeleteZipWithPermissionDialog(false)
                    viewModel.scanDirectory(uiState.currentPath)
                }
            )
        }
    }
}

@Composable
private fun LocalItemCard(
    item: LocalItem,
    isSelected: Boolean = false,
    isSelectionMode: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onDeleteClick: () -> Unit = {}
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { onLongClick() }
                )
            },
        colors = CardDefaults.cardColors(
            containerColor = when {
                isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                item is LocalItem.Folder -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Selection checkbox or icon
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() },
                    modifier = Modifier.padding(end = 16.dp)
                )
            } else {
                Text(
                    text = when (item) {
                        is LocalItem.Folder -> "📁"
                        is LocalItem.ZipFile -> "🗜️"
                    },
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(end = 16.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2
                )

                when (item) {
                    is LocalItem.Folder -> {
                        val description = if (item.zipCount > 0) {
                            "${item.zipCount} ZIP file${if (item.zipCount != 1) "s" else ""}"
                        } else {
                            "Contains subfolders"
                        }

                        Text(
                            text = "$description • ${formatDate(item.lastModified)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )

                        Text(
                            text = "Folder • Tap to open",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }

                    is LocalItem.ZipFile -> {
                        Text(
                            text = "${formatFileSize(item.size)} • ${formatDate(item.lastModified)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )

                        Text(
                            text = item.file.parent ?: "Unknown location",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
            }

            // Action buttons
            if (!isSelectionMode) {
                Row {
                    // Delete button
                    IconButton(
                        onClick = {
                            onDeleteClick()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }

                    // Open indicator
                    Text(
                        text = when (item) {
                            is LocalItem.Folder -> "📂"
                            is LocalItem.ZipFile -> "▶"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageViewerScreen(
    imageFiles: List<File>,
    pagerState: androidx.compose.foundation.pager.PagerState,
    showTopBar: Boolean,
    onToggleTopBar: () -> Unit,
    onBackToFiles: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Full-screen image pager
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .clickable { onToggleTopBar() },
            key = { index -> imageFiles[index].absolutePath },
            userScrollEnabled = true
        ) { index ->
            Image(
                painter = rememberAsyncImagePainter(model = imageFiles[index]),
                contentDescription = "Image ${index + 1}",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }

        // Top clickable area for going back
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clickable { onBackToFiles() }
                .align(Alignment.TopCenter)
        )

        // Top bar with image info
        if (showTopBar) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.8f))
                    .padding(16.dp)
                    .align(Alignment.TopCenter)
            ) {
                Column {
                    Text(
                        text = "Image ${pagerState.currentPage + 1} of ${imageFiles.size}",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (imageFiles.isNotEmpty() && pagerState.currentPage < imageFiles.size) {
                        Text(
                            text = imageFiles[pagerState.currentPage].name,
                            color = Color.White.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Tap top area to go back to file list",
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

// Sealed class to represent Dropbox items
private sealed class DropboxItem {
    abstract val name: String
    abstract val path: String

    data class Folder(
        override val name: String,
        override val path: String
    ) : DropboxItem()

    data class ZipFile(
        override val name: String,
        override val path: String,
        val size: Long
    ) : DropboxItem()
}

// Data class for download progress tracking
private data class DownloadProgress(
    val fileName: String = "",
    val currentFileProgress: Float = 0f, // 0.0 to 1.0
    val currentFileIndex: Int = 0,
    val totalFiles: Int = 1,
    val downloadSpeed: String = "",
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val estimatedTimeRemaining: String = ""
) {
    val overallProgress: Float
        get() =
            if (totalFiles <= 1) currentFileProgress
            else (currentFileIndex.toFloat() + currentFileProgress) / totalFiles.toFloat()

    val progressText: String
        get() =
            if (totalFiles <= 1) "${(currentFileProgress * 100).toInt()}%"
            else "File ${currentFileIndex + 1}/$totalFiles (${(overallProgress * 100).toInt()}%)"
}

@Composable
private fun DropboxScreen(
    dropboxAuthManager: DropboxAuthManager,
    onBackToLocal: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val authState by dropboxAuthManager.authState.collectAsState()

    // State for file browsing
    var dropboxItems by remember { mutableStateOf<List<DropboxItem>>(emptyList()) }
    var currentPath by remember { mutableStateOf("") }
    var isLoadingFiles by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var downloadMessage by remember { mutableStateOf<String?>(null) }

    // Progress tracking state
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(DownloadProgress()) }

    // Function to download ZIP file to Downloads folder
    fun downloadZipFile(item: DropboxItem.ZipFile, client: com.dropbox.core.v2.DbxClientV2) {
        coroutineScope.launch {
            try {
                isDownloading = true
                isLoadingFiles = true
                downloadMessage = null
                downloadProgress = DownloadProgress(
                    fileName = item.name,
                    totalBytes = item.size,
                    totalFiles = 1
                )

                // Use app-specific external files directory (reliable access)
                val appDownloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: context.filesDir // Fallback to internal storage

                val satenDroidDir = File(appDownloadsDir, "SatenDroid")

                // Create directory with detailed error checking
                if (!satenDroidDir.exists()) {
                    val created = satenDroidDir.mkdirs()
                    println("DEBUG: Directory creation result: $created")
                    println("DEBUG: Directory path: ${satenDroidDir.absolutePath}")
                    println("DEBUG: Directory exists after creation: ${satenDroidDir.exists()}")

                    if (!created && !satenDroidDir.exists()) {
                        throw Exception("Failed to create download directory: ${satenDroidDir.absolutePath}")
                    }
                }

                val localFile = File(satenDroidDir, item.name)
                println("DEBUG: Downloading to: ${localFile.absolutePath}")

                // Check if parent directory is writable
                if (!satenDroidDir.canWrite()) {
                    throw Exception("Cannot write to directory: ${satenDroidDir.absolutePath}")
                }

                var startTime = System.currentTimeMillis()

                withContext(Dispatchers.IO) {
                    val downloader = client.files().download(item.path)

                    // Custom output stream that tracks progress
                    val outputStream = localFile.outputStream()
                    val buffer = ByteArray(8192)
                    var bytesDownloaded = 0L

                    val inputStream = downloader.inputStream

                    try {
                        var bytesRead: Int
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            bytesDownloaded += bytesRead

                            // Update progress on main thread
                            withContext(Dispatchers.Main) {
                                val currentTime = System.currentTimeMillis()
                                val elapsedTime = (currentTime - startTime) / 1000.0 // seconds
                                val speed =
                                    if (elapsedTime > 0) bytesDownloaded / elapsedTime else 0.0
                                val remaining =
                                    if (speed > 0) (item.size - bytesDownloaded) / speed else 0.0

                                downloadProgress = downloadProgress.copy(
                                    currentFileProgress = bytesDownloaded.toFloat() / item.size.toFloat(),
                                    bytesDownloaded = bytesDownloaded,
                                    downloadSpeed = formatSpeed(speed),
                                    estimatedTimeRemaining = formatTime(remaining)
                                )
                            }
                        }
                    } finally {
                        inputStream.close()
                        outputStream.close()
                    }
                }

                downloadMessage = "✅ Downloaded: ${item.name}\nSaved to: ${localFile.absolutePath}"
                println("DEBUG: Downloaded ${item.name} to ${localFile.absolutePath}")

            } catch (e: Exception) {
                downloadMessage = "❌ Failed to download ${item.name}: ${e.message}"
                println("DEBUG: Download error: ${e.message}")
                e.printStackTrace()
            } finally {
                isDownloading = false
                isLoadingFiles = false
            }
        }
    }

    // Function to download all ZIP files in current folder
    fun downloadFolderZips(client: com.dropbox.core.v2.DbxClientV2) {
        val zipFiles = dropboxItems.filterIsInstance<DropboxItem.ZipFile>()
        if (zipFiles.isEmpty()) {
            downloadMessage = "No ZIP files found in this folder"
            return
        }

        coroutineScope.launch {
            try {
                isDownloading = true
                isLoadingFiles = true
                downloadMessage = null
                val folderName =
                    if (currentPath.isEmpty()) "Root" else currentPath.substringAfterLast("/")

                val totalBytes = zipFiles.sumOf { it.size }
                downloadProgress = DownloadProgress(
                    fileName = "Preparing download...",
                    currentFileIndex = 0,
                    totalFiles = zipFiles.size,
                    totalBytes = totalBytes
                )

                // Use app-specific external files directory (reliable access)
                val appDownloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: context.filesDir // Fallback to internal storage

                val satenDroidDir = File(appDownloadsDir, "SatenDroid")

                // Create base directory with detailed error checking
                if (!satenDroidDir.exists()) {
                    val created = satenDroidDir.mkdirs()
                    println("DEBUG: Base directory creation result: $created")
                    println("DEBUG: Base directory path: ${satenDroidDir.absolutePath}")

                    if (!created && !satenDroidDir.exists()) {
                        throw Exception("Failed to create base directory: ${satenDroidDir.absolutePath}")
                    }
                }

                // Create folder-specific directory
                val folderDir = File(satenDroidDir, folderName)
                if (!folderDir.exists()) {
                    val created = folderDir.mkdirs()
                    println("DEBUG: Folder directory creation result: $created")
                    println("DEBUG: Folder directory path: ${folderDir.absolutePath}")

                    if (!created && !folderDir.exists()) {
                        throw Exception("Failed to create folder directory: ${folderDir.absolutePath}")
                    }
                }

                // Check if directories are writable
                if (!folderDir.canWrite()) {
                    throw Exception("Cannot write to folder directory: ${folderDir.absolutePath}")
                }

                var successCount = 0
                var failCount = 0
                var totalBytesDownloaded = 0L
                val startTime = System.currentTimeMillis()

                for ((index, zipFile) in zipFiles.withIndex()) {
                    try {
                        downloadProgress = downloadProgress.copy(
                            fileName = zipFile.name,
                            currentFileIndex = index,
                            currentFileProgress = 0f
                        )

                        val localFile = File(folderDir, zipFile.name)
                        println("DEBUG: Downloading ${zipFile.name} to: ${localFile.absolutePath}")

                        withContext(Dispatchers.IO) {
                            val downloader = client.files().download(zipFile.path)

                            // Custom output stream that tracks progress
                            val outputStream = localFile.outputStream()
                            val buffer = ByteArray(8192)
                            var fileBytesDownloaded = 0L

                            val inputStream = downloader.inputStream

                            try {
                                var bytesRead: Int
                                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                    outputStream.write(buffer, 0, bytesRead)
                                    fileBytesDownloaded += bytesRead
                                    totalBytesDownloaded += bytesRead

                                    // Update progress on main thread
                                    withContext(Dispatchers.Main) {
                                        val currentTime = System.currentTimeMillis()
                                        val elapsedTime =
                                            (currentTime - startTime) / 1000.0 // seconds
                                        val speed =
                                            if (elapsedTime > 0) totalBytesDownloaded / elapsedTime else 0.0
                                        val remaining =
                                            if (speed > 0) (totalBytes - totalBytesDownloaded) / speed else 0.0

                                        downloadProgress = downloadProgress.copy(
                                            currentFileProgress = fileBytesDownloaded.toFloat() / zipFile.size.toFloat(),
                                            bytesDownloaded = totalBytesDownloaded,
                                            downloadSpeed = formatSpeed(speed),
                                            estimatedTimeRemaining = formatTime(remaining)
                                        )
                                    }
                                }
                            } finally {
                                inputStream.close()
                                outputStream.close()
                            }
                        }

                        // Verify file was created
                        if (localFile.exists() && localFile.length() > 0) {
                            successCount++
                            println("DEBUG: Successfully downloaded ${zipFile.name} (${localFile.length()} bytes)")
                        } else {
                            failCount++
                            println("DEBUG: File was not created or is empty: ${localFile.absolutePath}")
                        }

                    } catch (e: Exception) {
                        failCount++
                        println("DEBUG: Failed to download ${zipFile.name}: ${e.message}")
                        e.printStackTrace()
                    }
                }

                downloadMessage = "✅ Folder download complete!\n" +
                        "Folder: $folderName\n" +
                        "Success: $successCount files\n" +
                        "Failed: $failCount files\n" +
                        "Total size: ${formatFileSize(totalBytesDownloaded)}\n" +
                        "Saved to: ${folderDir.absolutePath}"

            } catch (e: Exception) {
                downloadMessage = "❌ Folder download failed: ${e.message}"
                println("DEBUG: Folder download error: ${e.message}")
                e.printStackTrace()
            } finally {
                isDownloading = false
                isLoadingFiles = false
            }
        }
    }

    // Function to load folder contents
    fun loadFolder(path: String, client: com.dropbox.core.v2.DbxClientV2) {
        coroutineScope.launch {
            isLoadingFiles = true
            errorMessage = null

            try {
                println("DEBUG: Loading folder: '$path'")
                val result = withContext(Dispatchers.IO) {
                    client.files().listFolder(path)
                }

                val allEntries = result.entries
                println("DEBUG: Found ${allEntries.size} total entries in '$path'")

                // Convert entries to DropboxItems
                dropboxItems = allEntries.mapNotNull { entry ->
                    when (entry) {
                        is com.dropbox.core.v2.files.FolderMetadata -> {
                            println("DEBUG: Found folder: ${entry.name}")
                            DropboxItem.Folder(
                                name = entry.name,
                                path = entry.pathLower
                            )
                        }

                        is com.dropbox.core.v2.files.FileMetadata -> {
                            val ext = entry.name.substringAfterLast('.', "").lowercase()
                            if (ext == "zip") {
                                println("DEBUG: Found ZIP file: ${entry.name}")
                                DropboxItem.ZipFile(
                                    name = entry.name,
                                    path = entry.pathLower,
                                    size = entry.size
                                )
                            } else null
                        }

                        else -> null
                    }
                }

                currentPath = path
                println("DEBUG: Found ${dropboxItems.size} relevant entries in '$path'")
            } catch (e: Exception) {
                errorMessage = "Failed to load folder: ${e.message}"
                println("DEBUG: Error loading folder '$path': ${e::class.simpleName}: ${e.message}")
                e.printStackTrace()
            } finally {
                isLoadingFiles = false
            }
        }
    }

    // Monitor auth state changes
    LaunchedEffect(authState) {
        println("DEBUG: DropboxScreen - AuthState changed: $authState")
        when (val currentState = authState) {
            is DropboxAuthState.Authenticated -> {
                println("DEBUG: Authenticated in DropboxScreen")
                if (dropboxAuthManager.isConfigured()) {
                    println("DEBUG: Loading Dropbox files with real API...")
                    val client = currentState.client
                    println("DEBUG: Dropbox client: $client")
                    loadFolder("", client)
                } else {
                    println("DEBUG: Dropbox not configured properly")
                    isLoadingFiles = false
                    dropboxItems = emptyList()
                }
            }

            DropboxAuthState.NotAuthenticated -> {
                println("DEBUG: Not authenticated")
                dropboxItems = emptyList()
                isLoadingFiles = false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.9f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(16.dp)
                .clickable { /* Prevent closing */ },
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Dropbox Files",
                        style = MaterialTheme.typography.headlineSmall
                    )

                    Row {
                        // Back to Local Files button
                        TextButton(onClick = onBackToLocal) {
                            Text("📁 Local Files")
                        }

                        TextButton(onClick = onDismiss) {
                            Text("✕ Close")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                when (authState) {
                    DropboxAuthState.NotAuthenticated -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            if (dropboxAuthManager.isConfigured()) {
                                Text(
                                    text = "Connect to Dropbox",
                                    style = MaterialTheme.typography.titleLarge,
                                    textAlign = TextAlign.Center
                                )

                                Spacer(modifier = Modifier.height(16.dp))

                                Text(
                                    text = "Sign in to access your ZIP files from Dropbox",
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )

                                Spacer(modifier = Modifier.height(24.dp))

                                Button(
                                    onClick = {
                                        println("DEBUG: Connect Dropbox button clicked")
                                        dropboxAuthManager.startAuthentication()
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth(0.6f)
                                        .height(48.dp)
                                ) {
                                    Text("Connect Dropbox")
                                }
                            } else {
                                Text(
                                    text = "Dropbox Not Configured",
                                    style = MaterialTheme.typography.titleLarge,
                                    textAlign = TextAlign.Center
                                )

                                Spacer(modifier = Modifier.height(16.dp))

                                Text(
                                    text = "Please set DROPBOX_APP_KEY in local.properties to enable Dropbox integration",
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }

                    is DropboxAuthState.Authenticated -> {
                        Column(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Text(
                                text = "✅ Connected to Dropbox",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )

                            // Current path indicator and navigation buttons
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (currentPath.isNotEmpty()) {
                                    Button(
                                        onClick = {
                                            val parentPath = if (currentPath.contains("/")) {
                                                currentPath.substringBeforeLast("/")
                                            } else {
                                                ""
                                            }
                                            val authenticatedState =
                                                authState as DropboxAuthState.Authenticated
                                            loadFolder(parentPath, authenticatedState.client)
                                        },
                                        modifier = Modifier.padding(end = 8.dp)
                                    ) {
                                        Text("← Back")
                                    }
                                }

                                Text(
                                    text = if (currentPath.isEmpty()) "📁 Root Folder" else "📁 $currentPath",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                    modifier = Modifier.weight(1f)
                                )

                                // Download All ZIPs in current folder button
                                val zipCount =
                                    dropboxItems.filterIsInstance<DropboxItem.ZipFile>().size
                                if (zipCount > 0) {
                                    OutlinedButton(
                                        onClick = {
                                            val authenticatedState =
                                                authState as DropboxAuthState.Authenticated
                                            downloadFolderZips(authenticatedState.client)
                                        },
                                        enabled = !isLoadingFiles && !isDownloading,
                                        modifier = Modifier.padding(start = 8.dp)
                                    ) {
                                        Text("⬇️ All ($zipCount)")
                                    }
                                }
                            }

                            // Download progress display
                            if (isDownloading) {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 16.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer
                                    )
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "Downloading",
                                                style = MaterialTheme.typography.titleMedium,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                            )

                                            Text(
                                                text = downloadProgress.progressText,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))

                                        // File name
                                        Text(
                                            text = downloadProgress.fileName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            maxLines = 1
                                        )

                                        Spacer(modifier = Modifier.height(8.dp))

                                        // Progress bar
                                        LinearProgressIndicator(
                                            progress = { downloadProgress.overallProgress },
                                            modifier = Modifier.fillMaxWidth(),
                                            color = MaterialTheme.colorScheme.primary,
                                            trackColor = MaterialTheme.colorScheme.primary.copy(
                                                alpha = 0.3f
                                            )
                                        )

                                        Spacer(modifier = Modifier.height(8.dp))

                                        // Speed and time info
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = if (downloadProgress.downloadSpeed.isNotEmpty())
                                                    "Speed: ${downloadProgress.downloadSpeed}"
                                                else "Calculating speed...",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(
                                                    alpha = 0.8f
                                                )
                                            )

                                            Text(
                                                text = if (downloadProgress.estimatedTimeRemaining.isNotEmpty())
                                                    "ETA: ${downloadProgress.estimatedTimeRemaining}"
                                                else "Calculating...",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(
                                                    alpha = 0.8f
                                                )
                                            )
                                        }

                                        // Size info
                                        if (downloadProgress.totalBytes > 0) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = "${formatFileSize(downloadProgress.bytesDownloaded)} / ${
                                                    formatFileSize(
                                                        downloadProgress.totalBytes
                                                    )
                                                }",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(
                                                    alpha = 0.8f
                                                )
                                            )
                                        }
                                    }
                                }
                            }

                            // Download message display (completion/error messages)
                            if (downloadMessage != null) {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 16.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (downloadMessage!!.startsWith("✅"))
                                            MaterialTheme.colorScheme.primaryContainer
                                        else
                                            MaterialTheme.colorScheme.errorContainer
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Text(
                                            text = downloadMessage!!,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (downloadMessage!!.startsWith("✅"))
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            else
                                                MaterialTheme.colorScheme.onErrorContainer,
                                            modifier = Modifier.weight(1f)
                                        )

                                        TextButton(
                                            onClick = { downloadMessage = null },
                                            modifier = Modifier.padding(start = 8.dp)
                                        ) {
                                            Text("✕")
                                        }
                                    }
                                }
                            }

                            when {
                                isLoadingFiles -> {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxWidth(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            CircularProgressIndicator()
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text("Loading files...")
                                        }
                                    }
                                }

                                errorMessage != null -> {
                                    Card(
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.errorContainer
                                        )
                                    ) {
                                        Text(
                                            text = errorMessage!!,
                                            modifier = Modifier.padding(16.dp),
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(16.dp))

                                    Button(onClick = {
                                        val authenticatedState =
                                            authState as DropboxAuthState.Authenticated
                                        loadFolder(currentPath, authenticatedState.client)
                                    }) {
                                        Text("Retry")
                                    }
                                }

                                dropboxItems.isEmpty() -> {
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                                        )
                                    ) {
                                        Text(
                                            text = if (currentPath.isEmpty())
                                                "No ZIP files or folders found in your Dropbox root folder"
                                            else
                                                "No ZIP files or folders found in this folder",
                                            modifier = Modifier.padding(16.dp),
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }

                                else -> {
                                    LazyColumn(
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        items(dropboxItems) { item ->
                                            Card(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 4.dp)
                                                    .clickable {
                                                        when (item) {
                                                            is DropboxItem.Folder -> {
                                                                // Navigate into folder
                                                                val authenticatedState =
                                                                    authState as DropboxAuthState.Authenticated
                                                                loadFolder(
                                                                    item.path,
                                                                    authenticatedState.client
                                                                )
                                                            }

                                                            is DropboxItem.ZipFile -> {
                                                                // ZIP file click is handled by download button
                                                            }
                                                        }
                                                    },
                                                colors = CardDefaults.cardColors(
                                                    containerColor = when (item) {
                                                        is DropboxItem.Folder -> MaterialTheme.colorScheme.primaryContainer
                                                        is DropboxItem.ZipFile -> MaterialTheme.colorScheme.surfaceVariant
                                                    }
                                                )
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(12.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    // Icon
                                                    Text(
                                                        text = when (item) {
                                                            is DropboxItem.Folder -> "📁"
                                                            is DropboxItem.ZipFile -> "🗜️"
                                                        },
                                                        style = MaterialTheme.typography.headlineSmall,
                                                        modifier = Modifier.padding(end = 12.dp)
                                                    )

                                                    // File/Folder info and actions
                                                    Column(
                                                        modifier = Modifier.weight(1f)
                                                    ) {
                                                        Text(
                                                            text = item.name,
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            maxLines = 2
                                                        )

                                                        when (item) {
                                                            is DropboxItem.Folder -> {
                                                                Text(
                                                                    text = "Folder • Tap to open",
                                                                    style = MaterialTheme.typography.bodySmall,
                                                                    color = MaterialTheme.colorScheme.onSurface.copy(
                                                                        alpha = 0.7f
                                                                    )
                                                                )
                                                            }

                                                            is DropboxItem.ZipFile -> {
                                                                Text(
                                                                    text = "ZIP File • Size: ${
                                                                        formatFileSize(
                                                                            item.size
                                                                        )
                                                                    }",
                                                                    style = MaterialTheme.typography.bodySmall,
                                                                    color = MaterialTheme.colorScheme.onSurface.copy(
                                                                        alpha = 0.7f
                                                                    )
                                                                )
                                                            }
                                                        }
                                                    }

                                                    // Action buttons
                                                    when (item) {
                                                        is DropboxItem.ZipFile -> {
                                                            Button(
                                                                onClick = {
                                                                    val authenticatedState =
                                                                        authState as DropboxAuthState.Authenticated
                                                                    downloadZipFile(
                                                                        item,
                                                                        authenticatedState.client
                                                                    )
                                                                },
                                                                enabled = !isLoadingFiles && !isDownloading
                                                            ) {
                                                                Text("⬇️ Download")
                                                            }
                                                        }

                                                        is DropboxItem.Folder -> {
                                                            // Folder doesn't need action button here as navigation is handled by card click
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                OutlinedButton(
                                    onClick = { dropboxAuthManager.logout() }
                                ) {
                                    Text("Disconnect")
                                }

                                OutlinedButton(
                                    onClick = onBackToLocal
                                ) {
                                    Text("📁 Local Files")
                                }

                                Button(
                                    onClick = {
                                        val authenticatedState =
                                            authState as DropboxAuthState.Authenticated
                                        loadFolder(currentPath, authenticatedState.client)
                                    }
                                ) {
                                    Text("🔄 Refresh")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// Helper functions
private fun formatFileSize(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0

    return when {
        gb >= 1 -> "%.1f GB".format(gb)
        mb >= 1 -> "%.1f MB".format(mb)
        kb >= 1 -> "%.1f KB".format(kb)
        else -> "$bytes bytes"
    }
}

private fun formatDate(timestamp: Long): String {
    val dateFormat = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault())
    return dateFormat.format(java.util.Date(timestamp))
}

private fun formatSpeed(bytesPerSecond: Double): String {
    val kb = bytesPerSecond / 1024.0
    val mb = kb / 1024.0

    return when {
        mb >= 1 -> "%.1f MB/s".format(mb)
        kb >= 1 -> "%.1f KB/s".format(kb)
        else -> "%.0f B/s".format(bytesPerSecond)
    }
}

private fun formatTime(seconds: Double): String {
    if (seconds.isInfinite() || seconds.isNaN() || seconds < 0) {
        return "∞"
    }

    val totalSeconds = seconds.toInt()
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val secs = totalSeconds % 60

    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${secs}s"
        else -> "${secs}s"
    }
}

@Composable
private fun DeleteFileWithPermission(
    item: LocalItem.ZipFile,
    onDeleteResult: (Boolean) -> Unit
) {
    val context = LocalContext.current
    var permissionRequested by remember { mutableStateOf(false) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                onDeleteResult(deleteFileWithPermission(context, item))
            } else {
                onDeleteResult(false)
            }
        }
    LaunchedEffect(Unit) {
        val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
        if (hasPermission) {
            onDeleteResult(deleteFileWithPermission(context, item))
        } else if (!permissionRequested) {
            permissionRequested = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = "package:${context.packageName}".toUri()
                context.startActivity(intent)
                onDeleteResult(false)
            } else {
                launcher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        } else {
            onDeleteResult(false)
        }
    }
}

private fun deleteFileWithPermission(context: Context, item: LocalItem.ZipFile): Boolean {
    try {
        if (item.file.delete()) {
            return true
        }
    } catch (_: SecurityException) {
        // 続行してMediaStore経由で削除を試みる
    } catch (_: Exception) {
        return false
    }
    // MediaStore経由でUriを検索して削除
    return try {
        val filePath = item.file.absolutePath
        val fileName = item.file.name
        val fileSize = item.file.length()
        val contentResolver = context.contentResolver
        val uriExternal = android.provider.MediaStore.Files.getContentUri("external")
        val projection = arrayOf(android.provider.MediaStore.MediaColumns._ID)
        // まずDATA列で検索
        var selection = android.provider.MediaStore.MediaColumns.DATA + "=?"
        var selectionArgs = arrayOf(filePath)
        var cursor = contentResolver.query(uriExternal, projection, selection, selectionArgs, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val id =
                    it.getLong(it.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns._ID))
                val uri = android.content.ContentUris.withAppendedId(uriExternal, id)
                val rowsDeleted = contentResolver.delete(uri, null, null)
                if (rowsDeleted > 0) return true
            }
        }
        // DATA列で見つからない場合、display_nameとサイズで検索
        selection =
            android.provider.MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + android.provider.MediaStore.MediaColumns.SIZE + "=?"
        selectionArgs = arrayOf(fileName, fileSize.toString())
        cursor = contentResolver.query(uriExternal, projection, selection, selectionArgs, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val id =
                    it.getLong(it.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns._ID))
                val uri = android.content.ContentUris.withAppendedId(uriExternal, id)
                val rowsDeleted = contentResolver.delete(uri, null, null)
                if (rowsDeleted > 0) return true
            }
        }
        false
    } catch (_: Exception) {
        false
    }
}
