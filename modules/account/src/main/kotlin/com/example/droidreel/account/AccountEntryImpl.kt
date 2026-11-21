package com.example.droidreel.account

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.example.droidreel.account.ui.LoginActivity
import com.example.droidreel.base.api.LoginEntry
import top.jayvin.android.spi.ServiceProvider

@ServiceProvider(service = LoginEntry::class)
class AccountEntryImpl : LoginEntry {

    override fun loginIntent(context: Context): Intent =
        Intent(context, LoginActivity::class.java).apply {
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
}
