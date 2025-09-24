package com.celstech.satendroid.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import com.celstech.satendroid.ui.models.HeaderState
import com.celstech.satendroid.ui.models.ReadingFilterType
import java.io.File

/**
 * スワイプ展開に対応したアダプティブヘッダーコンポーネント
 * Phase 1: 基本的なスワイプ機能
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun AdaptiveHeader(
    headerState: HeaderState,
    currentPath: String,
    filterType: ReadingFilterType,
    isLoading: Boolean,
    hasFiles: Boolean,
    onSettingsClick: () -> Unit,
    onDeviceClick: () -> Unit,
    onDropboxClick: () -> Unit,
    onDownloadQueueClick: () -> Unit,
    onFilterTypeChange: (ReadingFilterType) -> Unit,
    onRefreshClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // アニメーション用の状態
    val animationSpec = tween<IntOffset>(durationMillis = 300, easing = FastOutSlowInEasing)
    
    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        // ヘッダー部分のアニメーション
        AnimatedContent(
            targetState = headerState,
            transitionSpec = {
                slideInVertically(
                    animationSpec = animationSpec,
                    initialOffsetY = { if (targetState == HeaderState.EXPANDED) -it else 0 }
                ) togetherWith slideOutVertically(
                    animationSpec = animationSpec,
                    targetOffsetY = { if (targetState == HeaderState.COLLAPSED) -it else 0 }
                )
            }
        ) { state ->
            when (state) {
                HeaderState.COLLAPSED -> CollapsedHeader(
                    currentPath = currentPath,
                    filterType = filterType,
                    hasFiles = hasFiles,
                    onFilterTypeChange = onFilterTypeChange,
                    onRefreshClick = onRefreshClick,
                    isLoading = isLoading
                )
                
                HeaderState.EXPANDED, HeaderState.TRANSITIONING -> ExpandedHeader(
                    currentPath = currentPath,
                    filterType = filterType,
                    hasFiles = hasFiles,
                    isLoading = isLoading,
                    onSettingsClick = onSettingsClick,
                    onDeviceClick = onDeviceClick,
                    onDropboxClick = onDropboxClick,
                    onDownloadQueueClick = onDownloadQueueClick,
                    onFilterTypeChange = onFilterTypeChange,
                    onRefreshClick = onRefreshClick
                )
            }
        }
    }
}

@Composable
private fun CollapsedHeader(
    currentPath: String,
    filterType: ReadingFilterType,
    hasFiles: Boolean,
    onFilterTypeChange: (ReadingFilterType) -> Unit,
    onRefreshClick: () -> Unit,
    isLoading: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // パス表示（フォルダ名のみ）
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "📁",
                style = MaterialTheme.typography.titleMedium
            )
            
            Spacer(modifier = Modifier.width(8.dp))
            
            Text(
                text = getDisplayFolderName(currentPath),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        
        // コンパクトなアクションボタン
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // フィルターボタン（ファイルがある場合のみ）
            if (hasFiles) {
                FilterIconButton(
                    filterType = filterType,
                    onFilterTypeChange = onFilterTypeChange
                )
            }
            
            // リフレッシュボタン
            IconButton(
                onClick = onRefreshClick,
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "更新",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun ExpandedHeader(
    currentPath: String,
    filterType: ReadingFilterType,
    hasFiles: Boolean,
    isLoading: Boolean,
    onSettingsClick: () -> Unit,
    onDeviceClick: () -> Unit,
    onDropboxClick: () -> Unit,
    onDownloadQueueClick: () -> Unit,
    onFilterTypeChange: (ReadingFilterType) -> Unit,
    onRefreshClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // タイトル部分
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "SatenDroid",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "ZIP Image Viewer",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
                
                IconButton(onClick = onSettingsClick) {
                    Text(
                        text = "⚙️",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // アクションボタン群
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onDeviceClick,
                        modifier = Modifier.weight(1f),
                        enabled = !isLoading
                    ) {
                        Text("📱 Device")
                    }
                    
                    OutlinedButton(
                        onClick = onDropboxClick,
                        modifier = Modifier.weight(1f),
                        enabled = !isLoading
                    ) {
                        Text("☁️ Dropbox")
                    }
                }
                
                OutlinedButton(
                    onClick = onDownloadQueueClick,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading
                ) {
                    Text("📥 Download Queue")
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // パス情報とコントロール
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // フルパス表示
                Text(
                    text = "📁 ${getDisplayFolderName(currentPath)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // フィルターボタン
                    if (hasFiles) {
                        FilterIconButton(
                            filterType = filterType,
                            onFilterTypeChange = onFilterTypeChange
                        )
                    }
                    
                    // リフレッシュボタン
                    IconButton(
                        onClick = onRefreshClick,
                        enabled = !isLoading
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "更新"
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterIconButton(
    filterType: ReadingFilterType,
    onFilterTypeChange: (ReadingFilterType) -> Unit
) {
    var showFilterMenu by remember { mutableStateOf(false) }
    
    Box {
        IconButton(
            onClick = { showFilterMenu = true }
        ) {
            Icon(
                imageVector = Icons.Default.FilterList,
                contentDescription = "フィルター",
                tint = if (filterType != ReadingFilterType.ALL) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
        }
        
        DropdownMenu(
            expanded = showFilterMenu,
            onDismissRequest = { showFilterMenu = false }
        ) {
            FilterMenuItem(
                text = "すべて",
                isSelected = filterType == ReadingFilterType.ALL,
                onClick = {
                    onFilterTypeChange(ReadingFilterType.ALL)
                    showFilterMenu = false
                }
            )
            FilterMenuItem(
                text = "既読を非表示",
                isSelected = filterType == ReadingFilterType.HIDE_COMPLETED,
                onClick = {
                    onFilterTypeChange(ReadingFilterType.HIDE_COMPLETED)
                    showFilterMenu = false
                }
            )
            FilterMenuItem(
                text = "未読のみ",
                isSelected = filterType == ReadingFilterType.UNREAD,
                onClick = {
                    onFilterTypeChange(ReadingFilterType.UNREAD)
                    showFilterMenu = false
                }
            )
            FilterMenuItem(
                text = "読書中のみ",
                isSelected = filterType == ReadingFilterType.READING,
                onClick = {
                    onFilterTypeChange(ReadingFilterType.READING)
                    showFilterMenu = false
                }
            )
            FilterMenuItem(
                text = "既読のみ",
                isSelected = filterType == ReadingFilterType.COMPLETED,
                onClick = {
                    onFilterTypeChange(ReadingFilterType.COMPLETED)
                    showFilterMenu = false
                }
            )
        }
    }
}

@Composable
private fun FilterMenuItem(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = {
            Text(
                text = text,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
        },
        onClick = onClick
    )
}

/**
 * パスからフォルダ名のみを取得するユーティリティ関数
 */
private fun getDisplayFolderName(path: String): String {
    return when {
        path.isEmpty() -> "Local Files"
        else -> {
            val file = File(path)
            file.name.ifEmpty { "Root" }
        }
    }
}
