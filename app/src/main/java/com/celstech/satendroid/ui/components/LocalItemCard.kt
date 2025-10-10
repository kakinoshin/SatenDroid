package com.celstech.satendroid.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celstech.satendroid.ui.models.LocalItem
import com.celstech.satendroid.ui.models.ReadingStatus
import com.celstech.satendroid.ui.models.FileNameUtils
import com.celstech.satendroid.utils.FormatUtils

/**
 * ローカルファイル/フォルダを表示するカードコンポーネント
 */
@Composable
fun LocalItemCard(
    item: LocalItem,
    viewModel: com.celstech.satendroid.viewmodel.LocalFileViewModel,
    isSelected: Boolean = false,
    isSelectionMode: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onDeleteClick: () -> Unit = {}
) {
    // 統一データ管理システムによる読書状態取得（State版）
    val readingProgress by if (item is LocalItem.ZipFile) {
        viewModel.getReadingProgressState(item.file.absolutePath)
    } else {
        remember { mutableStateOf(null) }
    }
    
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
            // Selection checkbox
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() },
                    modifier = Modifier.padding(end = 16.dp)
                )
            }
            
            // Thumbnail or icon
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .padding(end = 16.dp)
            ) {
                when (item) {
                    is LocalItem.Folder -> {
                        Text(
                            text = "📁",
                            style = MaterialTheme.typography.headlineLarge,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    is LocalItem.ZipFile -> {
                        if (item.thumbnail != null) {
                            Image(
                                bitmap = item.thumbnail.asImageBitmap(),
                                contentDescription = "Thumbnail",
                                modifier = Modifier
                                    .size(60.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Text(
                                text = "🗜️",
                                style = MaterialTheme.typography.headlineLarge,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                        
                        // Reading status indicator（読書状態のみSharedPreferencesから取得）
                        readingProgress?.let { progress ->
                            val statusIndicator = when (progress.status) {
                                ReadingStatus.UNREAD -> "⚪" // 未読マーク
                                ReadingStatus.READING -> "🔵" // 読書中マーク
                                ReadingStatus.COMPLETED -> "✅" // 既読マーク
                            }
                            
                            Text(
                                text = statusIndicator,
                                fontSize = 16.sp,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .background(
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .padding(2.dp)
                            )
                        }
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1f)
            ) {
                // File name with truncation
                Text(
                    text = FileNameUtils.truncateFileName(item.name, 35),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                when (item) {
                    is LocalItem.Folder -> {
                        val description = if (item.zipCount > 0) {
                            "${item.zipCount} ZIP file${if (item.zipCount != 1) "s" else ""}"
                        } else {
                            "Contains subfolders"
                        }

                        Text(
                            text = "$description • ${FormatUtils.formatDate(item.lastModified)}",
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
                        // File size and date
                        Text(
                            text = "${FormatUtils.formatFileSize(item.size)} • ${FormatUtils.formatDate(item.lastModified)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )

                        // Reading progress information（統一データ管理システム）
                        readingProgress?.let { progress ->
                            println("DEBUG: Display progress for ${item.name} - Status: ${progress.status}, CurrentIndex: ${progress.currentIndex}, TotalCount: ${item.totalImageCount} (UnifiedData)")
                            
                            val progressText = when (progress.status) {
                                ReadingStatus.UNREAD -> "未読"
                                ReadingStatus.READING -> {
                                    if (item.totalImageCount > 0) {
                                        "読書中 ${progress.currentIndex + 1}/${item.totalImageCount}"
                                    } else {
                                        "読書中 (画像数: ${item.totalImageCount})"
                                    }
                                }
                                ReadingStatus.COMPLETED -> "読了"
                            }
                            
                            Text(
                                text = progressText,
                                style = MaterialTheme.typography.bodySmall,
                                color = when (progress.status) {
                                    ReadingStatus.UNREAD -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                    ReadingStatus.READING -> MaterialTheme.colorScheme.primary
                                    ReadingStatus.COMPLETED -> MaterialTheme.colorScheme.tertiary
                                },
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // Action buttons
            if (!isSelectionMode) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
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
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
