# ZIP直接アクセス移行ガイド

## 現在のMainActivity.ktを更新する手順

### 1. 既存のMainActivity.ktを確認

現在のMainActivity.ktで、以下のように`MainScreen()`を呼び出している部分を見つけてください：

```kotlin
setContent {
    SatenDroidTheme {
        // 現在のコード
        MainScreen()
    }
}
```

### 2. MainActivity.ktを以下のように更新

```kotlin
package com.celstech.satendroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.celstech.satendroid.ui.screens.DirectAccessMainScreen
import com.celstech.satendroid.ui.screens.MainScreen
import com.celstech.satendroid.ui.theme.SatenDroidTheme
import com.celstech.satendroid.utils.ZipAccessModeManager

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // ZIP アクセス方式の管理
        val accessModeManager = ZipAccessModeManager(this)
        
        setContent {
            SatenDroidTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // アクセス方式に応じて画面を切り替え
                    if (accessModeManager.isDirectAccessMode()) {
                        // 新しい直接アクセス方式
                        DirectAccessMainScreen()
                    } else {
                        // 従来の展開方式（互換性のため）
                        MainScreen()
                    }
                }
            }
        }
    }
}
```

### 3. 段階的な移行オプション

#### オプション A: 即座に新方式に切り替え

```kotlin
setContent {
    SatenDroidTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            // 常に新しい直接アクセス方式を使用
            DirectAccessMainScreen()
        }
    }
}
```

#### オプション B: デバッグフラグで切り替え

```kotlin
class MainActivity : ComponentActivity() {
    companion object {
        // デバッグ用フラグ
        private const val USE_DIRECT_ACCESS = true
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            SatenDroidTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (USE_DIRECT_ACCESS) {
                        DirectAccessMainScreen()
                    } else {
                        MainScreen()
                    }
                }
            }
        }
    }
}
```

## 設定画面での切り替え機能の追加

### SettingsScreen.ktに以下を追加

```kotlin
// 新しいComposable関数を追加
@Composable
fun ZipAccessModeSection(
    accessModeManager: ZipAccessModeManager,
    modifier: Modifier = Modifier
) {
    var currentMode by remember { mutableStateOf(accessModeManager.getCurrentMode()) }
    
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "ZIPファイル処理方式",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "直接アクセス方式",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "メモリ効率的（推奨）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
                
                Switch(
                    checked = currentMode == ZipAccessMode.DIRECT_ACCESS,
                    onCheckedChange = { 
                        currentMode = accessModeManager.toggleMode()
                    }
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "現在: ${accessModeManager.getCurrentModeDescription()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            
            if (currentMode == ZipAccessMode.DIRECT_ACCESS) {
                Text(
                    text = "• テンポラリファイル不要\n• 高速な初期ロード\n• 低メモリ使用量",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

// 既存のSettingsScreen Composableに追加
@Composable
fun SettingsScreen(
    cacheManager: ImageCacheManager,
    onBackPressed: () -> Unit
) {
    val context = LocalContext.current
    val accessModeManager = remember { ZipAccessModeManager(context) }
    
    // 既存のコード...
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 既存の設定項目...
        
        // 新しいZIPアクセス方式設定を追加
        ZipAccessModeSection(
            accessModeManager = accessModeManager
        )
        
        // 既存の他の設定項目...
    }
}
```

## テスト手順

### 1. 基本動作確認
1. アプリをビルドして起動
2. 設定画面で「直接アクセス方式」を有効にする
3. ZIPファイルを選択して開く
4. 画像の表示速度とメモリ使用量を確認

### 2. パフォーマンステスト
```kotlin
// デバッグ用のメモリ使用量測定
private fun logMemoryUsage(tag: String) {
    val runtime = Runtime.getRuntime()
    val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
    android.util.Log.d("MemoryUsage", "$tag: ${usedMemory}MB")
}
```

### 3. 大容量ファイルテスト
- 100MB以上のZIPファイルで動作確認
- 1000枚以上の画像を含むZIPファイルで確認
- メモリリークの確認（長時間使用）

## トラブルシューティング

### 問題: 画像が表示されない
**解決策:**
1. Coilの設定を確認
2. ZipImageFetcher.Factoryが正しく登録されているか確認
3. ログでキャッシュ状況を確認

### 問題: メモリ使用量が多い
**解決策:**
1. LruCacheのサイズを確認（デフォルト3枚）
2. 画像の解像度を確認
3. プリロード範囲を狭める

### 問題: 初期ロードが遅い
**解決策:**
1. ZIP内のエントリ数を確認
2. ファイルの圧縮率を確認
3. ストレージの読み込み速度を確認

## パフォーマンス改善の確認方法

### Android Studio Profilerでの確認
1. Memory Profilerでヒープ使用量を監視
2. CPU Profilerで処理時間を測定
3. Network Profilerで通信量を確認（Dropbox使用時）

### 期待される改善値
- **メモリ使用量**: 70-90%削減
- **初期ロード時間**: 80-95%短縮
- **ディスク使用量**: ほぼゼロ

この移行により、大幅なパフォーマンス改善が期待できます。
