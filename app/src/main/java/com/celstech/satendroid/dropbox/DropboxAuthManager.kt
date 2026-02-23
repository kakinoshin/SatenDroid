package com.celstech.satendroid.dropbox

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.celstech.satendroid.BuildConfig
import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.http.OkHttp3Requestor
import com.dropbox.core.oauth.DbxCredential
import com.dropbox.core.v2.DbxClientV2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.util.*

class DropboxAuthManager(private val context: Context) {

    private val _authState = MutableStateFlow<DropboxAuthState>(DropboxAuthState.NotAuthenticated)
    val authState = _authState.asStateFlow()

    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    private val APP_KEY = BuildConfig.DROPBOX_APP_KEY
    private val REDIRECT_URI = "http://localhost:8080"

    private val PREFS_NAME = "dropbox_auth"
    private val KEY_REFRESH_TOKEN = "refresh_token"
    private val KEY_CODE_VERIFIER = "code_verifier"

    init {
        coroutineScope.launch {
            initialize()
        }
    }

    private suspend fun initialize() {
        if (APP_KEY.isEmpty() || APP_KEY == "your_app_key_here") {
            _authState.value = DropboxAuthState.NotConfigured
            return
        }

        val refreshToken = getRefreshToken()
        if (refreshToken != null) {
            val credential = DbxCredential("", -1L, refreshToken, APP_KEY)
            createAuthenticatedClient(credential)
        } else {
            _authState.value = DropboxAuthState.NotAuthenticated
        }
    }

    fun startAuthentication() {
        val currentState = authState.value
        if (currentState !is DropboxAuthState.NotAuthenticated && currentState !is DropboxAuthState.ReAuthenticationRequired && currentState !is DropboxAuthState.Error) {
            return
        }

        coroutineScope.launch {
            _authState.value = DropboxAuthState.Authenticating
            try {
                val (codeVerifier, codeChallenge) = generatePKCEChallenge()
                saveCodeVerifier(codeVerifier)

                val scopes = "account_info.read files.content.read files.content.write"
                val authUrl = Uri.parse("https://www.dropbox.com/oauth2/authorize").buildUpon()
                    .appendQueryParameter("client_id", APP_KEY)
                    .appendQueryParameter("response_type", "code")
                    .appendQueryParameter("redirect_uri", REDIRECT_URI)
                    .appendQueryParameter("token_access_type", "offline")
                    .appendQueryParameter("scope", scopes)
                    .appendQueryParameter("code_challenge", codeChallenge)
                    .appendQueryParameter("code_challenge_method", "S256")
                    .build()

                val intent = Intent(Intent.ACTION_VIEW, authUrl).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e: NoSuchAlgorithmException) {
                _authState.value = DropboxAuthState.Error("Security algorithm not found. Cannot start authentication.")
            } catch (e: Exception) {
                _authState.value = DropboxAuthState.Error("Failed to launch browser: ${e.message}")
            }
        }
    }

    suspend fun handleOAuthRedirect(uri: Uri) {
        val code = uri.getQueryParameter("code")
        val error = uri.getQueryParameter("error")

        when {
            error != null -> _authState.value = DropboxAuthState.Error("Authentication failed: $error")
            code != null -> exchangeCodeForToken(code)
            else -> _authState.value = DropboxAuthState.Error("Invalid redirect.")
        }
    }

    private suspend fun exchangeCodeForToken(code: String) = withContext(Dispatchers.IO) {
        _authState.value = DropboxAuthState.TokenExchange
        val codeVerifier = getCodeVerifier() ?: run {
            _authState.value = DropboxAuthState.Error("Session expired.")
            return@withContext
        }

        try {
            val url = java.net.URL("https://api.dropboxapi.com/oauth2/token")
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            val postData = "code=${code}&grant_type=authorization_code&client_id=${APP_KEY}&redirect_uri=${REDIRECT_URI}&code_verifier=${codeVerifier}"
            connection.outputStream.use { it.write(postData.toByteArray(Charsets.UTF_8)) }

            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = org.json.JSONObject(response)
                val newRefreshToken = json.getString("refresh_token")
                val accessToken = json.getString("access_token")
                val expiresAt = System.currentTimeMillis() + json.getLong("expires_in") * 1000

                saveRefreshToken(newRefreshToken)
                clearCodeVerifier()
                val newCredential = DbxCredential(accessToken, expiresAt, newRefreshToken, APP_KEY)
                withContext(Dispatchers.Main) { createAuthenticatedClient(newCredential) }
            } else {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                _authState.value = DropboxAuthState.Error("Token exchange failed: $error")
            }
        } catch (e: Exception) {
            _authState.value = DropboxAuthState.Error("Token exchange network error: ${e.message}")
        }
    }

    fun logout() {
        coroutineScope.launch {
            clearAllSessionData()
            _authState.value = DropboxAuthState.NotAuthenticated
        }
    }

    private fun createAuthenticatedClient(credential: DbxCredential) {
        val onAuthFailure: () -> Unit = { 
            coroutineScope.launch {
                clearAllSessionData()
                _authState.value = DropboxAuthState.ReAuthenticationRequired("Session expired. Please log in again.")
            }
        }

        val okHttp = OkHttpClient.Builder().build()
        val requestor = OkHttp3Requestor(okHttp)
        val requestConfig = DbxRequestConfig.newBuilder("SatenDroid/1.0").withHttpRequestor(requestor).build()
        val baseClient = DbxClientV2(requestConfig, credential)
        val safeClient = SafeDropboxClient(baseClient, onAuthFailure)

        _authState.value = DropboxAuthState.Authenticated(safeClient)
    }

    private suspend fun clearAllSessionData() = withContext(Dispatchers.IO) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_CODE_VERIFIER)
            .commit()
    }

    private suspend fun saveRefreshToken(refreshToken: String) = withContext(Dispatchers.IO) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .commit()
    }

    private suspend fun getRefreshToken(): String? = withContext(Dispatchers.IO) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_REFRESH_TOKEN, null)
    }

    private suspend fun saveCodeVerifier(codeVerifier: String) = withContext(Dispatchers.IO) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_CODE_VERIFIER, codeVerifier)
            .commit()
    }

    private suspend fun getCodeVerifier(): String? = withContext(Dispatchers.IO) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_CODE_VERIFIER, null)
    }
    
    private suspend fun clearCodeVerifier() = withContext(Dispatchers.IO) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_CODE_VERIFIER)
            .commit()
    }

    @Throws(NoSuchAlgorithmException::class)
    private fun generatePKCEChallenge(): Pair<String, String> {
        val sr = SecureRandom()
        val code = ByteArray(96) // 96 bytes will be 128 chars in base64url
        sr.nextBytes(code)
        val codeVerifier = Base64.getUrlEncoder().withoutPadding().encodeToString(code)
        
        val digest = MessageDigest.getInstance("SHA-256")
        val challengeBytes = digest.digest(codeVerifier.toByteArray())
        val codeChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(challengeBytes)
        
        return Pair(codeVerifier, codeChallenge)
    }
}
