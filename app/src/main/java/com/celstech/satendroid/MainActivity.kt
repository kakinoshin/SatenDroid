package com.celstech.satendroid

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import coil.Coil
import coil.ImageLoader
import com.celstech.satendroid.download.manager.DownloadServiceManager
import com.celstech.satendroid.dropbox.DropboxAuthManager
import com.celstech.satendroid.ui.theme.SatenDroidTheme
import com.celstech.satendroid.utils.DirectZipImageHandler
import com.celstech.satendroid.utils.ZipImageFetcherNew
import kotlinx.coroutines.launch

// Global composition local for DropboxAuthManager
val LocalDropboxAuthManager = staticCompositionLocalOf<DropboxAuthManager> {
    error("No DropboxAuthManager provided")
}

/**
 * メインアクティビティ - サービス管理簡素化版
 * 
 * DownloadServiceManagerによりサービス接続を管理
 * OAuth処理は既存のDropboxAuthManagerを使用
 */
class MainActivity : ComponentActivity() {
    
    private lateinit var dropboxAuthManager: DropboxAuthManager
    private lateinit var directZipHandler: DirectZipImageHandler
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize Dropbox auth manager
        dropboxAuthManager = DropboxAuthManager(this)
        
        // Initialize direct ZIP handler
        directZipHandler = DirectZipImageHandler(this)
        
        // Setup Coil with custom fetcher for ZIP images
        setupCoil()
        
        // Handle OAuth redirect if this activity was opened from a redirect
        handleOAuthRedirect(intent)
        
        // Set up UI content
        setContent {
            SatenDroidTheme {
                CompositionLocalProvider(
                    LocalDropboxAuthManager provides dropboxAuthManager
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        // 新しい直接アクセス方式を使用
                        com.celstech.satendroid.ui.screens.MainScreen()
                    }
                }
            }
        }
    }
    
    private fun setupCoil() {
        val imageLoader = ImageLoader.Builder(this)
            .components {
                // ZIP内画像の直接読み込み用Fetcherを追加
                add(ZipImageFetcherNew.Factory(directZipHandler))
            }
            .build()
        
        // Coilのデフォルトローダーを設定
        Coil.setImageLoader(imageLoader)
        
        println("DEBUG: Coil configured with ZipImageFetcherNew")
    }
    
    /**
     * OAuth処理専用 - DropboxAuthManager用
     * DownloadServiceとは独立して動作
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Handle OAuth redirect for existing activity instance
        intent?.let { handleOAuthRedirect(it) }
    }
    
    /**
     * Dropbox OAuthリダイレクト処理
     * 既存のDropboxAuthManagerに処理を委譲
     */
    private fun handleOAuthRedirect(intent: Intent) {
        try {
            val uri = intent.data
            println("DEBUG: Received intent with data: $uri")
            
            if (uri != null && uri.scheme == "http" && uri.host == "localhost" && uri.port == 8080) {
                println("DEBUG: Valid Dropbox OAuth redirect detected: $uri")
                lifecycleScope.launch {
                    val success = dropboxAuthManager.handleOAuthRedirect(uri)
                    println("DEBUG: OAuth redirect handled, success: $success")
                }
            } else {
                println("DEBUG: Intent does not match OAuth redirect pattern")
            }
        } catch (e: Exception) {
            println("ERROR: OAuth redirect handling failed: ${e.message}")
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        // DownloadServiceManagerによるサービス接続切断
        lifecycleScope.launch {
            DownloadServiceManager.disconnect(this@MainActivity)
        }
        
        // リソースのクリーンアップ
        lifecycleScope.launch {
            try {
                directZipHandler.cleanup()
            } catch (e: Exception) {
                println("DEBUG: Failed to cleanup DirectZipImageHandler: ${e.message}")
            }
        }
    }
}
