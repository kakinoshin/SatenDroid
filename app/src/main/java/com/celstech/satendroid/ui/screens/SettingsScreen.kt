package com.celstech.satendroid.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.celstech.satendroid.utils.UnifiedReadingDataManager
import com.celstech.satendroid.ui.components.InfoDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    unifiedDataManager: UnifiedReadingDataManager,
    directZipHandler: com.celstech.satendroid.utils.DirectZipImageHandler? = null,
    onBackPressed: () -> Unit
) {
    val cacheEnabled by unifiedDataManager.cacheEnabled.collectAsState()
    val reverseSwipeDirection by unifiedDataManager.reverseSwipeDirection.collectAsState()
    
    var showConfirmClearDialog by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定") },
                navigationIcon = {
                    IconButton(onClick = onBackPressed) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "戻る")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // キャッシュ設定
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "キャッシュ設定",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("キャッシュ機能")
                        Switch(
                            checked = cacheEnabled,
                            onCheckedChange = { unifiedDataManager.setCacheEnabled(it) }
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    OutlinedButton(
                        onClick = { showConfirmClearDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = cacheEnabled
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("キャッシュを削除")
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // スワイプ設定
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "操作設定",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("スワイプ方向を逆転")
                        Switch(
                            checked = reverseSwipeDirection,
                            onCheckedChange = { unifiedDataManager.setReverseSwipeDirection(it) }
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // メモリ管理
            if (directZipHandler != null) {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "メモリ管理",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        OutlinedButton(
                            onClick = { directZipHandler.clearMemoryCache() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("メモリキャッシュをクリア")
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // アプリ情報
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "アプリについて",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Text(
                        text = "SatenDroid",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "ZIP内画像ビューアー（高速化版）",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
    
    if (showConfirmClearDialog) {
        InfoDialog(
            title = "キャッシュを削除",
            message = "全てのキャッシュデータを削除しますか？",
            confirmText = "削除",
            dismissText = "キャンセル",
            onConfirm = {
                unifiedDataManager.clearCache()
                showConfirmClearDialog = false
            },
            onDismiss = { showConfirmClearDialog = false }
        )
    }
}
