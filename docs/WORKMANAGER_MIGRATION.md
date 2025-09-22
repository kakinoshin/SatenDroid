# WorkManager移行ガイド

## 概要
SatenDroidのダウンロードシステムを従来のService + CoroutineベースからWorkManagerベースに移行しました。

## 主な変更点

### 1. アーキテクチャの変更
- **Before**: `DownloadService` + `DownloadQueueManager`
- **After**: `DownloadWorker` + `WorkManagerDownloadQueueManager`

### 2. 追加された依存関係
```kotlin
// build.gradle.kts (app module)
implementation("androidx.work:work-runtime-ktx:2.9.0")
```

### 3. 新しいコンポーネント

#### DownloadWorker
- WorkManagerベースのダウンロード処理
- フォアグラウンド実行で通知表示
- 進捗更新とエラーハンドリング

#### WorkManagerDownloadQueueManager
- WorkManagerを使ったキュー管理
- 非同期状態監視
- ダウンロードリクエストの永続化

#### WorkManagerDownloadServiceManager
- 既存のAPIとの互換性を保つラッパー
- シングルトンパターンで簡単アクセス

## 使用方法

### 基本的な使用例
```kotlin
// ダウンロードリクエストの作成
val request = DownloadRequest(
    id = DownloadRequest.generateId(),
    cloudType = CloudType.DROPBOX,
    fileName = "example.zip",
    remotePath = "/path/to/file.zip",
    localPath = "/storage/emulated/0/Download",
    fileSize = 1024000L
)

// ダウンロード開始
DownloadServiceManager.enqueueDownload(context, request)

// 進捗監視
val progressFlow = DownloadServiceManager.getDownloadProgress(context)
val queueStateFlow = DownloadServiceManager.getQueueState(context)

// Compose UIでの使用例
@Composable
fun MyDownloadScreen() {
    val downloadProgress by progressFlow.collectAsState()
    val queueState by queueStateFlow.collectAsState()
    
    // UI実装...
}
```

### ダウンロード制御
```kotlin
// ダウンロードキャンセル
DownloadServiceManager.cancelDownload(context, downloadId)

// 失敗したダウンロードの再試行
DownloadServiceManager.retryDownload(context, downloadId)

// 全ダウンロード一時停止
DownloadServiceManager.pauseAll(context)

// 完了したダウンロードをクリア
DownloadServiceManager.clearCompleted(context)
```

## WorkManagerの利点

### 1. バッテリー最適化対応
- Dozeモードでも確実に実行
- システムが自動的にバックグラウンド実行を管理

### 2. 制約条件の設定
```kotlin
val constraints = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.CONNECTED)
    .setRequiresBatteryNotLow(false)
    .build()
```

### 3. 永続性
- アプリがkillされてもタスクが継続
- 端末再起動後も自動復旧

### 4. システム統合
- Android標準の仕組みに従った実装
- OS側の最適化の恩恵を受けられる

## 移行における注意点

### 1. 一時停止・再開機能の制限
- WorkManagerには一時停止機能がない
- 代替として「キャンセル＋再エンキュー」で実装

### 2. リアルタイム進捗更新
- WorkManagerの進捗更新は最短1秒間隔
- UIの更新頻度が従来より低くなる可能性

### 3. 並行ダウンロード数の制御
- WorkManagerの制約設定で管理
- デフォルト：最大2つの並行ダウンロード

## トラブルシューティング

### ダウンロードが開始されない場合
1. ネットワーク接続を確認
2. WorkManagerの制約条件をチェック
3. アプリのバックグラウンド実行権限を確認

### 進捗が更新されない場合
1. WorkManagerの状態監視ロジックを確認
2. `observeWorkStates()` メソッドが正常に動作しているかチェック

### 通知が表示されない場合
1. 通知権限が許可されているか確認
2. 通知チャンネルが正しく作成されているかチェック

## 今後の改善予定

1. より詳細な進捗情報の提供
2. ダウンロード優先度の実装
3. ネットワーク状況に応じた自動再試行
4. バッチダウンロードの最適化

## サポートされるAndroidバージョン
- 最小SDK: 28 (Android 9.0)
- 対象SDK: 34 (Android 14)

WorkManagerはAndroid 6.0以降で利用可能ですが、本アプリの最小SDKに合わせています。
