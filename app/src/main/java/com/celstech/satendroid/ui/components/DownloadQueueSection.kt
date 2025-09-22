package com.celstech.satendroid.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.celstech.satendroid.ui.models.DownloadQueue

/**
 * アクティブなダウンロードキューセクション（未処理のダウンロード専用）
 */
@Composable
fun ActiveDownloadQueueSection(
    queue: DownloadQueue,
    onCancel: (String) -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onPauseAll: () -> Unit,
    onCancelAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        // ヘッダー
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Active Downloads (${queue.totalCount})",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (queue.totalCount > 0) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (queue.activeCount > 0) {
                        OutlinedButton(
                            onClick = onPauseAll,
                            modifier = Modifier
                        ) {
                            Icon(Icons.Default.Pause, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Pause All")
                        }
                    }

                    OutlinedButton(
                        onClick = onCancelAll,
                        modifier = Modifier
                    ) {
                        Icon(Icons.Default.Clear, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Cancel All")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // キューの内容
        when {
            queue.isEmpty -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        text = "No active downloads",
                        modifier = Modifier.padding(32.dp),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            else -> {
                // キューアイテムの表示（追加順序を保持）
                val sortedItems = queue.items.sortedBy { it.queuePosition }

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(sortedItems, key = { it.downloadId }) { item ->
                        DownloadQueueItemCard(
                            item = item,
                            onCancel = { onCancel(item.downloadId) },
                            onPause = { onPause(item.downloadId) },
                            onResume = { onResume(item.downloadId) }
                        )
                    }
                }
            }
        }
    }
}
