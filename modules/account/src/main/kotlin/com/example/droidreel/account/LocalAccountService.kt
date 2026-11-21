package com.example.droidreel.account

import android.content.Context
import com.example.droidreel.base.AccountManager
import com.example.droidreel.base.AppContextHolder
import com.example.droidreel.base.api.AccountService
import com.example.droidreel.base.model.AuthResult
import com.example.droidreel.base.model.User
import top.jayvin.android.spi.ProviderFactory
import top.jayvin.android.spi.ServiceProvider

/**
 * Fixed-credential demo account service.
 *
 * `demo` / `123456` is the only login pair, and no password is ever persisted. The private
 * constructor needs a [Context], so construction goes through a `@ProviderFactory`: the
 * generated table emits `INVOKESTATIC LocalAccountService.create()` and never reflective
 * constructor calls.
 */
@ServiceProvider(service = AccountService::class)
class LocalAccountService private constructor(private val context: Context) : AccountService {

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun login(loginName: String, password: String): AuthResult {
        val name = loginName.trim()
        if (name == DEMO_NAME && password == DEMO_PASSWORD) {
            val user = demoUser()
            persistSession(user)
            AccountManager.updateSession(user)
            return AuthResult(success = true, user = user)
        }
        return AuthResult(
            success = false,
            message = "Wrong account or password (demo / 123456)",
        )
    }

    override fun logout() {
        prefs.edit().clear().apply()
        AccountManager.updateSession(null)
    }

    override fun restoreSession(): User? {
        val id = prefs.getString(KEY_ID, null)
        val nickname = prefs.getString(KEY_NICKNAME, null)
        val bio = prefs.getString(KEY_BIO, null)
        if (id.isNullOrBlank() || nickname.isNullOrBlank()) {
            prefs.edit().clear().apply()
            return null
        }
        val user = User(id = id, nickname = nickname, bio = bio.orEmpty())
        AccountManager.updateSession(user)
        return user
    }

    private fun persistSession(user: User) {
        prefs.edit()
            .putString(KEY_ID, user.id)
            .putString(KEY_NICKNAME, user.nickname)
            .putString(KEY_BIO, user.bio)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "droidreel-session"
        private const val KEY_ID = "session.id"
        private const val KEY_NICKNAME = "session.nickname"
        private const val KEY_BIO = "session.bio"

        // Intentionally fixed test data: this is a demo, not an authentication service.
        private const val DEMO_NAME = "demo"
        private const val DEMO_PASSWORD = "123456"

        private val shared by lazy {
            LocalAccountService(AppContextHolder.appContext)
        }

        @ProviderFactory
        @JvmStatic
        fun create(): LocalAccountService = shared

        fun demoUser(): User = User(
            id = "u_demo",
            nickname = "Xiao D",
            bio = "Recording life, and the wind",
        )
    }
}
