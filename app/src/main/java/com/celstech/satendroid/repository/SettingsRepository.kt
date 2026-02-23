package com.celstech.satendroid.repository

import android.content.Context

class SettingsRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_DROPBOX_RETRIES = "dropbox_max_retries"
        private const val DEFAULT_RETRIES = 3
    }

    fun getDropboxRetryCount(): Int {
        return prefs.getInt(KEY_DROPBOX_RETRIES, DEFAULT_RETRIES)
    }

    fun setDropboxRetryCount(count: Int) {
        if (count < 0 || count > 5) {
            // In a real app, you might show a user-facing error.
            // For now, we'll just log it and clamp the value.
            println("WARN: Dropbox retry count must be between 0 and 5. Received $count. Clamping to default.")
            prefs.edit().putInt(KEY_DROPBOX_RETRIES, DEFAULT_RETRIES).apply()
            return
        }
        prefs.edit().putInt(KEY_DROPBOX_RETRIES, count).apply()
    }
}
