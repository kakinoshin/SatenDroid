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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.celstech.satendroid.download.manager.DownloadQueueManager
import com.celstech.satendroid.ui.components.DownloadProgressCard
import com.celstech.satendroid.ui.models.DownloadStatus
import com.celstech.satendroid.utils.FormatUtils
import kotlinx.coroutines.launch

/**
 * ダウンロードキュー管理画面
 */
@Composable
fun DownloadQueueScreen(
    downloadQueueManager: DownloadQueueManager,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    
    // ダウンロードの状態を監視
    val queueState by downloadQueueManager.queueState.collectAsState()
    val downloadProgress by downloadQueueManager.downloadProgress.collectAsState()

    Box(
        modifier = modifier
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
                // ヘッダー
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Download Queue",
                        style = MaterialTheme.typography.headlineSmall
                    )

                    TextButton(onClick = onDismiss) {
                        Text("✕ Close")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 全体の進捗表示
                if (queueState.isActive || queueState.totalDownloads > 0) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
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
                                    text = "Overall Progress",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )

                                Text(
                                    text = "${queueState.completedDownloads + queueState.activeDownloads}/${queueState.totalDownloads}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            LinearProgressIndicator(
                                progress = { queueState.overallProgress },
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // 詳細情報
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${(queueState.overallProgress * 100).toInt()}%",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                )

                                if (queueState.overallSpeed > 0) {
                                    Text(
                                        text = FormatUtils.formatSpeed(queueState.overallSpeed),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                    )
                                }
                            }

                            // サイズ情報
                            if (queueState.totalBytes > 0) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "${FormatUtils.formatFileSize(queueState.downloadedBytes)} / ${FormatUtils.formatFileSize(queueState.totalBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                                )
                            }

                            // 統計情報
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Active: ${queueState.activeDownloads}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                )
                                Text(
                                    text = "Queued: ${queueState.queuedDownloads}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                )
                                Text(
                                    text = "Completed: ${queueState.completedDownloads}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                )
                                if (queueState.failedDownloads > 0) {
                                    Text(
                                        text = "Failed: ${queueState.failedDownloads}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 制御ボタン
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (queueState.activeDownloads > 0) {
                            OutlinedButton(
                                onClick = {
                                    coroutineScope.launch {
                                        downloadQueueManager.pauseAll()
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Pause, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Pause All")
                            }
                        }

                        if (queueState.completedDownloads > 0) {
                            OutlinedButton(
                                onClick = {
                                    coroutineScope.launch {
                                        downloadQueueManager.clearCompleted()
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Clear, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Clear Completed")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }

                // ダウンロード一覧
                when {
                    downloadProgress.isEmpty() -> {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Text(
                                text = "No downloads in queue",
                                modifier = Modifier.padding(32.dp),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    else -> {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // ダウンロード中・キューイング中を先に表示
                            val activeItems = downloadProgress.values
                                .filter { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.PAUSED }
                                .sortedWith(compareBy<com.celstech.satendroid.ui.models.DownloadProgressInfo> { 
                                    when (it.status) {
                                        DownloadStatus.DOWNLOADING -> 0
                                        DownloadStatus.PAUSED -> 1
                                        DownloadStatus.QUEUED -> 2
                                        else -> 3
                                    }
                                }.thenBy { it.startTime })

                            // 完了・失敗・キャンセル済みを後に表示
                            val inactiveItems = downloadProgress.values
                                .filter { it.status == DownloadStatus.COMPLETED || it.status == DownloadStatus.FAILED || it.status == DownloadStatus.CANCELLED }
                                .sortedWith(compareBy<com.celstech.satendroid.ui.models.DownloadProgressInfo> { 
                                    when (it.status) {
                                        DownloadStatus.FAILED -> 0
                                        DownloadStatus.CANCELLED -> 1
                                        DownloadStatus.COMPLETED -> 2
                                        else -> 3
                                    }
                                }.thenByDescending { it.completedTime ?: it.startTime })

                            items(activeItems + inactiveItems) { progress ->
                                DownloadProgressCard(
                                    progress = progress,
                                    onCancel = {
                                        coroutineScope.launch {
                                            downloadQueueManager.cancelDownload(progress.downloadId)
                                        }
                                    },
                                    onPause = {
                                        coroutineScope.launch {
                                            downloadQueueManager.pauseDownload(progress.downloadId)
                                        }
                                    },
                                    onResume = {
                                        coroutineScope.launch {
                                            downloadQueueManager.resumeDownload(progress.downloadId)
                                        }
                                    },
                                    onRetry = {
                                        coroutineScope.launch {
                                            downloadQueueManager.retryDownload(progress.downloadId)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // フッターボタン
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(onClick = onDismiss) {
                        Text("Close")
                    }
                }
            }
        }
    }
}
