package com.celstech.satendroid.dropbox

/**
 * Represents the various states of the Dropbox authentication and session lifecycle.
 * This sealed class enforces a strict state machine, preventing invalid states.
 */
sealed class DropboxAuthState {
    /**
     * The Dropbox integration has not been configured by the developer (i.e., missing App Key).
     * This is a terminal state until the app is reconfigured and restarted.
     */
    object NotConfigured : DropboxAuthState()

    /**
     * The user is not authenticated. The app is in a clean state, ready to start a new
     * authentication flow.
     */
    object NotAuthenticated : DropboxAuthState()

    /**
     * The app has initiated the authentication flow and is waiting for the user to grant
     * permission in the browser.
     */
    object Authenticating : DropboxAuthState()

    /**
     * The user has granted permission, and the app is currently exchanging the authorization
     * code for an access token in the background.
     */
    object TokenExchange : DropboxAuthState()

    /**
     * The session is active and authenticated. This state holds the client instance needed
     * for API calls. The client is responsible for transparently refreshing short-lived
     * access tokens.
     * @param client The safe client wrapper for making API calls.
     */
    data class Authenticated(val client: SafeDropboxClient) : DropboxAuthState()

    /**
     * A fatal, unrecoverable authentication error occurred. This happens when the long-lived
     * refresh token becomes invalid (e.g., revoked by the user). The user must sign in again.
     * @param message A message explaining why re-authentication is required.
     */
    data class ReAuthenticationRequired(val message: String) : DropboxAuthState()

    /**
     * A temporary, recoverable error occurred during the authentication process (e.g., network
     * issue during token exchange). The user may be able to retry.
     * @param message A description of the error.
     */
    data class Error(val message: String) : DropboxAuthState()
}
