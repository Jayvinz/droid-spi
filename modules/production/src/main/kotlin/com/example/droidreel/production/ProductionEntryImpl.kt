package com.example.droidreel.production

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.example.droidreel.base.api.ProductionEntry
import com.example.droidreel.production.ui.ProductionHubActivity
import top.jayvin.android.spi.ServiceProvider

@ServiceProvider(service = ProductionEntry::class)
class ProductionEntryImpl : ProductionEntry {

    override fun openCreate(context: Context) {
        val intent = Intent(context, ProductionHubActivity::class.java)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    override fun versionLabel(): String = "Production · delivered on demand"
}
