package com.example.core.api

import android.content.Context

/**
 * Camera capability. The implementation lives in the **on-demand dynamic module**
 * `:modules:feature_camera`. While it is not installed,
 * `ServiceLoader.load(CameraService::class.java)` returns null; after installation the next
 * call gets an instance.
 *
 * The implementation also performs the page navigation itself, so base never needs to know
 * the `CameraActivity` class name.
 */
interface CameraService {
    /** Returns a short description for the demo screen. */
    fun description(): String

    /** Opens the camera page. */
    fun open(context: Context)
}
