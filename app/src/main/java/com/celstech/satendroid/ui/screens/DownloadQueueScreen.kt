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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.celstech.satendroid.download.manager.DownloadServiceManager
import com.celstech.satendroid.ui.components.ActiveDownloadQueueSection
import com.celstech.satendroid.ui.components.CompletedDownloadHistorySection

import com.celstech.satendroid.ui.models.DownloadQueue
import com.celstech.satendroid.ui.models.DownloadHistory
import com.celstech.satendroid.utils.FormatUtils
import kotlinx.coroutines.launch

/**
 * ダウンロードキュー管理画面 - 分離設計版
 * 未処理キューと処理済み履歴を明確に分離
 */
@Composable
fun DownloadQueueScreen(
    onDismiss: () -> Unit,
    onOpenSettings: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    // 分離されたデータフローを監視
    val downloadQueue by DownloadServiceManager.getDownloadQueue(context).collectAsState(initial = DownloadQueue())
    val downloadHistory by DownloadServiceManager.getDownloadHistory(context).collectAsState(initial = DownloadHistory())
    val queueState by DownloadServiceManager.getQueueState(context).collectAsState(initial = com.celstech.satendroid.ui.models.DownloadQueueState())

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
                        text = "Download Manager",
                        style = MaterialTheme.typography.headlineSmall
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (onOpenSettings != null) {
                            TextButton(onClick = onOpenSettings) {
                                Text("⚙️ Settings")
                            }
                        }
                        
                        TextButton(onClick = onDismiss) {
                            Text("✕ Close")
                        }
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
                                    text = "${queueState.activeDownloads + queueState.completedDownloads}/${queueState.totalDownloads}",
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
                                if (queueState.pausedDownloads > 0) {
                                    Text(
                                        text = "Paused: ${queueState.pausedDownloads}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                    )
                                }
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
                }

                // スクロール可能なコンテンツエリア
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 1. アクティブなダウンロードキューセクション
                    ActiveDownloadQueueSection(
                        queue = downloadQueue,
                        onCancel = { downloadId ->
                            coroutineScope.launch {
                                DownloadServiceManager.cancelDownload(context, downloadId)
                            }
                        },
                        onPause = { downloadId ->
                            coroutineScope.launch {
                                DownloadServiceManager.pauseDownload(context, downloadId)
                            }
                        },
                        onResume = { downloadId ->
                            coroutineScope.launch {
                                DownloadServiceManager.resumeDownload(context, downloadId)
                            }
                        },
                        onPauseAll = {
                            coroutineScope.launch {
                                DownloadServiceManager.pauseAll(context)
                            }
                        },
                        onCancelAll = {
                            coroutineScope.launch {
                                // 全てのアクティブなダウンロードをキャンセル
                                downloadQueue.items.forEach { item ->
                                    DownloadServiceManager.cancelDownload(context, item.downloadId)
                                }
                            }
                        }
                    )

                    // 2. 完了したダウンロード履歴セクション
                    CompletedDownloadHistorySection(
                        history = downloadHistory,
                        onRetry = { downloadId ->
                            coroutineScope.launch {
                                DownloadServiceManager.retryDownload(context, downloadId)
                            }
                        },
                        onRemove = { downloadId ->
                            coroutineScope.launch {
                                DownloadServiceManager.removeFromHistory(context, downloadId)
                            }
                        },
                        onClearHistory = {
                            coroutineScope.launch {
                                DownloadServiceManager.clearCompleted(context)
                            }
                        },
                        // 履歴が少ない場合は初期展開、多い場合は折りたたみ
                        initialExpanded = downloadHistory.totalCount <= 5
                    )
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
