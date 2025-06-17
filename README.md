# SatenDroid - ZIP Image Viewer

SatenDroidは、ZIPファイルから画像を抽出して表示するAndroidアプリケーションです。ローカルファイルシステムやDropboxから直接ZIPファイルを開くことができます。

## 🏗️ アーキテクチャ

このプロジェクトは、Clean Architectureの原則に従い、画面・機能ごとにファイルを分割し、ViewとModelを適切に整理しています。

### フォルダ構造

```
app/src/main/java/com/celstech/satendroid/
├── ui/
│   ├── screens/           # 画面UI層
│   │   ├── MainScreen.kt         # メイン画面制御・ナビゲーション管理
│   │   ├── FileSelectionScreen.kt # ファイル選択画面
│   │   ├── ImageViewerScreen.kt   # 画像表示専用画面
│   │   └── DropboxScreen.kt       # Dropbox連携画面
│   ├── components/        # 再利用可能UIコンポーネント
│   │   ├── LocalItemCard.kt       # ローカルファイル/フォルダカード
│   │   └── DeleteFileDialog.kt    # ファイル削除ダイアログ
│   └── models/           # UIステート・データモデル
│       ├── ViewState.kt           # 画面遷移状態管理
│       ├── DropboxModels.kt       # Dropbox関連データクラス
│       └── LocalFileModels.kt     # ローカルファイル関連データクラス
├── repository/           # データアクセス層
│   └── LocalFileRepository.kt    # ローカルファイル操作
├── navigation/           # ナビゲーション管理
│   └── LocalFileNavigationManager.kt # パス履歴管理
├── selection/            # 選択モード管理
│   └── SelectionManager.kt       # アイテム選択ロジック
├── utils/                # ユーティリティ関数
│   ├── FormatUtils.kt            # フォーマット関連ヘルパー
│   ├── FileUtils.kt              # ファイル操作ヘルパー
│   └── ZipImageHandler.kt        # ZIP処理
├── viewmodel/           # ビジネスロジック・状態管理
│   └── LocalFileViewModel.kt     # UI状態管理ViewModel
└── dropbox/             # Dropbox連携
    └── DropboxAuthManager.kt     # 認証管理
```

## 🎯 主要機能

### 1. ファイル選択画面
- ローカルストレージのZIPファイル一覧表示
- フォルダナビゲーション
- 複数選択・削除機能
- デバイスからのファイル選択
- Dropbox連携

### 2. 画像表示画面
- ZIPファイルから抽出された画像の表示
- スワイプによるページ送り
- タップ操作での戻り・UI表示切替

### 3. Dropbox連携
- Dropbox認証
- リモートファイル一覧
- ダウンロード機能（進捗表示付き）

## 🧪 テスト

プロジェクトには各コンポーネントのUnit Testが含まれています：

```
app/src/test/java/com/celstech/satendroid/
├── navigation/
│   └── LocalFileNavigationManagerTest.kt
└── selection/
    └── SelectionManagerTest.kt
```

テストの実行：
```bash
./gradlew test
```

## 🔧 技術スタック

- **UI**: Jetpack Compose
- **Architecture**: MVVM + Repository Pattern
- **Navigation**: Custom Navigation Manager
- **State Management**: StateFlow
- **Async**: Kotlin Coroutines
- **Image Loading**: Coil
- **File Operations**: Java IO + MediaStore
- **Cloud Storage**: Dropbox API

## 📱 設定

### Dropbox連携の設定

1. `local.properties`ファイルにDropboxアプリキーを追加：
```properties
DROPBOX_APP_KEY=your_dropbox_app_key_here
```

2. Dropbox App Consoleでリダイレクトカスタムスキームを設定：
```
db-your_app_key://auth
```

## 🚀 ビルド & 実行

```bash
# クローン
git clone [repository-url]
cd SatenDroid

# ビルド
./gradlew assembleDebug

# エミュレータで実行
./gradlew installDebug
```

## 🎨 設計原則

### 単一責任の原則
各クラス・ファイルは明確な単一の責任を持ちます：
- **Screen**: UI表示とユーザーインタラクション
- **Repository**: データアクセス・ファイル操作
- **Manager**: 特定ドメインのロジック管理
- **ViewModel**: UI状態とビジネスロジックの橋渡し

### 依存性の注入
ViewModelは必要な依存関係を注入して使用し、テスタビリティを向上させています。

### 関心の分離
UI、ビジネスロジック、データアクセスを明確に分離し、保守性を向上させています。

## 🤝 貢献

1. Forkしてください
2. Feature branchを作成してください (`git checkout -b feature/AmazingFeature`)
3. 変更をCommitしてください (`git commit -m 'Add some AmazingFeature'`)
4. Branchにプッシュしてください (`git push origin feature/AmazingFeature`)
5. Pull Requestを開いてください

## 📄 ライセンス

このプロジェクトはMITライセンスの下で配布されています。詳細は[LICENSE](LICENSE)ファイルを参照してください。
