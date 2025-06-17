package com.celstech.satendroid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.celstech.satendroid.ui.components.DeleteFileWithPermission
import com.celstech.satendroid.ui.components.LocalItemCard
import com.celstech.satendroid.ui.models.LocalItem
import com.celstech.satendroid.viewmodel.LocalFileViewModel
import java.io.File

/**
 * ファイル選択画面 - ローカルファイルの一覧表示と管理
 */
@Composable
fun FileSelectionScreen(
    onFileSelected: (File) -> Unit,
    onOpenFromDevice: () -> Unit,
    onOpenFromDropbox: () -> Unit,
    onOpenSettings: () -> Unit,
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "SatenDroid",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                
                Text(
                    text = "ZIP Image Viewer",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                )
            }
            
            TextButton(
                onClick = onOpenSettings,
                enabled = !isLoading
            ) {
                Text("⚙️ 設定")
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))

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
                    TextButton(onClick = { viewModel.exitSelectionMode() }) {
                        Text("✕ Cancel")
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
