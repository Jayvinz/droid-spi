package com.example.feature.camera.ui

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.example.core.common.Trace

/**
 * Activity inside a dynamic module. The page is built entirely in code and does not depend on
 * layout/string resources inside the split.
 */
class CameraActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.WHITE)
            setPadding(48, 96, 48, 48)
        }

        panel.addView(TextView(this).apply {
            text = "Camera module loaded"
            textSize = 22f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
        })

        panel.addView(TextView(this).apply {
            text = Trace.line("camera", "This page comes from the on-demand dynamic module")
            textSize = 14f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 0)
        })

        setContentView(panel)
    }
}
