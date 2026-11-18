package com.example.core.api

/**
 * 账号信息。演示：同一接口两个 key 变体 + 私有构造 + `@ProviderFactory`。
 */
interface AccountService {
    fun userName(): String
    fun level(): String
}
