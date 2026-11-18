package com.example.feature.account

import com.example.core.api.AccountService
import top.fishquant.mobile.spi.ServiceProvider
import top.fishquant.mobile.spi.ProviderFactory

/**
 * 演示 `@ProviderFactory`：构造器是私有的、且带参数，注册表生成的 `new Impl()` 走不通，
 * 改由静态工厂提供实例，框架生成的字节码是 `INVOKESTATIC`，零反射。
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
