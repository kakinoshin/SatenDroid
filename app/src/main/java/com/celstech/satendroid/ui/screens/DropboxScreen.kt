package com.celstech.satendroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.celstech.satendroid.dropbox.DropboxAuthManager
import com.celstech.satendroid.dropbox.DropboxAuthState
import com.celstech.satendroid.ui.models.DropboxItem
import com.celstech.satendroid.ui.models.DownloadProgress
import com.celstech.satendroid.utils.FormatUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Dropbox画面 - Dropboxとの連携、ファイル管理を行う
 */
@Composable
fun DropboxScreen(
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
                val appDownloadsDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
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
                                    downloadSpeed = FormatUtils.formatSpeed(speed),
                                    estimatedTimeRemaining = FormatUtils.formatTime(remaining)
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
                val appDownloadsDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
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
                                            downloadSpeed = FormatUtils.formatSpeed(speed),
                                            estimatedTimeRemaining = FormatUtils.formatTime(remaining)
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
                        "Total size: ${FormatUtils.formatFileSize(totalBytesDownloaded)}\n" +
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
                                                text = "${FormatUtils.formatFileSize(downloadProgress.bytesDownloaded)} / ${
                                                    FormatUtils.formatFileSize(
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
                                                                        FormatUtils.formatFileSize(
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
