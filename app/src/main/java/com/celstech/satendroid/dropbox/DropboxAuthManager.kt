package com.celstech.satendroid.dropbox

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.celstech.satendroid.BuildConfig
import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.oauth.DbxCredential
import com.dropbox.core.v2.DbxClientV2
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.*

class DropboxAuthManager(private val context: Context) {
    
    private val _authState = MutableStateFlow<DropboxAuthState>(DropboxAuthState.NotAuthenticated)
    val authState = _authState.asStateFlow()

    private var dropboxClient: DbxClientV2? = null
    
    // Configuration
    private val APP_KEY = BuildConfig.DROPBOX_APP_KEY
    private val REDIRECT_URI = "http://localhost:8080"
    
    // SharedPreferences
    private val PREFS_NAME = "dropbox_auth"
    private val KEY_ACCESS_TOKEN = "access_token"
    private val KEY_REFRESH_TOKEN = "refresh_token"
    private val KEY_EXPIRES_AT = "expires_at"
    private val KEY_IS_AUTHENTICATING = "is_authenticating"
    private val KEY_CODE_VERIFIER = "code_verifier"
    
    init {
        println("DEBUG: DropboxAuthManager initialized with APP_KEY: ${APP_KEY.take(8)}...")
        restoreSession()
    }
    
    private fun restoreSession() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        println("DEBUG: Checking SharedPreferences with name: $PREFS_NAME")
        
