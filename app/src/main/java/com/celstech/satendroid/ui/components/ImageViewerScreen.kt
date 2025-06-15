package com.celstech.satendroid.ui.components

import android.net.Uri
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

    // State to hold extracted image files
    var imageFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var showFileSelector by remember { mutableStateOf(false) }
    var showTopBar by remember { mutableStateOf(false) }
    var showDropboxScreen by remember { mutableStateOf(false) }

    // Zip image handler
    val zipImageHandler = remember { ZipImageHandler(context) }

    // Dropbox authentication manager
    val dropboxAuthManager = LocalDropboxAuthManager.current

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
            showFileSelector = false
            
            // Clear previous extracted files if any
            if (imageFiles.isNotEmpty()) {
                zipImageHandler.clearExtractedFiles(imageFiles)
            }

            // Extract images from ZIP
            coroutineScope.launch {
                try {
                    imageFiles = zipImageHandler.extractImagesFromZip(uri)
                } catch (e: Exception) {
                    // Handle error silently for now
                } finally {
                    isLoading = false
                }
            }
        }
    }

    // Pager state
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        when {
            // Loading state
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Extracting images...",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // Image viewer
            imageFiles.isNotEmpty() -> {
                // Full-screen image pager
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable {
                            // Toggle top bar visibility on image tap
                            showTopBar = !showTopBar
                        },
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

                // Top clickable area for file selection and info toggle
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .clickable {
                            if (showTopBar) {
                                // If info is showing, open file selector
                                showFileSelector = true
                            } else {
                                // If info is hidden, show info bar
                                showTopBar = true
                            }
                        }
                        .align(Alignment.TopCenter)
                )

                // Top bar with image info (manual toggle only)
                if (showTopBar) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Color.Black.copy(alpha = 0.8f)
                            )
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
                                text = "Tap here again to select new file",
                                color = Color.White.copy(alpha = 0.6f),
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            // Welcome/Initial state
            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "ZIP Image Viewer",
                        style = MaterialTheme.typography.headlineLarge,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Text(
                        text = "Select a ZIP file containing images to get started",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center
                    )
                    
                    Spacer(modifier = Modifier.height(32.dp))
                    
                    Button(
                        onClick = { showFileSelector = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(
                            text = "Select ZIP File",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = Color.White.copy(alpha = 0.1f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                text = "Instructions:",
                                style = MaterialTheme.typography.titleSmall,
                                color = Color.White,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            Text(
                                text = "• Swipe left/right to browse images\n" +
                                        "• Tap image to show/hide controls\n" +
                                        "• Tap top area to show info & access file selection\n" +
                                        "• Supported: JPG, PNG, GIF, BMP, WebP",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }
        }

        // File selector overlay
        if (showFileSelector) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.8f))
                    .clickable { showFileSelector = false },
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { /* Prevent closing when clicking on card */ },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Select Source",
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Text(
                            text = "Choose how you want to select your ZIP file",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        
                        Spacer(modifier = Modifier.height(24.dp))
                        
                        // Local file button
                        Button(
                            onClick = {
                                if (storagePermissionState.status.isGranted) {
                                    zipPickerLauncher.launch("application/zip")
                                } else {
                                    storagePermissionState.launchPermissionRequest()
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            Text("📁 Local Files")
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Dropbox button
                        OutlinedButton(
                            onClick = {
                                println("DEBUG: Dropbox Files button clicked")
                                showFileSelector = false
                                showDropboxScreen = true
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            Text("☁️ Dropbox Files")
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        TextButton(
                            onClick = { showFileSelector = false },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }

        // Dropbox screen overlay
        if (showDropboxScreen) {
            DropboxScreen(
                dropboxAuthManager = dropboxAuthManager,
                onFileSelected = { files ->
                    println("DEBUG: Files selected: ${files.size}")
                    imageFiles = files
                    showDropboxScreen = false
                },
                onDismiss = { 
                    println("DEBUG: Dropbox screen dismissed")
                    showDropboxScreen = false 
                }
            )
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
    onFileSelected: (List<File>) -> Unit,
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
                    
                    TextButton(onClick = onDismiss) {
                        Text("✕ Close")
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
                            
                            // Current path indicator and back button
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
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
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
                                                                // Handle ZIP file selection - could be implemented later
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
                                                    
                                                    // File/Folder info
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
                                                    
                                                    // Download button for ZIP files
                                                    if (item is DropboxItem.ZipFile) {
                                                        Button(
                                                            onClick = {
                                                                println("DEBUG: Download ZIP ${item.name}")
                                                                coroutineScope.launch {
                                                                    try {
                                                                        val authenticatedState = authState as DropboxAuthState.Authenticated
                                                                        val client = authenticatedState.client
                                                                        val localFile = File(context.cacheDir, item.name)
                                                                        
                                                                        // Show loading state
                                                                        isLoadingFiles = true
                                                                        
                                                                        withContext(Dispatchers.IO) {
                                                                            client.files().download(item.path).download(localFile.outputStream())
                                                                        }
                                                                        
                                                                        // Extract images from ZIP
                                                                        val zipHandler = ZipImageHandler(context)
                                                                        val extractedFiles = zipHandler.extractImagesFromZip(Uri.fromFile(localFile))
                                                                        onFileSelected(extractedFiles)
                                                                    } catch (e: Exception) {
                                                                        errorMessage = "Failed to download: ${e.message}"
                                                                        println("DEBUG: Download error: ${e.message}")
                                                                        e.printStackTrace()
                                                                    } finally {
                                                                        isLoadingFiles = false
                                                                    }
                                                                }
                                                            },
                                                            enabled = !isLoadingFiles
                                                        ) {
                                                            Text("Download")
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
                                
                                Button(
                                    onClick = {
                                        val authenticatedState = authState as DropboxAuthState.Authenticated
                                        loadFolder(currentPath, authenticatedState.client)
                                    }
                                ) {
                                    Text("Refresh")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// Helper function to format file size
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
