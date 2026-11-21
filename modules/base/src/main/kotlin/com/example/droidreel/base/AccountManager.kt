package com.example.droidreel.base

import android.os.Handler
import android.os.Looper
import com.example.droidreel.base.model.User
import java.util.concurrent.CopyOnWriteArrayList

/** Session observer, always invoked on the main thread. */
fun interface AccountListener {
    fun onSessionChanged(user: User?)
}

/**
 * Shared session core. Business modules read [currentUser] / [isLoggedIn]; only the account
 * module's `AccountService` implementation calls [updateSession].
 */
object AccountManager {

    private val listeners = CopyOnWriteArrayList<AccountListener>()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var currentUser: User? = null
        private set

    val isLoggedIn: Boolean
        get() = currentUser != null

    /** Replaces the session and notifies listeners on the main thread. */
    fun updateSession(user: User?) {
        currentUser = user
        if (Looper.myLooper() == Looper.getMainLooper()) {
            dispatch(user)
        } else {
            mainHandler.post { dispatch(user) }
        }
    }

    fun addListener(listener: AccountListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: AccountListener) {
        listeners.remove(listener)
    }

    private fun dispatch(user: User?) {
        listeners.forEach { it.onSessionChanged(user) }
    }
}
