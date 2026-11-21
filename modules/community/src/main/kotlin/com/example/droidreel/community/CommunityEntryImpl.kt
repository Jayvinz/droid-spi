package com.example.droidreel.community

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.example.droidreel.base.api.CommunityEntry
import com.example.droidreel.community.ui.CommunityActivity
import com.example.droidreel.community.ui.PlayerActivity
import com.example.droidreel.community.ui.ProfileActivity
import top.jayvin.android.spi.ServiceProvider

@ServiceProvider(service = CommunityEntry::class)
class CommunityEntryImpl : CommunityEntry {

    override fun openFeed(context: Context) {
        context.startNewTaskIfNeeded(Intent(context, CommunityActivity::class.java))
    }

    override fun openProfile(context: Context) {
        context.startNewTaskIfNeeded(Intent(context, ProfileActivity::class.java))
    }

    override fun openPlayer(context: Context, videoId: String) {
        context.startNewTaskIfNeeded(
            Intent(context, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_VIDEO_ID, videoId),
        )
    }

    private fun Context.startNewTaskIfNeeded(intent: Intent) {
        if (this !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }
}
