package com.example.droidreel.base.api

import android.content.Context
import android.content.Intent

/**
 * Community navigation contract. The app and other modules never reference community Activity
 * classes; the provider opens them on the caller's behalf.
 */
interface CommunityEntry {
    fun openFeed(context: Context)
    fun openProfile(context: Context)
    fun openPlayer(context: Context, videoId: String)
}

/**
 * Account UI contract. The returned Intent is launched with an Activity Result launcher; a
 * successful login finishes with `RESULT_OK`.
 */
interface LoginEntry {
    fun loginIntent(context: Context): Intent
}
