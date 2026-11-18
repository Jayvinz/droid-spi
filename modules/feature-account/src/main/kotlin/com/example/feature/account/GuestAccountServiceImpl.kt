package com.example.feature.account

import com.example.core.api.AccountService
import top.jayvin.android.spi.ServiceProvider

/** Guest variant of [AccountService]. */
@ServiceProvider(service = AccountService::class, key = "guest")
class GuestAccountServiceImpl : AccountService {

    override fun userName(): String = "Guest"

    override fun level(): String = "Not signed in"
}
