package com.p2p.torchat.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.p2p.torchat.crypto.E2EManager
import com.p2p.torchat.util.Constants

class AuthViewModel : ViewModel() {
    var isAuthenticated by mutableStateOf(false)
        private set

    var failedAttempts by mutableIntStateOf(0)
        private set

    fun handleAuthResult(password: String, savedHash: String?): Boolean {
        if (E2EManager.verifyPassword(password, savedHash ?: "")) {
            isAuthenticated = true
            failedAttempts = 0
            return true
        }
        failedAttempts++
        return false
    }

    fun resetAuth() {
        isAuthenticated = false
    }

    fun setInitialFailedAttempts(attempts: Int) {
        failedAttempts = attempts
    }
}
