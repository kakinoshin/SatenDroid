package com.celstech.satendroid.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import com.celstech.satendroid.ui.models.HeaderState
import com.celstech.satendroid.ui.models.ReadingFilterType
import com.celstech.satendroid.ui.models.CloudProviderInfo
import com.celstech.satendroid.ui.models.ConnectionStatus
import com.celstech.satendroid.ui.models.HeaderSettings
import java.io.File

/**
 * スワイプ展開に対応したアダプティブヘッダーコンポーネント
 * Phase 2: 高度なジェスチャー認識、Cloud Provider Management
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun AdaptiveHeader(
    headerState: HeaderState,
    currentPath: String,
    filterType: ReadingFilterType,
    isLoading: Boolean,
    hasFiles: Boolean,
    headerSettings: HeaderSettings = HeaderSettings(),
    cloudProviders: List<CloudProviderInfo> = emptyList(),
    onSettingsClick: () -> Unit,
    onDeviceClick: () -> Unit,
    onDropboxClick: () -> Unit,
    onDownloadQueueClick: () -> Unit,
    onFilterTypeChange: (ReadingFilterType) -> Unit,
    onRefreshClick: () -> Unit,
    onCloudProviderClick: (CloudProviderInfo) -> Unit = { },
    onCloudProviderAuth: (String) -> Unit = { },
    modifier: Modifier = Modifier
) {
    // アニメーション用の状態
    val animationDuration = headerSettings.animationDuration
    val animationSpec = tween<IntOffset>(
        durationMillis = animationDuration,
        easing = if (headerSettings.enableEaseAnimation) FastOutSlowInEasing else LinearEasing
    )
    
    // プレビュー状態のアルファ値
    val previewAlpha by animateFloatAsState(
        targetValue = when (headerState) {
            HeaderState.PREVIEW_EXPAND, HeaderState.PREVIEW_COLLAPSE -> 0.7f
            else -> 1f
        },
        animationSpec = tween(200)
    )
    
    Column(
        modifier = modifier
            .fillMaxWidth()
            .alpha(previewAlpha)
    ) {
        // ヘッダー部分のアニメーション
        AnimatedContent(
            targetState = headerState,
            transitionSpec = {
                slideInVertically(
                    animationSpec = animationSpec,
                    initialOffsetY = { if (targetState == HeaderState.EXPANDED || targetState == HeaderState.PREVIEW_EXPAND) -it else 0 }
                ) togetherWith slideOutVertically(
                    animationSpec = animationSpec,
                    targetOffsetY = { if (targetState == HeaderState.COLLAPSED || targetState == HeaderState.PREVIEW_COLLAPSE) -it else 0 }
                )
            }
        ) { state ->
            when (state) {
                HeaderState.COLLAPSED, HeaderState.PREVIEW_COLLAPSE -> CollapsedHeader(
                    currentPath = currentPath,
                    filterType = filterType,
                    hasFiles = hasFiles,
                    headerSettings = headerSettings,
                    cloudProviders = cloudProviders,
                    onFilterTypeChange = onFilterTypeChange,
                    onRefreshClick = onRefreshClick,
                    onCloudProviderClick = onCloudProviderClick,
                    isLoading = isLoading,
                    isPreview = state == HeaderState.PREVIEW_COLLAPSE
                )
                
                HeaderState.EXPANDED, HeaderState.TRANSITIONING, HeaderState.PREVIEW_EXPAND -> ExpandedHeader(
                    currentPath = currentPath,
                    filterType = filterType,
                    hasFiles = hasFiles,
                    isLoading = isLoading,
                    headerSettings = headerSettings,
                    cloudProviders = cloudProviders,
                    onSettingsClick = onSettingsClick,
                    onDeviceClick = onDeviceClick,
                    onDropboxClick = onDropboxClick,
                    onDownloadQueueClick = onDownloadQueueClick,
                    onFilterTypeChange = onFilterTypeChange,
                    onRefreshClick = onRefreshClick,
                    onCloudProviderClick = onCloudProviderClick,
                    onCloudProviderAuth = onCloudProviderAuth,
                    isPreview = state == HeaderState.PREVIEW_EXPAND
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
    headerSettings: HeaderSettings,
    cloudProviders: List<CloudProviderInfo>,
    onFilterTypeChange: (ReadingFilterType) -> Unit,
    onRefreshClick: () -> Unit,
    onCloudProviderClick: (CloudProviderInfo) -> Unit,
    isLoading: Boolean,
    isPreview: Boolean = false
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (isPreview) MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
                    else MaterialTheme.colorScheme.surface
                )
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
        
        // Phase 2: Cloud Providersのコンパクト表示
        if (headerSettings.showCloudProviders && cloudProviders.isNotEmpty()) {
            CompactCloudProviders(
                providers = cloudProviders,
                onProviderClick = onCloudProviderClick,
                isPreview = isPreview
            )
        }
    }
}

@Composable
private fun ExpandedHeader(
    currentPath: String,
    filterType: ReadingFilterType,
    hasFiles: Boolean,
    isLoading: Boolean,
    headerSettings: HeaderSettings,
    cloudProviders: List<CloudProviderInfo>,
    onSettingsClick: () -> Unit,
    onDeviceClick: () -> Unit,
    onDropboxClick: () -> Unit,
    onDownloadQueueClick: () -> Unit,
    onFilterTypeChange: (ReadingFilterType) -> Unit,
    onRefreshClick: () -> Unit,
    onCloudProviderClick: (CloudProviderInfo) -> Unit,
    onCloudProviderAuth: (String) -> Unit,
    isPreview: Boolean = false
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .let { if (isPreview) it.alpha(0.8f) else it },
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
            
            // Phase 2: Cloud Providersの詳細表示
            if (headerSettings.showCloudProviders && cloudProviders.isNotEmpty()) {
                ExpandedCloudProviders(
                    providers = cloudProviders,
                    onProviderClick = onCloudProviderClick,
                    onProviderAuth = onCloudProviderAuth,
                    isPreview = isPreview
                )
                
                Spacer(modifier = Modifier.height(16.dp))
            }
            
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
private fun CompactCloudProviders(
    providers: List<CloudProviderInfo>,
    onProviderClick: (CloudProviderInfo) -> Unit,
    isPreview: Boolean = false
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(providers) { provider ->
            CompactCloudProviderChip(
                provider = provider,
                onClick = { onProviderClick(provider) },
                isPreview = isPreview
            )
        }
    }
}

@Composable
private fun CompactCloudProviderChip(
    provider: CloudProviderInfo,
    onClick: () -> Unit,
    isPreview: Boolean = false
) {
    val backgroundColor = when (provider.connectionStatus) {
        ConnectionStatus.CONNECTED -> MaterialTheme.colorScheme.primaryContainer
        ConnectionStatus.CONNECTING, ConnectionStatus.SYNCING -> MaterialTheme.colorScheme.secondaryContainer
        ConnectionStatus.ERROR -> MaterialTheme.colorScheme.errorContainer
        ConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.surfaceVariant
    }
    
    val contentColor = when (provider.connectionStatus) {
        ConnectionStatus.CONNECTED -> MaterialTheme.colorScheme.onPrimaryContainer
        ConnectionStatus.CONNECTING, ConnectionStatus.SYNCING -> MaterialTheme.colorScheme.onSecondaryContainer
        ConnectionStatus.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        ConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    
    Surface(
        modifier = Modifier
            .clickable { onClick() }
            .alpha(if (isPreview) 0.7f else 1f),
        shape = RoundedCornerShape(16.dp),
        color = backgroundColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // ステータスアイコン
            val statusIcon = when (provider.connectionStatus) {
                ConnectionStatus.CONNECTED -> "☁️"
                ConnectionStatus.CONNECTING, ConnectionStatus.SYNCING -> "⏳"
                ConnectionStatus.ERROR -> "❌"
                ConnectionStatus.DISCONNECTED -> "☁️"
            }
            
            Text(
                text = statusIcon,
                style = MaterialTheme.typography.labelSmall
            )
            
            // プロバイダー名（短縮）
            Text(
                text = provider.name.take(8),
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ExpandedCloudProviders(
    providers: List<CloudProviderInfo>,
    onProviderClick: (CloudProviderInfo) -> Unit,
    onProviderAuth: (String) -> Unit,
    isPreview: Boolean = false
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Cloud Storage",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium
        )
        
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(providers) { provider ->
                ExpandedCloudProviderCard(
                    provider = provider,
                    onClick = { onProviderClick(provider) },
                    onAuth = { onProviderAuth(provider.id) },
                    isPreview = isPreview
                )
            }
        }
    }
}

@Composable
private fun ExpandedCloudProviderCard(
    provider: CloudProviderInfo,
    onClick: () -> Unit,
    onAuth: () -> Unit,
    isPreview: Boolean = false
) {
    val backgroundColor = when (provider.connectionStatus) {
        ConnectionStatus.CONNECTED -> MaterialTheme.colorScheme.primaryContainer
        ConnectionStatus.CONNECTING, ConnectionStatus.SYNCING -> MaterialTheme.colorScheme.secondaryContainer
        ConnectionStatus.ERROR -> MaterialTheme.colorScheme.errorContainer
        ConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.surfaceVariant
    }
    
    val contentColor = when (provider.connectionStatus) {
        ConnectionStatus.CONNECTED -> MaterialTheme.colorScheme.onPrimaryContainer
        ConnectionStatus.CONNECTING, ConnectionStatus.SYNCING -> MaterialTheme.colorScheme.onSecondaryContainer
        ConnectionStatus.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        ConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    
    val borderColor = if (provider.connectionStatus == ConnectionStatus.ERROR) {
        MaterialTheme.colorScheme.error
    } else Color.Transparent
    
    Card(
        modifier = Modifier
            .width(140.dp)
            .clickable { 
                if (provider.isAuthenticated) onClick() else onAuth() 
            }
            .alpha(if (isPreview) 0.7f else 1f)
            .border(
                width = if (borderColor != Color.Transparent) 1.dp else 0.dp,
                color = borderColor,
                shape = RoundedCornerShape(8.dp)
            ),
        colors = CardDefaults.cardColors(containerColor = backgroundColor)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // ステータスアイコンとプロバイダー名
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val statusIcon = when (provider.connectionStatus) {
                    ConnectionStatus.CONNECTED -> Icons.Default.CloudQueue
                    ConnectionStatus.CONNECTING -> Icons.Default.Sync
                    ConnectionStatus.SYNCING -> Icons.Default.Sync
                    ConnectionStatus.ERROR -> Icons.Default.Error
                    ConnectionStatus.DISCONNECTED -> Icons.Default.CloudOff
                }
                
                Icon(
                    imageVector = statusIcon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = contentColor
                )
                
                Text(
                    text = provider.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            // 接続状態テキスト
            val statusText = when (provider.connectionStatus) {
                ConnectionStatus.CONNECTED -> "接続済み"
                ConnectionStatus.CONNECTING -> "接続中..."
                ConnectionStatus.SYNCING -> "同期中..."
                ConnectionStatus.ERROR -> "エラー"
                ConnectionStatus.DISCONNECTED -> if (provider.isAuthenticated) "オフライン" else "未認証"
            }
            
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.8f),
                maxLines = 1
            )
            
            // 最終同期時間（接続済みの場合のみ）
            if (provider.connectionStatus == ConnectionStatus.CONNECTED && provider.lastSyncTime != null) {
                val syncTimeText = formatSyncTime(provider.lastSyncTime)
                Text(
                    text = syncTimeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.6f),
                    maxLines = 1
                )
            }
            
            // アクションボタン（認証が必要な場合）
            if (!provider.isAuthenticated) {
                Spacer(modifier = Modifier.height(2.dp))
                OutlinedButton(
                    onClick = onAuth,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = contentColor
                    )
                ) {
                    Text(
                        text = "認証",
                        style = MaterialTheme.typography.labelSmall
                    )
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
 * 同期時間を人間が読みやすい形式にフォーマット
 */
private fun formatSyncTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    
    return when {
        diff < 60_000 -> "たった今"
        diff < 3600_000 -> "${diff / 60_000}分前"
        diff < 86400_000 -> "${diff / 3600_000}時間前"
        else -> "${diff / 86400_000}日前"
    }
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
