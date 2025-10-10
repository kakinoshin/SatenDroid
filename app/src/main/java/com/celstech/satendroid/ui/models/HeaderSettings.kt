package com.celstech.satendroid.ui.models

/**
 * ヘッダー動作設定のデータクラス
 * Phase 2: 設定可能な動作カスタマイズ
 */
data class HeaderSettings(
    // 自動折りたたみ設定
    val autoCollapseEnabled: Boolean = true,
    val autoCollapseDelay: Long = 5000L, // ミリ秒 (3-10秒、または無効化)
    
    // スワイプ設定
    val swipeThreshold: Float = 100f, // dp単位
    val enableVelocityDetection: Boolean = true,
    val enablePreviewMode: Boolean = true,
    val enableTapToToggle: Boolean = true,
    
    // 初期表示設定
    val defaultHeaderState: HeaderState = HeaderState.COLLAPSED,
    val rememberLastState: Boolean = true,
    
    // アニメーション設定
    val animationDuration: Int = 300, // ミリ秒
    val enableEaseAnimation: Boolean = true,
    
    // Cloud Provider設定
    val showCloudProviders: Boolean = true,
    val enableProviderStatusIndicator: Boolean = true,
    val autoRefreshProviderStatus: Boolean = true,
    val providerStatusRefreshInterval: Long = 30000L // 30秒
) {
    companion object {
        // プリセット設定
        fun fastResponse() = HeaderSettings(
            autoCollapseDelay = 3000L,
            swipeThreshold = 80f,
            animationDuration = 200
        )
        
        fun conservativeBattery() = HeaderSettings(
            autoCollapseEnabled = true,
            autoCollapseDelay = 8000L,
            enableVelocityDetection = false,
            enablePreviewMode = false,
            autoRefreshProviderStatus = false
        )
        
        fun accessibilityFriendly() = HeaderSettings(
            swipeThreshold = 120f,
            enableTapToToggle = true,
            animationDuration = 400,
            autoCollapseDelay = 10000L
        )
        
        fun powerUser() = HeaderSettings(
            autoCollapseDelay = 2000L,
            swipeThreshold = 60f,
            enableVelocityDetection = true,
            enablePreviewMode = true,
            animationDuration = 150
        )
    }
    
    /**
     * 設定値の妥当性チェック
     */
    fun validate(): HeaderSettings {
        return copy(
            autoCollapseDelay = autoCollapseDelay.coerceIn(0L, 30000L),
            swipeThreshold = swipeThreshold.coerceIn(50f, 200f),
            animationDuration = animationDuration.coerceIn(100, 1000),
            providerStatusRefreshInterval = providerStatusRefreshInterval.coerceIn(5000L, 300000L)
        )
    }
}

/**
 * Cloud Provider情報
 */
data class CloudProviderInfo(
    val id: String,
    val name: String,
    val iconRes: Int? = null,
    val isAuthenticated: Boolean = false,
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val lastSyncTime: Long? = null,
    val errorMessage: String? = null
)

/**
 * Cloud Provider接続状態
 */
enum class ConnectionStatus {
    CONNECTED,
    CONNECTING,
    DISCONNECTED,
    ERROR,
    SYNCING
}

/**
 * ヘッダー表示モード
 */
enum class HeaderDisplayMode {
    COMPACT,    // 最小限の表示
    STANDARD,   // 標準表示
    EXTENDED    // 拡張表示（Cloud Provider含む）
}
