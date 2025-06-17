# SatenDroid アーキテクチャ ドキュメント

## 概要

SatenDroidは、Clean Architectureの原則に従い、画面・機能ごとにファイルを分割し、ViewとModelを適切に整理したZIP画像ビューアーアプリケーションです。

## アーキテクチャ図

```
┌─────────────────────────────────────────────────────────┐
│                        UI Layer                         │
├─────────────────────────────────────────────────────────┤
│  Screens          │  Components      │  Models          │
│  ├── MainScreen   │  ├── LocalItem   │  ├── ViewState   │
│  ├── FileSelect   │  │   Card        │  ├── LocalFile   │
│  ├── ImageViewer  │  └── DeleteFile  │  │   Models      │
│  └── DropboxScrn  │      Dialog      │  └── Dropbox     │
│                   │                  │      Models      │
├─────────────────────────────────────────────────────────┤
│                    Business Layer                       │
├─────────────────────────────────────────────────────────┤
│  ViewModel        │  Managers        │  Selection       │
│  └── LocalFile    │  └── Navigation  │  └── Selection   │
│      ViewModel    │      Manager     │      Manager     │
├─────────────────────────────────────────────────────────┤
│                     Data Layer                          │
├─────────────────────────────────────────────────────────┤
│  Repository       │  Utils           │  External        │
│  └── LocalFile    │  ├── FormatUtils │  └── Dropbox     │
│      Repository   │  ├── FileUtils   │      Auth        │
│                   │  └── ZipImage    │      Manager     │
│                   │      Handler     │                  │
└─────────────────────────────────────────────────────────┘
```

## 詳細フォルダ構造

```
app/src/main/java/com/celstech/satendroid/
├── ui/                          # UI層
│   ├── screens/                 # 画面UI層
│   │   ├── MainScreen.kt           # メイン画面制御・ナビゲーション管理
│   │   ├── FileSelectionScreen.kt  # ファイル選択画面
│   │   ├── ImageViewerScreen.kt    # 画像表示専用画面
│   │   └── DropboxScreen.kt        # Dropbox連携画面
│   ├── components/              # 再利用可能UIコンポーネント
│   │   ├── LocalItemCard.kt        # ローカルファイル/フォルダカード
│   │   └── DeleteFileDialog.kt     # ファイル削除ダイアログ
│   └── models/                  # UIステート・データモデル
│       ├── ViewState.kt            # 画面遷移状態管理
│       ├── DropboxModels.kt        # Dropbox関連データクラス
│       └── LocalFileModels.kt      # ローカルファイル関連データクラス
├── repository/                  # データアクセス層
│   └── LocalFileRepository.kt      # ローカルファイル操作
├── navigation/                  # ナビゲーション管理
│   └── LocalFileNavigationManager.kt # パス履歴管理
├── selection/                   # 選択モード管理
│   └── SelectionManager.kt         # アイテム選択ロジック
├── utils/                       # ユーティリティ関数
│   ├── FormatUtils.kt              # フォーマット関連ヘルパー
│   ├── FileUtils.kt                # ファイル操作ヘルパー
│   └── ZipImageHandler.kt          # ZIP処理
├── viewmodel/                   # ビジネスロジック・状態管理
│   └── LocalFileViewModel.kt       # UI状態管理ViewModel
└── dropbox/                     # Dropbox連携
    └── DropboxAuthManager.kt       # 認証管理
```

## レイヤー別詳細説明

### UI Layer (ui/)

#### Screens
各画面の責任を明確に分離：

- **MainScreen.kt**: 全体的な画面遷移とステート管理
- **FileSelectionScreen.kt**: ローカルファイルの一覧表示と管理
- **ImageViewerScreen.kt**: 抽出された画像の表示専用
- **DropboxScreen.kt**: Dropboxとの連携・ファイル管理

#### Components
再利用可能なUIコンポーネント：

- **LocalItemCard.kt**: ファイル/フォルダ表示の統一コンポーネント
- **DeleteFileDialog.kt**: 権限管理を含むファイル削除処理

#### Models
UI状態とデータ構造：

- **ViewState.kt**: アプリケーションの主要画面状態
- **LocalFileModels.kt**: ローカルファイル関連のデータクラス
- **DropboxModels.kt**: Dropbox関連のデータクラス

