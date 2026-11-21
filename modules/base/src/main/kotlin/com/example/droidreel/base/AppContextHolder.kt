package com.example.droidreel.base

import android.content.Context

/**
 * Process-wide application context holder for providers that cannot receive a constructor
 * argument, such as a `@ProviderFactory` provider.
 */
object AppContextHolder {

    private var application: Context? = null

    /** The application context; throws when [init] has not run. */
    val appContext: Context
        get() = application
            ?: throw IllegalStateException("AppContextHolder.init must run in Application.onCreate")

    /** Stores the application context. Safe to call more than once. */
    fun init(context: Context) {
        application = context.applicationContext
    }
}
