package com.example.demo

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
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
import top.jayvin.android.spi.ServiceLoader
import java.util.Locale
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
            SplitInstallSessionStatus.INSTALLED -> "INSTALLED · takes effect on the next load"
            SplitInstallSessionStatus.FAILED -> "FAILED errorCode=${state.errorCode()}"
            SplitInstallSessionStatus.CANCELED -> "CANCELED"
            SplitInstallSessionStatus.REQUIRES_USER_CONFIRMATION -> "REQUIRES_USER_CONFIRMATION"
            else -> "OTHER(${state.status()})"
        }
        append("Install status: $status")
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
            text = "The registry is instantiated directly in Application.onCreate"
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, dp(4), 0, dp(12))
        })

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addWeighted(actionButton("Re-render") { render() })
            addWeighted(actionButton("Camera module") { openCamera() })
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
        append("CameraService unavailable; requesting the dynamic module…")
        val request = SplitInstallRequest.newBuilder()
            .addModule("feature_camera")
            .build()
        installManager.startInstall(request)
            .addOnFailureListener { e ->
                append("startInstall failed: ${e.message} (expected on non-Play channels)")
                toast("Dynamic delivery is not supported in this environment")
            }
    }

    private fun render() {
        val sb = StringBuilder(log)

        sb.appendLine()
        sb.appendLine("Initialized: ${ServiceLoader.isInitialized()}")
        sb.appendLine()
        sb.appendLine("── provider table ───────────")
        ServiceLoader.snapshot().forEach { (api, key, state) ->
            val name = api.substringAfterLast('.')
            val k = if (key.isEmpty()) "\"\"" else key
            sb.appendLine(String.format(Locale.US, "%-22s %-14s %s", name, "key=$k", state))
        }

        sb.appendLine()
        sb.appendLine("── service calls ────────────────")
        val home = ServiceLoader.load(HomeService::class.java)
        val vip = ServiceLoader.load(HomeService::class.java, "vip")
        val account = ServiceLoader.load(AccountService::class.java)
        val guest = ServiceLoader.load(AccountService::class.java, "guest")
        val camera = ServiceLoader.load(CameraService::class.java)

        sb.appendLine("Home(default)  = " + (home?.title() ?: "null"))
        sb.appendLine("Home(vip)      = " + (vip?.title() ?: "null"))
        sb.appendLine("Account        = " + (account?.let { "${it.userName()} / ${it.level()}" } ?: "null"))
        sb.appendLine("Account(guest) = " + (guest?.let { "${it.userName()} / ${it.level()}" } ?: "null"))
        sb.appendLine("Camera         = " + (camera?.description() ?: "null (module not installed)"))
        val legacy = ServiceLoader.load(LegacyService::class.java)
        sb.appendLine("Legacy(SPI)    = " + (legacy?.describe() ?: "null"))

        sb.appendLine()
        sb.appendLine("── JDK-style Provider API ────")
        val first = ServiceLoader.findFirst(HomeService::class.java)
        sb.appendLine("findFirst = " + (first?.type()?.simpleName ?: "null"))
        ServiceLoader.stream(HomeService::class.java).forEach { p ->
            sb.appendLine("stream    = ${p.type().simpleName} / ${p.state()}")
        }

        output.text = sb.toString()
    }

    private fun append(line: String) {
        log.append(line).append('\n')
        runOnUiThread { render() }
    }

    private fun actionButton(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            textSize = 13f
            setOnClickListener { onClick() }
        }

    private fun LinearLayout.addWeighted(view: View) {
        addView(view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun toast(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
