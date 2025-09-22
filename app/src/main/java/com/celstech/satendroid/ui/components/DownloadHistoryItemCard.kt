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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.celstech.satendroid.ui.models.DownloadHistoryItem
import com.celstech.satendroid.ui.models.DownloadHistoryStatus
import com.celstech.satendroid.ui.models.DownloadResult
import com.celstech.satendroid.utils.FormatUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ダウンロード履歴アイテム用カード（処理済みのダウンロード専用）
 */
@Composable
fun DownloadHistoryItemCard(
    item: DownloadHistoryItem,
    onRetry: (() -> Unit)? = null,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (item.status) {
                DownloadHistoryStatus.COMPLETED -> MaterialTheme.colorScheme.surfaceVariant
                DownloadHistoryStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
                DownloadHistoryStatus.CANCELLED -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // ヘッダー: ファイル名とステータスアイコン
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // ステータスアイコン
                    Icon(
                        imageVector = when (item.status) {
                            DownloadHistoryStatus.COMPLETED -> Icons.Default.CheckCircle
                            DownloadHistoryStatus.FAILED -> Icons.Default.Error
                            DownloadHistoryStatus.CANCELLED -> Icons.Default.Stop
                        },
                        contentDescription = null,
                        tint = when (item.status) {
                            DownloadHistoryStatus.COMPLETED -> MaterialTheme.colorScheme.primary
                            DownloadHistoryStatus.FAILED -> MaterialTheme.colorScheme.error
                            DownloadHistoryStatus.CANCELLED -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        },
                        modifier = Modifier.size(20.dp)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Column {
                        Text(
                            text = item.request.fileName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Text(
                            text = when (item.status) {
                                DownloadHistoryStatus.COMPLETED -> "Completed"
                                DownloadHistoryStatus.FAILED -> "Failed"
                                DownloadHistoryStatus.CANCELLED -> "Cancelled"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = when (item.status) {
                                DownloadHistoryStatus.COMPLETED -> MaterialTheme.colorScheme.primary
                                DownloadHistoryStatus.FAILED -> MaterialTheme.colorScheme.error
                                DownloadHistoryStatus.CANCELLED -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            }
                        )
                    }
                }

                // アクションボタン
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // リトライボタン（失敗の場合のみ）
                    if (item.canRetry && onRetry != null) {
                        IconButton(
                            onClick = onRetry,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Retry",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    // 削除ボタン
                    IconButton(
                        onClick = onRemove,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Remove",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 詳細情報
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Size: ${FormatUtils.formatFileSize(item.request.fileSize)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )

                    val dateFormat = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
                    val completedTimeText = if (item.completedTime > 0) {
                        "Completed: ${dateFormat.format(Date(item.completedTime))}"
                    } else {
                        "Processing..."
                    }
                    Text(
                        text = completedTimeText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }

                // 所要時間
                val duration = item.completedTime - item.startTime
                if (duration > 0 && item.completedTime > 0) {
                    Text(
                        text = "Duration: ${FormatUtils.formatDuration(duration / 1000)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }

            // 結果の詳細
            when (val result = item.result) {
                is DownloadResult.Success -> {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Saved to: ${result.localFilePath}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                is DownloadResult.Failure -> {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Error: ${result.error}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                is DownloadResult.Cancelled -> {
                    // キャンセル時は特に詳細表示しない
                }
            }

            // 追加のエラーメッセージ（該当する場合）
            if (!item.errorMessage.isNullOrBlank() && item.result !is DownloadResult.Failure) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Note: ${item.errorMessage}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
