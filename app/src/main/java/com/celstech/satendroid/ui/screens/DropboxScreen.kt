package com.celstech.satendroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.celstech.satendroid.download.manager.DownloadServiceManager
import com.celstech.satendroid.dropbox.AuthException
import com.celstech.satendroid.dropbox.DropboxAuthManager
import com.celstech.satendroid.dropbox.DropboxAuthState
import com.celstech.satendroid.dropbox.SafeDropboxClient
import com.celstech.satendroid.ui.models.CloudType
import com.celstech.satendroid.ui.models.DownloadQueueState
import com.celstech.satendroid.ui.models.DropboxItem
import com.celstech.satendroid.ui.models.DownloadRequest
import com.celstech.satendroid.utils.FormatUtils
import com.dropbox.core.v2.files.FileMetadata
import com.dropbox.core.v2.files.FolderMetadata
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

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

    var dropboxItems by remember { mutableStateOf<List<DropboxItem>>(emptyList()) }
    var currentPath by remember { mutableStateOf("") }
    var isLoadingFiles by remember { mutableStateOf(false) }
    var uiErrorMessage by remember { mutableStateOf<String?>(null) }
    var downloadMessage by remember { mutableStateOf<String?>(null) }

    val queueState by DownloadServiceManager.getQueueState(context).collectAsState()

    fun getBaseDownloadDir(): File {
        return if (currentLocalPath.isNotEmpty()) File(currentLocalPath) else File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "SatenDroid")
    }

    suspend fun addToDownloadQueue(item: DropboxItem.ZipFile, customLocalPath: String? = null) {
        val downloadDir = customLocalPath?.let { File(it) } ?: getBaseDownloadDir()
        val request = DownloadRequest(
            id = UUID.randomUUID().toString(),
            cloudType = CloudType.DROPBOX,
            fileName = item.name,
            remotePath = item.path,
            localPath = downloadDir.absolutePath,
            fileSize = item.size
        )
        DownloadServiceManager.enqueueDownload(context, request)
    }

    fun addFolderToDownloadQueue() {
        coroutineScope.launch {
            val zipFiles = dropboxItems.filterIsInstance<DropboxItem.ZipFile>()
            if (zipFiles.isEmpty()) {
                downloadMessage = "No ZIP files to add."
                return@launch
            }

            val folderName = if (currentPath.isEmpty()) "Root" else currentPath.substringAfterLast('/')
            val batchDownloadDir = File(getBaseDownloadDir(), folderName)
            if (!batchDownloadDir.exists()) {
                batchDownloadDir.mkdirs()
            }

            for (file in zipFiles) {
                addToDownloadQueue(file, batchDownloadDir.absolutePath)
            }
            downloadMessage = "✅ Added ${zipFiles.size} files to the queue. They will be saved in the '$folderName' folder."
        }
    }

    fun loadFolder(path: String, client: SafeDropboxClient) {
        coroutineScope.launch {
            isLoadingFiles = true
            uiErrorMessage = null
            try {
                val result = client.listFolder(path)
                dropboxItems = result.entries.mapNotNull { entry ->
                    when (entry) {
                        is FolderMetadata -> DropboxItem.Folder(name = entry.name, path = entry.pathLower)
                        is FileMetadata -> if (entry.name.endsWith(".zip", true)) DropboxItem.ZipFile(name = entry.name, path = entry.pathLower, size = entry.size) else null
                        else -> null
                    }
                }
                currentPath = path
            } catch (e: AuthException) {
                // This is handled by the global state change to ReAuthenticationRequired, UI will update automatically.
            } catch (e: Exception) {
                uiErrorMessage = "Failed to load folder: ${e.message}"
            } finally {
                isLoadingFiles = false
            }
        }
    }

    LaunchedEffect(authState) {
        if (authState is DropboxAuthState.Authenticated) {
            loadFolder("", (authState as DropboxAuthState.Authenticated).client)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f)).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
        Card(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(16.dp).clickable(enabled = false) {}, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                // Header
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Dropbox Files", style = MaterialTheme.typography.headlineSmall)
                    Row {
                        if (queueState.isActive || queueState.totalDownloads > 0) {
                            TextButton(onClick = onOpenDownloadQueue) { Text("📥 Queue") }
                        }
                        TextButton(onClick = onDismiss) { Text("✕ Close") }
                    }
                }
                Spacer(Modifier.height(16.dp))

                // Content area based on AuthState
                when (val state = authState) {
                    is DropboxAuthState.NotConfigured -> StateInfo(title = "Dropbox Not Configured", message = "Please set DROPBOX_APP_KEY in your local.properties.")
                    is DropboxAuthState.NotAuthenticated -> AuthPrompt { dropboxAuthManager.startAuthentication() }
                    is DropboxAuthState.Authenticating -> StateInfo(title = "Authenticating...", message = "Please complete the sign-in process in your browser.", showLoading = true)
                    is DropboxAuthState.TokenExchange -> StateInfo(title = "Finalizing Connection...", message = "Exchanging authorization code for a token.", showLoading = true)
                    is DropboxAuthState.ReAuthenticationRequired -> StateInfo(title = "Session Expired", message = state.message, buttonText = "Reconnect") { dropboxAuthManager.startAuthentication() }
                    is DropboxAuthState.Error -> StateInfo(title = "An Error Occurred", message = state.message, buttonText = "Retry") { dropboxAuthManager.logout() }
                    is DropboxAuthState.Authenticated -> {
                        val client = state.client
                        Column(modifier = Modifier.fillMaxSize()) {
                            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("☁️ ${if (currentPath.isEmpty()) "/" else currentPath}", modifier = Modifier.weight(1f))
                                val zipFileCount = dropboxItems.count { it is DropboxItem.ZipFile }
                                if (zipFileCount > 0) {
                                    OutlinedButton(onClick = { addFolderToDownloadQueue() }) {
                                        Text("⬇️ All ($zipFileCount)")
                                    }
                                }
                                if (currentPath.isNotEmpty()) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Button(onClick = { loadFolder(currentPath.substringBeforeLast('/', ""), client) }) { Text("← Back") }
                                }
                            }

                            if (downloadMessage != null) {
                                FeedbackCard(message = downloadMessage!!, isError = false) { downloadMessage = null }
                            }
                            if (queueState.isActive) {
                                DownloadStatusCard(queueState = queueState, onOpenDownloadQueue = onOpenDownloadQueue)
                            }

                            when {
                                isLoadingFiles -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(); Spacer(Modifier.height(8.dp)); Text("Loading...") } }
                                uiErrorMessage != null -> StateInfo(title = "Error Loading Folder", message = uiErrorMessage!!, buttonText = "Retry") { loadFolder(currentPath, client) }
                                dropboxItems.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("This folder is empty.") }
                                else -> {
                                    LazyColumn(modifier = Modifier.weight(1f)) {
                                        items(dropboxItems) { item ->
                                            DropboxItemRow(item = item, onFolderClick = { loadFolder(item.path, client) }, onFileQueue = { coroutineScope.launch { addToDownloadQueue(it); downloadMessage = "✅ Added to queue: ${it.name}" } })
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height(16.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                OutlinedButton(onClick = { dropboxAuthManager.logout() }) { Text("Disconnect") }
                                Button(onClick = { loadFolder(currentPath, client) }) { Text("🔄 Refresh") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AuthPrompt(onConnect: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Connect to Dropbox", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text("Access your ZIP files from Dropbox.", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onConnect) { Text("Connect Dropbox") }
    }
}

@Composable
private fun StateInfo(title: String, message: String, showLoading: Boolean = false, buttonText: String? = null, onButtonClick: (() -> Unit)? = null) {
    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp))
        if (showLoading) {
            Spacer(Modifier.height(24.dp))
            CircularProgressIndicator()
        }
        if (buttonText != null && onButtonClick != null) {
            Spacer(Modifier.height(24.dp))
            Button(onClick = onButtonClick) { Text(buttonText) }
        }
    }
}

@Composable
private fun DropboxItemRow(item: DropboxItem, onFolderClick: () -> Unit, onFileQueue: (DropboxItem.ZipFile) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { if (item is DropboxItem.Folder) onFolderClick() },
        colors = CardDefaults.cardColors(containerColor = if (item is DropboxItem.Folder) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (item is DropboxItem.Folder) "📁" else "🗜️", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(end = 12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.bodyMedium)
                if (item is DropboxItem.ZipFile) {
                    Text("Size: ${FormatUtils.formatFileSize(item.size)}", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (item is DropboxItem.ZipFile) {
                Button(onClick = { onFileQueue(item) }) { Text("+ Queue") }
            }
        }
    }
}

@Composable
private fun FeedbackCard(message: String, isError: Boolean, onDismiss: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), colors = CardDefaults.cardColors(containerColor = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, modifier = Modifier.weight(1f), color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer)
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Dismiss") }
        }
    }
}

@Composable
private fun DownloadStatusCard(queueState: DownloadQueueState, onOpenDownloadQueue: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Downloads Active", style = MaterialTheme.typography.titleMedium)
                Text("${queueState.activeDownloads} downloading, ${queueState.queuedDownloads} queued", style = MaterialTheme.typography.bodySmall)
                // Unconditionally display the speed, letting FormatUtils handle the zero case.
                Text("Speed: ${FormatUtils.formatSpeed(queueState.overallSpeed)}", style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onOpenDownloadQueue) { Text("View Queue") }
        }
    }
}