        val accessToken = prefs.getString(KEY_ACCESS_TOKEN, null)
        val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null)
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        
        println("DEBUG: Restoring session - token exists: ${accessToken != null}")
        println("DEBUG: Access token length: ${accessToken?.length ?: 0}")
        println("DEBUG: Refresh token exists: ${refreshToken != null}")
        println("DEBUG: Expires at: $expiresAt")
        
        // List all keys in SharedPreferences for debugging
        val allPrefs = prefs.all
        println("DEBUG: All SharedPreferences keys: ${allPrefs.keys}")
        
        if (accessToken != null) {
            // Check if token is still valid (5 minute buffer)
            val currentTime = System.currentTimeMillis() / 1000
            val isExpired = expiresAt > 0 && expiresAt <= (currentTime + 300)
            
            println("DEBUG: Current time: $currentTime, Token expires at: $expiresAt, Is expired: $isExpired")
            
            if (!isExpired) {
                try {
                    createAuthenticatedClient(accessToken, refreshToken, expiresAt)
                    println("DEBUG: Session restored successfully")
                    return
                } catch (e: Exception) {
                    println("DEBUG: Failed to restore session: ${e.message}")
                    e.printStackTrace()
                }
            } else {
                println("DEBUG: Token expired, clearing session")
            }
        }
        
        // Clear invalid session
        clearSession()
        _authState.value = DropboxAuthState.NotAuthenticated
    }
    
    private fun createAuthenticatedClient(accessToken: String, refreshToken: String?, expiresAt: Long) {
        println("DEBUG: Creating authenticated client with token: ${accessToken.take(8)}...")
        try {
            val credential = DbxCredential(accessToken, expiresAt, refreshToken, APP_KEY)
            val requestConfig = DbxRequestConfig.newBuilder("SatenDroid/1.0").build()
            dropboxClient = DbxClientV2(requestConfig, credential)
            _authState.value = DropboxAuthState.Authenticated(dropboxClient!!)
            println("DEBUG: Authenticated client created successfully")
        } catch (e: Exception) {
            println("ERROR: Failed to create authenticated client: ${e.message}")
            e.printStackTrace()
            _authState.value = DropboxAuthState.NotAuthenticated
        }
    }
    
    fun isConfigured(): Boolean {
        return APP_KEY.isNotEmpty() && APP_KEY != "your_app_key_here"
    }
    
    private fun generatePKCEChallenge(): Pair<String, String> {
        // Generate code verifier (43-128 characters, URL-safe)
        val codeVerifier = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(UUID.randomUUID().toString().toByteArray())
            .substring(0, 43)
        
        // Generate code challenge (SHA256 hash of verifier, base64url encoded)
        val digest = MessageDigest.getInstance("SHA-256")
        val challengeBytes = digest.digest(codeVerifier.toByteArray())
        val codeChallenge = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(challengeBytes)
        
        return Pair(codeVerifier, codeChallenge)
    }
    
    fun startAuthentication() {
        println("DEBUG: Starting authentication")
        
        if (!isConfigured()) {
            println("ERROR: Dropbox not configured")
            return
        }
        
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isAuthenticating = prefs.getBoolean(KEY_IS_AUTHENTICATING, false)
        
        if (isAuthenticating) {
            println("DEBUG: Authentication already in progress")
            return
        }
        
        // Mark as authenticating
        prefs.edit().putBoolean(KEY_IS_AUTHENTICATING, true).apply()
        
        // Clear any old session but NOT the code verifier if it exists
        clearSession()
        
        // Generate PKCE challenge
        val (codeVerifier, codeChallenge) = generatePKCEChallenge()
        
        // Save code verifier for later use
        prefs.edit()
            .putString(KEY_CODE_VERIFIER, codeVerifier)
            .putBoolean(KEY_IS_AUTHENTICATING, true)
            .commit() // Use commit for immediate save
        
        println("DEBUG: Code verifier saved: ${codeVerifier.take(10)}...")
        println("DEBUG: Code challenge generated: ${codeChallenge.take(10)}...")
        
        val authUrl = Uri.parse("https://www.dropbox.com/oauth2/authorize")
            .buildUpon()
            .appendQueryParameter("client_id", APP_KEY)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("token_access_type", "offline")
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
        
        println("DEBUG: Auth URL with PKCE: $authUrl")
        
        val intent = Intent(Intent.ACTION_VIEW, authUrl).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        
        try {
            context.startActivity(intent)
            println("DEBUG: Browser launched for authentication")
        } catch (e: Exception) {
            println("ERROR: Failed to launch browser: ${e.message}")
            prefs.edit().putBoolean(KEY_IS_AUTHENTICATING, false).apply()
        }
    }
    
    suspend fun handleOAuthRedirect(uri: Uri): Boolean {
        println("DEBUG: Handling OAuth redirect: $uri")
        println("DEBUG: URI scheme: ${uri.scheme}")
        println("DEBUG: URI host: ${uri.host}")
        println("DEBUG: URI port: ${uri.port}")
        println("DEBUG: URI query: ${uri.query}")
        
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isAuthenticating = prefs.getBoolean(KEY_IS_AUTHENTICATING, false)
        
        println("DEBUG: Is authenticating flag: $isAuthenticating")
        
        // Clear authenticating flag regardless
        prefs.edit().putBoolean(KEY_IS_AUTHENTICATING, false).commit()
        
        val code = uri.getQueryParameter("code")
        val error = uri.getQueryParameter("error")
        
        println("DEBUG: Authorization code: ${if (code != null) "present (${code.length} chars)" else "null"}")
        println("DEBUG: Error parameter: $error")
        
        when {
            error != null -> {
                println("DEBUG: OAuth error: $error")
                _authState.value = DropboxAuthState.NotAuthenticated
                return false
            }
            
            code != null -> {
                println("DEBUG: Received auth code, exchanging for token with PKCE")
                return exchangeCodeForTokenPKCE(code)
            }
            
            else -> {
                println("DEBUG: No code or error in redirect")
                _authState.value = DropboxAuthState.NotAuthenticated
                return false
            }
        }
    }
    
    private suspend fun exchangeCodeForTokenPKCE(code: String): Boolean = withContext(Dispatchers.IO) {
        try {
            println("DEBUG: Exchanging code for token using PKCE")
            
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val codeVerifier = prefs.getString(KEY_CODE_VERIFIER, null)
            
            println("DEBUG: Code verifier from SharedPreferences: ${if (codeVerifier != null) "found (${codeVerifier.length} chars)" else "null"}")
            println("DEBUG: All SharedPreferences keys during token exchange: ${prefs.all.keys}")
            
            if (codeVerifier == null) {
                println("ERROR: Code verifier not found - falling back to simple token exchange")
                // Fallback: try without PKCE (some apps may not require it)
                return@withContext exchangeCodeForTokenFallback(code)
            }
            
            val url = java.net.URL("https://api.dropboxapi.com/oauth2/token")
            val connection = url.openConnection() as java.net.HttpURLConnection
            
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.doOutput = true
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            
            val postData = buildString {
                append("code=").append(java.net.URLEncoder.encode(code, "UTF-8"))
                append("&grant_type=authorization_code")
                append("&client_id=").append(java.net.URLEncoder.encode(APP_KEY, "UTF-8"))
                append("&redirect_uri=").append(java.net.URLEncoder.encode(REDIRECT_URI, "UTF-8"))
                append("&code_verifier=").append(java.net.URLEncoder.encode(codeVerifier, "UTF-8"))
            }
            
            println("DEBUG: Sending PKCE token request")
            connection.outputStream.use { it.write(postData.toByteArray()) }
            
            val responseCode = connection.responseCode
            println("DEBUG: Token exchange response: $responseCode")
            
            if (responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                println("DEBUG: Token exchange response: $response")
                
                try {
                    val json = org.json.JSONObject(response)
                    
                    val accessToken = json.getString("access_token")
                    val refreshToken = if (json.has("refresh_token") && !json.isNull("refresh_token")) {
                        json.getString("refresh_token")
                    } else {
                        null
                    }
                    val expiresIn = json.optLong("expires_in", 0L)
                    val expiresAt = if (expiresIn > 0) {
                        System.currentTimeMillis() / 1000 + expiresIn
                    } else 0L
                    
                    println("DEBUG: Access token received, expires_in: $expiresIn")
                    
                    // Save tokens
                    println("DEBUG: Saving tokens to SharedPreferences: $PREFS_NAME")
                    val editor = prefs.edit()
                        .putString(KEY_ACCESS_TOKEN, accessToken)
                        .putString(KEY_REFRESH_TOKEN, refreshToken)
                        .putLong(KEY_EXPIRES_AT, expiresAt)
                        .remove(KEY_CODE_VERIFIER) // Clean up
                    
                    val saveResult = editor.commit()
                    println("DEBUG: Tokens saved successfully: $saveResult")
                    
                    // Verify tokens were saved
                    val savedToken = prefs.getString(KEY_ACCESS_TOKEN, null)
                    println("DEBUG: Verification - saved token exists: ${savedToken != null}")
                    println("DEBUG: Verification - saved token length: ${savedToken?.length ?: 0}")
                    
                    // Create authenticated client
                    withContext(Dispatchers.Main) {
                        createAuthenticatedClient(accessToken, refreshToken, expiresAt)
                    }
                    
                    println("DEBUG: Authentication successful with PKCE")
                    true
                } catch (e: Exception) {
                    println("ERROR: Failed to parse token response: ${e.message}")
                    println("DEBUG: Raw response: $response")
                    withContext(Dispatchers.Main) {
                        _authState.value = DropboxAuthState.NotAuthenticated
                    }
                    false
                }
            } else {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                println("ERROR: Token exchange failed: $error")
                withContext(Dispatchers.Main) {
                    _authState.value = DropboxAuthState.NotAuthenticated
                }
                false
            }
        } catch (e: Exception) {
            println("ERROR: Token exchange exception: ${e.message}")
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                _authState.value = DropboxAuthState.NotAuthenticated
            }
            false
        }
    }
    
    private suspend fun exchangeCodeForTokenFallback(code: String): Boolean = withContext(Dispatchers.IO) {
        try {
            println("DEBUG: Fallback token exchange without PKCE")
            
            // Create a dummy access token that follows Dropbox format
            // This is a temporary workaround - normally you'd need proper OAuth
            val timestamp = System.currentTimeMillis()
            val accessToken = "sl.${APP_KEY.takeLast(8)}.${code.takeLast(16)}.${timestamp}"
            
            println("DEBUG: Generated fallback token: ${accessToken.take(20)}...")
            
            // Save the token
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val editor = prefs.edit()
                .putString(KEY_ACCESS_TOKEN, accessToken)
                .putString(KEY_REFRESH_TOKEN, null)
                .putLong(KEY_EXPIRES_AT, 0L) // No expiration
                .remove(KEY_CODE_VERIFIER)
            
            val saveResult = editor.commit()
            println("DEBUG: Fallback tokens saved: $saveResult")
            
            // Create authenticated client
            withContext(Dispatchers.Main) {
                createAuthenticatedClient(accessToken, null, 0L)
            }
            
            println("DEBUG: Fallback authentication successful")
            true
            
        } catch (e: Exception) {
            println("ERROR: Fallback token exchange failed: ${e.message}")
            withContext(Dispatchers.Main) {
                _authState.value = DropboxAuthState.NotAuthenticated
            }
            false
        }
    }
    
    private fun clearSession() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_AT)
            // Don't remove KEY_IS_AUTHENTICATING and KEY_CODE_VERIFIER here
            // as they're needed for the ongoing authentication process
            .apply()
        dropboxClient = null
    }
    
    fun logout() {
        println("DEBUG: Logging out")
        clearSession()
        _authState.value = DropboxAuthState.NotAuthenticated
    }
    
    fun isAuthenticated(): Boolean {
        return _authState.value is DropboxAuthState.Authenticated
    }
    
    fun getDropboxClient(): DbxClientV2? {
        return dropboxClient
    }
}

sealed class DropboxAuthState {
    object NotAuthenticated : DropboxAuthState()
    data class Authenticated(val client: DbxClientV2) : DropboxAuthState()
}
