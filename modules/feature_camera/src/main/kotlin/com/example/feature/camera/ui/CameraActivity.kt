package com.example.feature.camera.ui

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.example.core.common.Trace
import com.google.android.play.core.splitcompat.SplitCompat

/**
 * 动态模块里的 Activity。进入前必须 `SplitCompat.installActivity(this)`，
 * 否则这个模块自己的 layout/string 可能加载不到。
 */
class CameraActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SplitCompat.installActivity(this)

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.WHITE)
            setPadding(48, 96, 48, 48)
        }

        panel.addView(TextView(this).apply {
            text = "相机模块已加载"
            textSize = 22f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
        })

        panel.addView(TextView(this).apply {
            text = Trace.line("camera", "这个页面来自 on-demand 动态模块")
            textSize = 14f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 0)
        })

        setContentView(panel)
    }
}
