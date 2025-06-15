package com.celstech.satendroid.ui.components

import android.net.Uri
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.celstech.satendroid.LocalDropboxAuthManager
import com.celstech.satendroid.dropbox.DropboxAuthManager
import com.celstech.satendroid.dropbox.DropboxAuthState
import com.celstech.satendroid.utils.ZipImageHandler
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
                zipImageHandler.clearExtractedFiles(imageFiles)
            }

            // Extract images from ZIP
            coroutineScope.launch {
                try {
                    imageFiles = zipImageHandler.extractImagesFromZip(uri)
                    if (imageFiles.isNotEmpty()) {
                        currentView = ViewState.ImageViewer
                    }
                } catch (e: Exception) {
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
            zipImageHandler.clearExtractedFiles(imageFiles)
        }
    }

    when (currentView) {
        is ViewState.LocalFileList -> {
            LocalFileListScreen(
                onFileSelected = { file ->
                    isLoading = true
                    // Clear previous extracted files if any
                    if (imageFiles.isNotEmpty()) {
                        zipImageHandler.clearExtractedFiles(imageFiles)
                    }
                    
                    coroutineScope.launch {
                        try {
                            imageFiles = zipImageHandler.extractImagesFromZip(Uri.fromFile(file))
                            if (imageFiles.isNotEmpty()) {
                                currentView = ViewState.ImageViewer
                            }
                        } catch (e: Exception) {
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
                    zipImageHandler.clearExtractedFiles(imageFiles)
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
    var localZipFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var isRefreshing by remember { mutableStateOf(false) }
    
    // Function to scan for ZIP files in Downloads folder
    fun scanForZipFiles() {
        isRefreshing = true
        
        try {
            val allZipFiles = mutableListOf<File>()
            
            // Scan app-specific external files directory (main location)
            val appDownloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            if (appDownloadsDir != null && appDownloadsDir.exists()) {
                println("DEBUG: Scanning app downloads dir: ${appDownloadsDir.absolutePath}")
                
                // Scan root directory
                appDownloadsDir.listFiles { file ->
                    file.extension.lowercase() == "zip"
                }?.let { allZipFiles.addAll(it) }
                
                // Scan SatenDroid subdirectory
                val satenDroidDir = File(appDownloadsDir, "SatenDroid")
                if (satenDroidDir.exists()) {
                    println("DEBUG: Scanning SatenDroid dir: ${satenDroidDir.absolutePath}")
                    satenDroidDir.walkTopDown().filter { file ->
                        file.extension.lowercase() == "zip"
                    }.forEach { allZipFiles.add(it) }
                }
            }
            
            // Also scan public Downloads directory (for files downloaded by other apps)
            try {
                val publicDownloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (publicDownloadsDir != null && publicDownloadsDir.exists()) {
                    println("DEBUG: Scanning public downloads dir: ${publicDownloadsDir.absolutePath}")
                    
                    // Scan public Downloads root
                    publicDownloadsDir.listFiles { file ->
                        file.extension.lowercase() == "zip"
                    }?.let { allZipFiles.addAll(it) }
                    
                    // Scan public SatenDroid folder
                    val publicSatenDroidDir = File(publicDownloadsDir, "SatenDroid")
                    if (publicSatenDroidDir.exists()) {
                        publicSatenDroidDir.walkTopDown().filter { file ->
                            file.extension.lowercase() == "zip"
                        }.forEach { allZipFiles.add(it) }
                    }
                }
            } catch (e: Exception) {
                println("DEBUG: Cannot access public downloads directory: ${e.message}")
                // Continue without public directory access
            }
            
            // Remove duplicates and sort
            localZipFiles = allZipFiles.distinctBy { it.absolutePath }.sortedByDescending { it.lastModified() }
            println("DEBUG: Found ${localZipFiles.size} ZIP files total")
            
        } catch (e: Exception) {
            println("DEBUG: Error scanning for ZIP files: ${e.message}")
            e.printStackTrace()
        } finally {
            isRefreshing = false
        }
    }
    
    // Scan for files on first load
    LaunchedEffect(Unit) {
        scanForZipFiles()
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
        
        // Local files section
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Local ZIP Files",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            
            TextButton(
                onClick = { scanForZipFiles() },
                enabled = !isRefreshing
            ) {
                Text("🔄 Refresh")
            }
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        when {
            isLoading -> {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
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
            
            isRefreshing -> {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Scanning for ZIP files...")
                    }
                }
            }
            
            localZipFiles.isEmpty() -> {
                Card(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "📁",
                            style = MaterialTheme.typography.displayLarge,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        
                        Text(
                            text = "No ZIP files found",
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Text(
                            text = "Download ZIP files from Dropbox or select from your device to get started",
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
                    items(localZipFiles) { file ->
                        ZipFileCard(
                            file = file,
                            onClick = { onFileSelected(file) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ZipFileCard(
    file: File,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "🗜️",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(end = 16.dp)
            )
            
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2
                )
                
                Text(
                    text = "${formatFileSize(file.length())} • ${formatDate(file.lastModified())}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                
                Text(
                    text = file.parent ?: "Unknown location",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
            
            Text(
                text = "▶",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary
            )
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
    
    // Function to download ZIP file to Downloads folder
    fun downloadZipFile(item: DropboxItem.ZipFile, client: com.dropbox.core.v2.DbxClientV2) {
        coroutineScope.launch {
            try {
                isLoadingFiles = true
                downloadMessage = "Downloading ${item.name}..."
                
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
                
                withContext(Dispatchers.IO) {
                    client.files().download(item.path).download(localFile.outputStream())
                }
                
                downloadMessage = "✅ Downloaded: ${item.name}\nSaved to: ${localFile.absolutePath}"
                println("DEBUG: Downloaded ${item.name} to ${localFile.absolutePath}")
                
            } catch (e: Exception) {
                downloadMessage = "❌ Failed to download ${item.name}: ${e.message}"
                println("DEBUG: Download error: ${e.message}")
                e.printStackTrace()
            } finally {
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
                isLoadingFiles = true
                val folderName = if (currentPath.isEmpty()) "Root" else currentPath.substringAfterLast("/")
                downloadMessage = "Downloading ${zipFiles.size} files from folder: $folderName..."
                
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
                
                for ((index, zipFile) in zipFiles.withIndex()) {
                    try {
                        downloadMessage = "Downloading (${index + 1}/${zipFiles.size}): ${zipFile.name}..."
                        
                        val localFile = File(folderDir, zipFile.name)
                        println("DEBUG: Downloading ${zipFile.name} to: ${localFile.absolutePath}")
                        
                        withContext(Dispatchers.IO) {
                            client.files().download(zipFile.path).download(localFile.outputStream())
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
                        "Saved to: ${folderDir.absolutePath}"
                
            } catch (e: Exception) {
                downloadMessage = "❌ Folder download failed: ${e.message}"
                println("DEBUG: Folder download error: ${e.message}")
                e.printStackTrace()
            } finally {
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
                println("DEBUG: Error loading folder '$path': ${e.javaClass.simpleName}: ${e.message}")
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
                                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
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
                                            val authenticatedState = authState as DropboxAuthState.Authenticated
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
                                val zipCount = dropboxItems.filterIsInstance<DropboxItem.ZipFile>().size
                                if (zipCount > 0) {
                                    OutlinedButton(
                                        onClick = {
                                            val authenticatedState = authState as DropboxAuthState.Authenticated
                                            downloadFolderZips(authenticatedState.client)
                                        },
                                        enabled = !isLoadingFiles,
                                        modifier = Modifier.padding(start = 8.dp)
                                    ) {
                                        Text("⬇️ All ($zipCount)")
                                    }
                                }
                            }
                            
                            // Download message display
                            if (downloadMessage != null) {
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (downloadMessage!!.startsWith("✅")) 
                                            MaterialTheme.colorScheme.primaryContainer 
                                        else 
                                            MaterialTheme.colorScheme.errorContainer
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(16.dp),
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
                                        modifier = Modifier.weight(1f).fillMaxWidth(),
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
                                        val authenticatedState = authState as DropboxAuthState.Authenticated
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
                                                                val authenticatedState = authState as DropboxAuthState.Authenticated
                                                                loadFolder(item.path, authenticatedState.client)
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
                                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                                                )
                                                            }
                                                            is DropboxItem.ZipFile -> {
                                                                Text(
                                                                    text = "ZIP File • Size: ${formatFileSize(item.size)}",
                                                                    style = MaterialTheme.typography.bodySmall,
                                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                                                )
                                                            }
                                                        }
                                                    }
                                                    
                                                    // Action buttons
                                                    when (item) {
                                                        is DropboxItem.ZipFile -> {
                                                            Button(
                                                                onClick = {
                                                                    val authenticatedState = authState as DropboxAuthState.Authenticated
                                                                    downloadZipFile(item, authenticatedState.client)
                                                                },
                                                                enabled = !isLoadingFiles
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
                                        val authenticatedState = authState as DropboxAuthState.Authenticated
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
