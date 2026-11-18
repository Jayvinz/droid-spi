package com.example.demo

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.core.api.AccountService
import com.example.core.api.CameraService
import com.example.core.api.HomeService
import com.example.core.api.LegacyService
import top.fishquant.mobile.spi.ServiceLoader
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory
import com.google.android.play.core.splitinstall.SplitInstallRequest
import com.google.android.play.core.splitinstall.SplitInstallStateUpdatedListener
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus

class MainActivity : Activity() {

    private lateinit var output: TextView
    private val log = StringBuilder()

    private val installManager by lazy { SplitInstallManagerFactory.create(this) }

    private val installListener = SplitInstallStateUpdatedListener { state ->
        val status = when (state.status()) {
            SplitInstallSessionStatus.PENDING -> "PENDING"
            SplitInstallSessionStatus.DOWNLOADING ->
                "DOWNLOADING ${state.bytesDownloaded()}/${state.totalBytesToDownload()}"
            SplitInstallSessionStatus.DOWNLOADED -> "DOWNLOADED"
            SplitInstallSessionStatus.INSTALLING -> "INSTALLING"
            SplitInstallSessionStatus.INSTALLED -> "INSTALLED · 框架已自动 refresh 注册表"
            SplitInstallSessionStatus.FAILED -> "FAILED errorCode=${state.errorCode()}"
            SplitInstallSessionStatus.CANCELED -> "CANCELED"
            SplitInstallSessionStatus.REQUIRES_USER_CONFIRMATION -> "REQUIRES_USER_CONFIRMATION"
            else -> "OTHER(${state.status()})"
        }
        append("安装状态：$status")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(28), dp(16), dp(16))
        }

        root.addView(TextView(this).apply {
            text = "Android SPI ServiceLoader"
            textSize = 20f
            setTextColor(Color.BLACK)
            setTypeface(typeface, Typeface.BOLD)
        })

        root.addView(TextView(this).apply {
            text = "注册表由 ContentProvider 在 Application 之前初始化"
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, dp(4), 0, dp(12))
        })

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addWeighted(actionButton("刷新") {
                ServiceLoader.refresh()
                append("手动 refresh 完成")
                render()
            })
            addWeighted(actionButton("相机模块") { openCamera() })
        })

        output = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#222222"))
            setPadding(0, dp(12), 0, 0)
        }

        val scroll = ScrollView(this).apply { addView(output) }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        setContentView(root)
        render()
    }

    override fun onStart() {
        super.onStart()
        installManager.registerListener(installListener)
    }

    override fun onStop() {
        installManager.unregisterListener(installListener)
        super.onStop()
    }

    private fun openCamera() {
        val camera = ServiceLoader.load(CameraService::class.java)
        if (camera != null) {
            camera.open(this)
            return
        }
        append("CameraService 不可用，发起动态模块下载…")
        val request = SplitInstallRequest.newBuilder()
            .addModule("feature_camera")
            .build()
        installManager.startInstall(request)
            .addOnFailureListener { e ->
                append("startInstall 失败：${e.message}（非 Play 渠道时属预期）")
                toast("当前环境不支持动态分发")
            }
    }

    private fun render() {
        val sb = StringBuilder(log)

        sb.appendLine()
        sb.appendLine("初始化: ${ServiceLoader.isInitialized()}")
        sb.appendLine()
        sb.appendLine("── provider 配置表 ───────────")
        ServiceLoader.snapshot().forEach { (api, key, state) ->
            val name = api.substringAfterLast('.')
            val k = if (key.isEmpty()) "\"\"" else key
            sb.appendLine(pad(name, 22) + pad("key=$k", 14) + state)
        }

        sb.appendLine()
        sb.appendLine("── 服务调用 ────────────────")
        val home = ServiceLoader.load(HomeService::class.java)
        val vip = ServiceLoader.load(HomeService::class.java, "vip")
        val account = ServiceLoader.load(AccountService::class.java)
        val guest = ServiceLoader.load(AccountService::class.java, "guest")
        val camera = ServiceLoader.load(CameraService::class.java)

        sb.appendLine("Home(default)  = " + (home?.title() ?: "null"))
        sb.appendLine("Home(vip)      = " + (vip?.title() ?: "null"))
        sb.appendLine("Account        = " + (account?.let { "${it.userName()} / ${it.level()}" } ?: "null"))
        sb.appendLine("Account(guest) = " + (guest?.let { "${it.userName()} / ${it.level()}" } ?: "null"))
        sb.appendLine("Camera         = " + (camera?.description() ?: "null（模块未安装）"))
        val legacy = ServiceLoader.load(LegacyService::class.java)
        sb.appendLine("Legacy(SPI)    = " + (legacy?.describe() ?: "null"))

        sb.appendLine()
        sb.appendLine("── JDK 风格 Provider API ────")
        val first = ServiceLoader.findFirst(HomeService::class.java)
        sb.appendLine("findFirst = " + (first?.type()?.simpleName ?: "null"))
        ServiceLoader.stream(HomeService::class.java).forEach { p ->
            sb.appendLine("stream    = ${p.type().simpleName} / ${p.state()}")
        }

        output.text = sb.toString()
    }

    private fun append(line: String) {
        synchronized(log) { log.append(line).append('\n') }
        runOnUiThread { render() }
    }

    private fun actionButton(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            textSize = 13f
            setOnClickListener { onClick() }
        }

    private fun LinearLayout.addWeighted(view: android.view.View) {
        addView(view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun toast(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    private fun pad(text: String, width: Int): String {
        if (text.length >= width) return "$text "
        return text + " ".repeat(width - text.length)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
