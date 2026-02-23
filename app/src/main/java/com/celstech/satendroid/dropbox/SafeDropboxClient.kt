package com.celstech.satendroid.dropbox

import com.dropbox.core.DbxException
import com.dropbox.core.oauth.DbxOAuthException
import com.dropbox.core.v2.DbxClientV2
import com.dropbox.core.v2.files.FileMetadata
import com.dropbox.core.v2.files.ListFolderResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream

/**
 * A wrapper for the DbxClientV2 that distinguishes between recoverable and unrecoverable errors.
 */
class SafeDropboxClient(
    private val client: DbxClientV2,
    private val onAuthFailure: () -> Unit
) {

    /**
     * Executes a Dropbox API call and handles exceptions.
     * It distinguishes between fatal authentication errors and other exceptions.
     *
     * @param T The return type of the API call.
     * @param block The suspend block executing the actual Dropbox API call.
     * @return The result of the API call.
     * @throws AuthException if a fatal, unrecoverable authentication error occurs.
     * @throws DbxException for any other recoverable API errors (e.g., network issues).
     */
    private suspend fun <T> execute(block: suspend () -> T): T {
        return withContext(Dispatchers.IO) {
            try {
                block()
            } catch (e: DbxOAuthException) {
                // This is a fatal authentication error, likely an invalid refresh token.
                // The session is unrecoverable. Trigger the re-authentication flow.
                onAuthFailure()
                // Throw a custom exception to signal that the flow should stop.
                throw AuthException("Fatal authentication error: ${e.message}", e)
            } catch (e: DbxException) {
                // Any other DbxException is considered potentially recoverable (e.g., network error,
                // file not found). Let the caller (ViewModel/UI) handle it.
                throw e
            }
        }
    }

    suspend fun listFolder(path: String): ListFolderResult {
        return execute { client.files().listFolder(path) }
    }

    suspend fun download(path: String, out: OutputStream): FileMetadata {
        return execute { client.files().download(path).download(out) }
    }
}

/**
 * A custom exception to signal that a fatal authentication error was detected and handled.
 * This helps calling code to stop processing, as the state has already been moved to
 * ReAuthenticationRequired.
 */
class AuthException(message: String, cause: Throwable) : Exception(message, cause)
