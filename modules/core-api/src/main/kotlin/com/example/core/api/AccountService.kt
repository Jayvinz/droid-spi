package com.example.core.api

/**
 * Account information. Demo: one interface with two key variants + a private constructor + `@ProviderFactory`.
 */
interface AccountService {
    /** Returns the displayed user name. */
    fun userName(): String

    /** Returns the membership level label. */
    fun level(): String
}
