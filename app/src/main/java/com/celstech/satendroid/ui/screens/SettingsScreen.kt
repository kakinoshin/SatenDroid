package com.celstech.satendroid.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.celstech.satendroid.utils.SimpleReadingDataManager
import com.celstech.satendroid.ui.components.InfoDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    readingDataManager: SimpleReadingDataManager,
    directZipHandler: com.celstech.satendroid.utils.DirectZipImageHandler? = null,
    onBackPressed: () -> Unit
) {
    val reverseSwipeDirection by readingDataManager.reverseSwipeDirection.collectAsState()
    
    var showConfirmClearDialog by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定") },
                navigationIcon = {
                    IconButton(onClick = onBackPressed) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
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
                            onCheckedChange = { readingDataManager.setReverseSwipeDirection(it) }
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // データ管理
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "データ管理",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    OutlinedButton(
                        onClick = { showConfirmClearDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("全ての読書データを削除")
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
                            onClick = { directZipHandler.clearMemoryCacheAsync() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("メモリキャッシュをクリア")
                        }
                    }
                }
            }
        }
    }
    
    if (showConfirmClearDialog) {
        InfoDialog(
            title = "読書データを削除",
            message = "全ての読書進捗データを削除しますか？この操作は元に戻せません。",
            confirmText = "削除",
            dismissText = "キャンセル",
            onConfirm = {
                readingDataManager.clearAllData()
                showConfirmClearDialog = false
            },
            onDismiss = { showConfirmClearDialog = false }
        )
    }
}
