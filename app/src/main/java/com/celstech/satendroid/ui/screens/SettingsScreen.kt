package com.celstech.satendroid.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.celstech.satendroid.cache.ImageCacheManager
import com.celstech.satendroid.ui.components.InfoDialog

/**
 * 設定画面
 * キャッシュ設定やその他のアプリ設定を管理
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    cacheManager: ImageCacheManager,
    onBackPressed: () -> Unit
) {
    val cacheEnabled by cacheManager.cacheEnabled.collectAsState()
    val clearCacheOnDelete by cacheManager.clearCacheOnDelete.collectAsState()
    
    var showCacheInfoDialog by remember { mutableStateOf(false) }
    var showDeleteInfoDialog by remember { mutableStateOf(false) }
    var showConfirmClearDialog by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定") },
                navigationIcon = {
                    IconButton(onClick = onBackPressed) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "戻る")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
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
            // キャッシュ設定セクション
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "キャッシュ設定",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = { showCacheInfoDialog = true }) {
                            Icon(Icons.Default.Info, contentDescription = "詳細情報")
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // キャッシュ機能の有効/無効
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "キャッシュ機能",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "前回の表示位置を記憶します",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = cacheEnabled,
                            onCheckedChange = { cacheManager.setCacheEnabled(it) }
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // ファイル削除時の動作
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "ファイル削除時にキャッシュを削除",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (clearCacheOnDelete) "削除されたファイルのキャッシュを自動削除" 
                                      else "削除されたファイルのキャッシュを保持",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { showDeleteInfoDialog = true }) {
                                Icon(Icons.Default.Info, contentDescription = "詳細情報")
                            }
                            Switch(
                                checked = clearCacheOnDelete,
                                onCheckedChange = { cacheManager.setClearCacheOnDelete(it) },
                                enabled = cacheEnabled
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // キャッシュクリアボタン
                    OutlinedButton(
                        onClick = { showConfirmClearDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = cacheEnabled
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Spacer(modifier = Modifier.padding(4.dp))
                        Text("全てのキャッシュを削除")
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // アプリ情報セクション
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
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
                        text = "ZIP内画像ビューアー",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = cacheManager.getSettingsSummary(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
    
    // ダイアログ表示
    if (showCacheInfoDialog) {
        InfoDialog(
            title = "キャッシュ機能について",
            message = "キャッシュ機能を有効にすると、ZIPファイルを開いた際に前回表示していた画像の位置から表示を開始します。" +
                    "この機能により、長い画像シリーズを途中まで見た後、後で続きから見ることができます。",
            onDismiss = { showCacheInfoDialog = false }
        )
    }
    
    if (showDeleteInfoDialog) {
        InfoDialog(
            title = "ファイル削除時の動作について",
            message = "この設定を有効にすると、ZIPファイルを削除した際に、そのファイルに関連するキャッシュデータ（表示位置）も自動的に削除されます。" +
                    "無効にした場合、削除されたファイルのキャッシュデータは保持され、同じファイルを再度追加した際に前回の位置から表示されます。",
            onDismiss = { showDeleteInfoDialog = false }
        )
    }
    
    if (showConfirmClearDialog) {
        InfoDialog(
            title = "キャッシュを削除",
            message = "全てのキャッシュデータを削除しますか？この操作は元に戻せません。",
            confirmText = "削除",
            dismissText = "キャンセル",
            onConfirm = {
                cacheManager.clearCache()
                showConfirmClearDialog = false
            },
            onDismiss = { showConfirmClearDialog = false }
        )
    }
}