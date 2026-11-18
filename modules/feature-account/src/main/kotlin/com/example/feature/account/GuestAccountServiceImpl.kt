package com.example.feature.account

import com.example.core.api.AccountService
import top.jayvin.android.spi.ServiceProvider

@ServiceProvider(service = AccountService::class, key = "guest")
class GuestAccountServiceImpl : AccountService {

    override fun userName(): String = "Guest"

    override fun level(): String = "Not signed in"
}
