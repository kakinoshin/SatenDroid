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
import com.celstech.satendroid.ui.models.DownloadQueueItem
import com.celstech.satendroid.ui.models.DownloadQueueStatus
import com.celstech.satendroid.utils.FormatUtils

/**
 * ダウンロードキューアイテム用カード（未処理のダウンロード専用）
 */
@Composable
fun DownloadQueueItemCard(
    item: DownloadQueueItem,
    onCancel: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (item.status) {
                DownloadQueueStatus.DOWNLOADING -> MaterialTheme.colorScheme.primaryContainer
                DownloadQueueStatus.PAUSED -> MaterialTheme.colorScheme.secondaryContainer
                DownloadQueueStatus.QUEUED -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // ヘッダー: ファイル名と位置
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.request.fileName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    
                    Text(
                        text = "Queue position: ${item.queuePosition + 1}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }

                // 制御ボタン
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    when (item.status) {
                        DownloadQueueStatus.DOWNLOADING, DownloadQueueStatus.QUEUED -> {
                            if (item.canPause) {
                                IconButton(
                                    onClick = onPause,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Pause,
                                        contentDescription = "Pause",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                        DownloadQueueStatus.PAUSED -> {
                            if (item.canResume) {
                                IconButton(
                                    onClick = onResume,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        contentDescription = "Resume",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }

                    if (item.canCancel) {
                        IconButton(
                            onClick = onCancel,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Cancel,
                                contentDescription = "Cancel",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 進捗バー（ダウンロード中の場合）
            if (item.status == DownloadQueueStatus.DOWNLOADING && item.progress != null) {
                LinearProgressIndicator(
                    progress = { item.progress.progressPercentage },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 進捗詳細
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${(item.progress.progressPercentage * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    if (item.progress.downloadSpeed > 0) {
                        Text(
                            text = FormatUtils.formatSpeed(item.progress.downloadSpeed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // サイズ情報
                if (item.progress.totalBytes > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${FormatUtils.formatFileSize(item.progress.bytesDownloaded)} / ${FormatUtils.formatFileSize(item.progress.totalBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }

                // 残り時間
                if (item.progress.estimatedTimeRemaining > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "ETA: ${FormatUtils.formatDuration(item.progress.estimatedTimeRemaining)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            } else {
                // キューイング中または一時停止中
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = when (item.status) {
                            DownloadQueueStatus.QUEUED -> "Waiting in queue..."
                            DownloadQueueStatus.PAUSED -> "Paused"
                            else -> "Processing..."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )

                    Text(
                        text = FormatUtils.formatFileSize(item.request.fileSize),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }

            // エラーメッセージ（該当する場合）
            if (!item.errorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Error: ${item.errorMessage}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
