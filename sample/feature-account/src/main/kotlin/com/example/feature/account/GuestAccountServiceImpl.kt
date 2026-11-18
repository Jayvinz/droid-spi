package com.example.feature.account

import com.example.core.api.AccountService
import com.example.spi.ServiceProvider

@ServiceProvider(service = AccountService::class, key = "guest")
class GuestAccountServiceImpl : AccountService {

    override fun userName(): String = "访客"

    override fun level(): String = "未登录"
}
