package com.example.droidreel.base.api

import com.example.droidreel.base.model.AuthResult
import com.example.droidreel.base.model.User

/**
 * Account business contract. The session itself lives in
 * [com.example.droidreel.base.AccountManager]; this interface owns credential evaluation and
 * persistence.
 */
interface AccountService {
    fun login(loginName: String, password: String): AuthResult
    fun logout()
    fun restoreSession(): User?
}
