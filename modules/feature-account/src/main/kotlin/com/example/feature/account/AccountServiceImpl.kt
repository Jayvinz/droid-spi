package com.example.feature.account

import com.example.core.api.AccountService
import top.jayvin.android.spi.ServiceProvider
import top.jayvin.android.spi.ProviderFactory

/**
 * Demonstrates `@ProviderFactory`: the constructor is private and takes an argument, so the
 * registry's generated `new Impl()` cannot work. A static factory supplies the instance instead,
 * and the framework generates `INVOKESTATIC` bytecode with zero reflection.
 */
@ServiceProvider(service = AccountService::class)
class AccountServiceImpl private constructor(
    private val suffix: String,
) : AccountService {

    override fun userName(): String = "garen$suffix"

    override fun level(): String = "Lv.3"

    companion object {
        @ProviderFactory
        @JvmStatic
        fun create(): AccountServiceImpl = AccountServiceImpl("")
    }
}
