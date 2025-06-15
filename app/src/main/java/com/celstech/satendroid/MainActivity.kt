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
import com.celstech.satendroid.dropbox.DropboxAuthManager
import com.celstech.satendroid.ui.components.ImageViewerScreen
import com.celstech.satendroid.ui.theme.SatEndroidTheme
import kotlinx.coroutines.launch

// Global composition local for DropboxAuthManager
val LocalDropboxAuthManager = staticCompositionLocalOf<DropboxAuthManager> {
    error("No DropboxAuthManager provided")
}

class MainActivity : ComponentActivity() {
    
    private lateinit var dropboxAuthManager: DropboxAuthManager
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize Dropbox auth manager
        dropboxAuthManager = DropboxAuthManager(this)
        
        // Handle OAuth redirect if this activity was opened from a redirect
        handleOAuthRedirect(intent)
        
        setContent {
            SatEndroidTheme {
                CompositionLocalProvider(LocalDropboxAuthManager provides dropboxAuthManager) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        ImageViewerScreen()
                    }
                }
            }
        }
    }
    
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Handle OAuth redirect for existing activity instance
        intent?.let { handleOAuthRedirect(it) }
    }
    
    private fun handleOAuthRedirect(intent: Intent) {
        val uri = intent.data
        println("DEBUG: Received intent with data: $uri")
        
        if (uri != null && uri.scheme == "http" && uri.host == "localhost" && uri.port == 8080) {
            println("DEBUG: Valid Dropbox OAuth redirect detected: $uri")
            // Handle OAuth redirect asynchronously
            lifecycleScope.launch {
                val success = dropboxAuthManager.handleOAuthRedirect(uri)
                println("DEBUG: OAuth redirect handled, success: $success")
            }
        } else {
            println("DEBUG: Intent does not match OAuth redirect pattern")
        }
    }
}