### Business Layer

#### ViewModel
UI状態とビジネスロジックの橋渡し：

- **LocalFileViewModel.kt**: 依存性注入によりスリム化されたViewModel

#### Managers
特定ドメインのロジック管理：

- **LocalFileNavigationManager.kt**: パス履歴とナビゲーションロジック
- **SelectionManager.kt**: アイテム選択モードの管理

### Data Layer

#### Repository
データアクセスの抽象化：

- **LocalFileRepository.kt**: ファイルシステムへのアクセスを担当

#### Utils
共通ユーティリティ関数：

- **FormatUtils.kt**: 表示用フォーマット処理
- **FileUtils.kt**: ファイル操作・権限管理
- **ZipImageHandler.kt**: ZIP抽出処理

## 設計原則とパターン

### 1. 単一責任の原則 (SRP)
各クラス・ファイルは明確な単一の責任を持ちます：

```kotlin
// 例: NavigationManager - ナビゲーションロジックのみ担当
class LocalFileNavigationManager {
    fun navigateToFolder(...): NavigationResult
    fun navigateBack(...): NavigationResult?
    fun formatDisplayPath(...): String
}
```

### 2. 依存性逆転の原則 (DIP)
ViewModelは具象クラスではなく、抽象化された依存関係を使用：

```kotlin
class LocalFileViewModel(
    private val repository: LocalFileRepository,
    private val navigationManager: LocalFileNavigationManager,
    private val selectionManager: SelectionManager
)
```

### 3. Repository パターン
データアクセスロジックをViewModelから分離：

```kotlin
class LocalFileRepository(private val context: Context) {
    suspend fun scanDirectory(path: String): List<LocalItem>
    suspend fun deleteItems(items: Set<LocalItem>): Pair<Int, Int>
}
```

### 4. State Management
StateFlowを使用した一方向データフロー：

```kotlin
// ViewModel
private val _uiState = MutableStateFlow(LocalFileUiState())
val uiState: StateFlow<LocalFileUiState> = _uiState.asStateFlow()

// UI
val uiState by viewModel.uiState.collectAsState()
```

## テスト戦略

### Unit Tests
各Manager・Repositoryクラスに対応するテストを作成：

```
app/src/test/java/com/celstech/satendroid/
├── navigation/
│   └── LocalFileNavigationManagerTest.kt
└── selection/
    └── SelectionManagerTest.kt
```

### テストの特徴
- 依存関係の分離により、単体テストが容易
- Managerクラスは純粋関数として実装されており、テスタブル
- Repositoryパターンにより、モックを使用したテストが可能

## 利点と成果

### 1. 保守性の向上
- 機能別の独立性により、変更影響範囲が限定的
- 単一責任により、バグの原因特定が容易

### 2. 拡張性
- 新しい画面・機能の追加が容易
- Manager・Repositoryパターンにより、機能追加時の既存コードへの影響を最小限に

### 3. テスタビリティ
- 依存性注入により、単体テストが容易
- 純粋関数を多用し、副作用を制限

### 4. 可読性
- ファイル名から責任が明確
- 階層構造により、コードの所在が分かりやすい

### 5. 再利用性
- コンポーネントとユーティリティの分離
- Manager・Repositoryの再利用可能性

## 今後の拡張例

### 新しい画面の追加
```kotlin
// 1. ScreenをUIレイヤーに追加
@Composable
fun NewFeatureScreen() { ... }

// 2. 必要に応じてManagerを追加
class NewFeatureManager { ... }

// 3. ViewStateを拡張
sealed class ViewState {
    object NewFeature : ViewState()
}
```

### 新しいデータソースの追加
```kotlin
// 1. Repositoryインターフェースを定義
interface NewDataRepository {
    suspend fun fetchData(): List<DataItem>
}

// 2. 実装クラスを作成
class NewDataRepositoryImpl : NewDataRepository { ... }

// 3. ViewModelに注入
class NewViewModel(
    private val newDataRepository: NewDataRepository
)
```

この設計により、機能の追加・変更時の影響範囲を最小限に抑え、持続可能な開発を実現しています。
