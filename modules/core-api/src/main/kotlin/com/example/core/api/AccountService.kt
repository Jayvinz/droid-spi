package com.example.core.api

/**
 * Account information. Demo: one interface with two key variants + a private constructor + `@ProviderFactory`.
 */
interface AccountService {
    fun userName(): String
    fun level(): String
}
