# SatenDroid 実装状況レポート

## 1. 概要
SatenDroidは、Clean Architectureに基づいた高性能なAndroid用ZIP画像ビューアーです。ローカルストレージおよびDropboxからのファイル閲覧・ダウンロードに対応しています。

## 2. 現在のアーキテクチャ
- **UI Layer**: Jetpack Composeを使用した宣言的UI。
- **Business Layer**: ViewModel, Repository, Managerクラスによる関心の分離。
- **Data Layer**: 
    - `LocalFileRepository`: ローカルファイルシステムへのアクセス。
    - `DropboxAuthManager`: Dropbox API連携。
    - `ZipFileManager` / `DirectZipImageHandler`: ZIPファイルへの直接アクセス。

## 3. 主要機能の実装状況

### 📂 ファイル管理
- [x] ローカルストレージのディレクトリナビゲーション
- [x] ZIPファイルの検出と一覧表示
- [x] 複数ファイルの選択・削除機能
- [x] ストレージ権限（API 28-34）の適切な処理

### 🖼️ 画像表示 (ZIP Direct Access)
- [x] **ZIP直接アクセス方式 (最新)**: 
    - テンポラリ展開なしで画像を表示し、メモリとストレージを節約。
    - `Coil` カスタムFetcher (`ZipImageFetcherNew`) によるシームレスな統合。
    - プリロード機能によるスムーズなページめくり。
    - `ReadingStateManager` による各ファイルの読書位置（ページ数）の自動保存・復旧。
- [x] 従来の展開方式 (互換性のため維持)

### ☁️ クラウド連携 (Dropbox)
- [x] Dropbox OAuth2 認証
- [x] リモートファイルブラウジング
- [x] **WorkManagerによるダウンロード**:
    - バックグラウンドでの安定したダウンロード。
    - 通知欄での進捗表示。
    - アプリ終了後の再開・継続。

### ⚙️ 設定・管理
- [x] キャッシュ管理機能
- [x] ZIPアクセス方式の切り替え (実装済み、`MainScreen`で統合)

## 4. 最近の重要な変更
1.  **WorkManager への移行**: ダウンロード処理を `Service` から `WorkManager` に刷新し、Android OSの最新のバックグラウンド制限に対応。
2.  **ZIP 直接アクセスの導入**: `DirectZipImageHandler` を中心とした、展開不要の画像表示システム。Coilとの統合により、極めて高いパフォーマンスを実現。
3.  **読書状態管理の強化**: ファイルごとの読書位置を永続化し、アプリ再起動後も続きから読める機能を実装。

## 5. 今後の課題・予定
- [ ] UI/UXのさらなるブラッシュアップ (マテリアル3の完全活用)
- [ ] ZIP内のフォルダ構造へのより深い対応
- [ ] 他のクラウドストレージ (Google Drive等) への対応検討
- [ ] パフォーマンスメトリクスの可視化画面の追加

## 6. 技術スタック
- **Language**: Kotlin (1.9.x)
- **UI**: Jetpack Compose
- **Image Loading**: Coil
- **Concurrency**: Coroutines, Flow
- **Background**: WorkManager
- **Networking**: OkHttp, Dropbox SDK
- **Test**: JUnit, Mockito, Robolectric

---
*最終更新日: 2026年4月5日*
