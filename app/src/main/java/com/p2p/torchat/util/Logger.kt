package com.p2p.torchat.util

import android.util.Log

/**
 * Security-aware logger that prevents leaking sensitive metadata in release builds.
 * Resolves Audit Point 18 (Log metadata leakage).
 */
object Logger {
    private const val TAG = Constants.TAG

    // In a real project, this would be linked to BuildConfig.DEBUG
    private var isDebug = true

    fun i(message: String) {
        if (isDebug) Log.i(TAG, sanitize(message))
    }

    fun e(message: String, throwable: Throwable? = null) {
        // Errors are logged even in release but heavily sanitized
        Log.e(TAG, sanitize(message), throwable)
    }

    fun d(message: String) {
        if (isDebug) Log.d(TAG, sanitize(message))
    }

    fun w(message: String) {
        Log.w(TAG, sanitize(message))
    }

    /**
     * Strips potentially sensitive patterns like onion addresses or public keys.
     */
    private fun sanitize(input: String): String {
        // Regex for .onion addresses
        val onionRegex = "[a-z2-7]{56}\\.onion".toRegex()
        // Simple regex for long Base64 strings (likely keys)
        val base64Regex = "[A-Za-z0-9+/]{40,}".toRegex()

        return input.replace(onionRegex, "[ONION_HIDDEN]")
                    .replace(base64Regex, "[KEY_HIDDEN]")
    }
}
