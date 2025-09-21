package com.celstech.satendroid.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.celstech.satendroid.ui.models.CloudType
import com.celstech.satendroid.ui.models.DownloadProgressInfo
import com.celstech.satendroid.ui.models.DownloadStatus
import com.celstech.satendroid.utils.FormatUtils

/**
 * 個別ダウンロード進捗表示用カード
 */
@Composable
fun DownloadProgressCard(
    progress: DownloadProgressInfo,
    onCancel: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (progress.status) {
                DownloadStatus.DOWNLOADING -> MaterialTheme.colorScheme.primaryContainer
                DownloadStatus.COMPLETED -> MaterialTheme.colorScheme.secondaryContainer
                DownloadStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
                DownloadStatus.CANCELLED -> MaterialTheme.colorScheme.surfaceVariant
                DownloadStatus.PAUSED -> MaterialTheme.colorScheme.tertiaryContainer
                DownloadStatus.QUEUED -> MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // ファイル名とステータス
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = progress.fileName,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = getCloudTypeIcon(CloudType.DROPBOX), // 現在はDropboxのみ
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = getStatusText(progress),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
                
                // 制御ボタン
                Row {
                    when (progress.status) {
                        DownloadStatus.DOWNLOADING -> {
                            if (progress.canPause) {
                                IconButton(onClick = onPause) {
                                    Icon(
                                        Icons.Default.Pause,
                                        contentDescription = "Pause",
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            IconButton(onClick = onCancel) {
                                Icon(
                                    Icons.Default.Cancel,
                                    contentDescription = "Cancel",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        
                        DownloadStatus.QUEUED -> {
                            IconButton(onClick = onCancel) {
                                Icon(
                                    Icons.Default.Cancel,
                                    contentDescription = "Cancel",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        
                        DownloadStatus.PAUSED -> {
                            IconButton(onClick = onResume) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = "Resume",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            IconButton(onClick = onCancel) {
                                Icon(
                                    Icons.Default.Cancel,
                                    contentDescription = "Cancel",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        
                        DownloadStatus.FAILED -> {
                            IconButton(onClick = onRetry) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "Retry",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        
                        DownloadStatus.COMPLETED,
                        DownloadStatus.CANCELLED -> {
                            // 完了・キャンセル済みは操作不要
                        }
                    }
                }
            }
            
            // 進捗バー（アクティブな状態のみ）
            if (progress.status == DownloadStatus.DOWNLOADING || progress.status == DownloadStatus.PAUSED) {
                Spacer(modifier = Modifier.height(8.dp))
                
                LinearProgressIndicator(
                    progress = { progress.progressPercentage },
                    modifier = Modifier.fillMaxWidth(),
                    color = when (progress.status) {
                        DownloadStatus.DOWNLOADING -> MaterialTheme.colorScheme.primary
                        DownloadStatus.PAUSED -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.primary
                    }
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // 詳細情報
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${(progress.progressPercentage * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                    
                    if (progress.status == DownloadStatus.DOWNLOADING) {
                        Text(
                            text = if (progress.downloadSpeed > 0) {
                                FormatUtils.formatSpeed(progress.downloadSpeed)
                            } else "Calculating...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                        )
                    }
                }
                
                // サイズ情報
                if (progress.totalBytes > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${FormatUtils.formatFileSize(progress.bytesDownloaded)} / ${FormatUtils.formatFileSize(progress.totalBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                
                // 残り時間（ダウンロード中のみ）
                if (progress.status == DownloadStatus.DOWNLOADING && progress.estimatedTimeRemaining > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "ETA: ${FormatUtils.formatTime(progress.estimatedTimeRemaining.toDouble())}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            
            // エラーメッセージ
            if (progress.status == DownloadStatus.FAILED && progress.errorMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = progress.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * クラウドタイプのアイコンを取得
 */
private fun getCloudTypeIcon(cloudType: CloudType): String {
    return when (cloudType) {
        CloudType.DROPBOX -> "☁️"
        CloudType.GOOGLE_DRIVE -> "📁"
        CloudType.ONEDRIVE -> "📂"
        CloudType.LOCAL -> "💾"
    }
}

/**
 * ステータステキストを取得
 */
private fun getStatusText(progress: DownloadProgressInfo): String {
    return when (progress.status) {
        DownloadStatus.QUEUED -> "Queued • ${FormatUtils.formatFileSize(progress.totalBytes)}"
        DownloadStatus.DOWNLOADING -> "Downloading • ${FormatUtils.formatFileSize(progress.totalBytes)}"
        DownloadStatus.PAUSED -> "Paused • ${FormatUtils.formatFileSize(progress.totalBytes)}"
        DownloadStatus.COMPLETED -> "Completed • ${FormatUtils.formatFileSize(progress.totalBytes)}"
        DownloadStatus.FAILED -> "Failed • ${FormatUtils.formatFileSize(progress.totalBytes)}"
        DownloadStatus.CANCELLED -> "Cancelled • ${FormatUtils.formatFileSize(progress.totalBytes)}"
    }
}
