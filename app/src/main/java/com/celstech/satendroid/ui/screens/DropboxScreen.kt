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
import com.celstech.satendroid.download.manager.DownloadServiceManager
import com.celstech.satendroid.dropbox.DropboxAuthManager
import com.celstech.satendroid.dropbox.DropboxAuthState
import com.celstech.satendroid.ui.models.CloudType
import com.celstech.satendroid.ui.models.DropboxItem
import com.celstech.satendroid.ui.models.DownloadRequest
import com.celstech.satendroid.utils.FormatUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Dropbox画面 - DownloadServiceManager統合版
 */
@Composable
fun DropboxScreen(
    dropboxAuthManager: DropboxAuthManager,
    currentLocalPath: String = "",
    onBackToLocal: () -> Unit,
    onDismiss: () -> Unit,
    onOpenDownloadQueue: () -> Unit = {}
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

    // DropboxDownloadServiceManagerの状態を監視
    val queueStateFlow = DownloadServiceManager.getQueueState(context)
    val queueState by queueStateFlow.collectAsState()
    val downloadQueue by DownloadServiceManager.getDownloadQueue(context).collectAsState(initial = com.celstech.satendroid.ui.models.DownloadQueue())

    // Function to add single file to download queue
    fun addToDownloadQueue(item: DropboxItem.ZipFile) {
        coroutineScope.launch {
            try {
                // ダウンロード先ディレクトリの決定
                val downloadDir = if (currentLocalPath.isNotEmpty()) {
                    File(currentLocalPath)
                } else {
                    val appDownloadsDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                        ?: context.filesDir
                    File(appDownloadsDir, "SatenDroid")
                }

                // ダウンロード要求を作成
                val downloadRequest = DownloadRequest(
                    id = DownloadRequest.generateId(),
                    cloudType = CloudType.DROPBOX,
                    fileName = item.name,
                    remotePath = item.path,
                    localPath = downloadDir.absolutePath,
                    fileSize = item.size
                )

                // DownloadServiceManager経由でキューに追加
                DownloadServiceManager.enqueueDownload(context, downloadRequest)

                val downloadLocationText = if (currentLocalPath.isNotEmpty()) {
                    "current folder"
                } else {
                    "SatenDroid folder"
                }

                downloadMessage = "✅ Added to download queue: ${item.name}\nWill be saved to $downloadLocationText"
                println("DEBUG: Added ${item.name} to download queue")

            } catch (e: Exception) {
                downloadMessage = "❌ Failed to add ${item.name} to queue: ${e.message}"
                println("DEBUG: Failed to add to queue: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    // Function to add all ZIP files in current folder to download queue
    fun addFolderToDownloadQueue() {
        val zipFiles = dropboxItems.filterIsInstance<DropboxItem.ZipFile>()
        if (zipFiles.isEmpty()) {
            downloadMessage = "No ZIP files found in this folder"
            return
        }

        coroutineScope.launch {
            try {
                val folderName = if (currentPath.isEmpty()) "Root" else currentPath.substringAfterLast("/")

                // ダウンロード先ディレクトリの決定
                val baseDownloadDir = if (currentLocalPath.isNotEmpty()) {
                    File(currentLocalPath)
                } else {
                    val appDownloadsDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                        ?: context.filesDir
                    File(appDownloadsDir, "SatenDroid")
                }

                // バッチダウンロード用サブフォルダー
                val batchDownloadDir = File(baseDownloadDir, folderName)

                var addedCount = 0
                var failedCount = 0

                for (zipFile in zipFiles) {
                    try {
                        val downloadRequest = DownloadRequest(
                            id = DownloadRequest.generateId(),
                            cloudType = CloudType.DROPBOX,
                            fileName = zipFile.name,
                            remotePath = zipFile.path,
                            localPath = batchDownloadDir.absolutePath,
                            fileSize = zipFile.size
                        )

                        DownloadServiceManager.enqueueDownload(context, downloadRequest)
                        addedCount++
                        println("DEBUG: Added ${zipFile.name} to download queue")

                    } catch (e: Exception) {
                        failedCount++
                        println("DEBUG: Failed to add ${zipFile.name} to queue: ${e.message}")
                        e.printStackTrace()
                    }
                }

                val downloadLocationText = if (currentLocalPath.isNotEmpty()) {
                    "current folder/${folderName}"
                } else {
                    "SatenDroid/${folderName}"
                }

                downloadMessage = "✅ Added $addedCount files to download queue!\n" +
                        "Folder: $folderName\n" +
                        "Successfully added: $addedCount files\n" +
                        "Failed: $failedCount files\n" +
                        "Will be saved to $downloadLocationText"

            } catch (e: Exception) {
                downloadMessage = "❌ Failed to add folder to queue: ${e.message}"
                println("DEBUG: Folder queue addition error: ${e.message}")
                e.printStackTrace()
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
                        // Download Queue button
                        if (queueState.isActive || queueState.totalDownloads > 0) {
                            TextButton(onClick = onOpenDownloadQueue) {
                                Text("📥 Queue (${downloadQueue.items.size})")
                            }
                        }

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
                                Column(
                                    modifier = Modifier.weight(1f)
                                ) {
                                    // Dropbox path
                                    Text(
                                        text = if (currentPath.isEmpty()) "📁 Dropbox Root" else "☁️ $currentPath",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                    )

                                    // Download destination info
                                    if (currentLocalPath.isNotEmpty()) {
                                        Text(
                                            text = "📥 Downloads to: ${
                                                currentLocalPath.substringAfterLast(
                                                    "/"
                                                )
                                            }/[folder_name]",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    } else {
                                        Text(
                                            text = "📥 Downloads to: SatenDroid/[folder_name]",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    }
                                }

                                // Navigation buttons
                                Row {
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

                                    // Download All ZIPs in current folder button
                                    val zipCount =
                                        dropboxItems.filterIsInstance<DropboxItem.ZipFile>().size
                                    if (zipCount > 0) {
                                        OutlinedButton(
                                            onClick = {
                                                addFolderToDownloadQueue()
                                            },
                                            enabled = !isLoadingFiles
                                        ) {
                                            Text("⬇️ All ($zipCount)")
                                        }
                                    }
                                }
                            }

                            // Active downloads indicator
                            if (queueState.isActive) {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 16.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(
                                                text = "Downloads Active",
                                                style = MaterialTheme.typography.titleMedium,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                            Text(
                                                text = "${queueState.activeDownloads} downloading, ${queueState.queuedDownloads} queued",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                            )
                                            if (queueState.overallSpeed > 0) {
                                                Text(
                                                    text = "Speed: ${FormatUtils.formatSpeed(queueState.overallSpeed)}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                                )
                                            }
                                        }

                                        Button(
                                            onClick = onOpenDownloadQueue
                                        ) {
                                            Text("View Queue")
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
                                                                    addToDownloadQueue(item)
                                                                },
                                                                enabled = !isLoadingFiles
                                                            ) {
                                                                Text("+ Queue")
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

                                if (queueState.totalDownloads > 0) {
                                    OutlinedButton(
                                        onClick = onOpenDownloadQueue
                                    ) {
                                        Text("📥 View Queue")
                                    }
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
