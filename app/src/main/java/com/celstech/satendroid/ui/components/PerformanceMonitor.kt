package com.celstech.satendroid.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celstech.satendroid.utils.DirectZipImageHandler

/**
 * パフォーマンス監視用UI
 * 開発者向けのデバッグ情報を表示
 */
@Composable
fun PerformanceMonitor(
    zipHandler: DirectZipImageHandler,
    modifier: Modifier = Modifier,
    isVisible: Boolean = false
) {
    if (!isVisible) return
    
    val performanceMetrics by zipHandler.performanceMetrics.collectAsState()
    
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "📊 Performance Monitor",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                modifier = Modifier.padding(vertical = 4.dp)
            )
            
            MetricRow(
                label = "キャッシュヒット率",
                value = "${(performanceMetrics.cacheHitRate * 100).toInt()}%",
                isGood = performanceMetrics.cacheHitRate > 0.7f
            )
            
            MetricRow(
                label = "平均読み込み時間",
                value = "${performanceMetrics.averageLoadTime}ms",
                isGood = performanceMetrics.averageLoadTime < 200
            )
            
            MetricRow(
                label = "プリロード済み",
                value = "${performanceMetrics.preloadedImages}枚",
                isGood = performanceMetrics.preloadedImages > 0
            )
            
            MetricRow(
                label = "総リクエスト数",
                value = "${performanceMetrics.totalRequests}",
                isGood = true
            )
            
            // メモリ使用量（概算）
            val estimatedMemoryMB = (performanceMetrics.preloadedImages * 2) // 2MB per image estimate
            MetricRow(
                label = "推定メモリ使用量",
                value = "${estimatedMemoryMB}MB",
                isGood = estimatedMemoryMB < 50
            )
        }
    }
}

@Composable
private fun MetricRow(
    label: String,
    value: String,
    isGood: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            fontSize = 12.sp
        )
        Text(
            text = value,
            color = if (isGood) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * パフォーマンス監視トグルボタン
 */
@Composable
fun PerformanceToggleButton(
    isVisible: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    FloatingActionButton(
        onClick = { onToggle(!isVisible) },
        modifier = modifier.size(48.dp),
        containerColor = if (isVisible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
    ) {
        Text(
            text = "📊",
            fontSize = 20.sp
        )
    }
}

/**
 * 簡易パフォーマンスアラート
 */
@Composable
fun PerformanceAlert(
    zipHandler: DirectZipImageHandler,
    modifier: Modifier = Modifier
) {
    val performanceMetrics by zipHandler.performanceMetrics.collectAsState()
    
    // 警告条件
    val shouldShowAlert = performanceMetrics.totalRequests > 10 && (
        performanceMetrics.cacheHitRate < 0.5f || 
        performanceMetrics.averageLoadTime > 1000
    )
    
    if (shouldShowAlert) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .padding(8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "⚠️",
                    fontSize = 20.sp
                )
                Column {
                    Text(
                        text = "パフォーマンス警告",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = if (performanceMetrics.cacheHitRate < 0.5f) {
                            "キャッシュヒット率が低下しています"
                        } else {
                            "読み込み時間が長くなっています"
                        },
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.9f),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}
