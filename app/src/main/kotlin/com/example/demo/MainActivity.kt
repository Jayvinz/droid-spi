package com.example.demo

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.core.api.AccountService
import com.example.core.api.CameraService
import com.example.core.api.ChannelService
import com.example.core.api.DebugService
import com.example.core.api.HomeService
import com.example.core.api.LegacyService
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory
import com.google.android.play.core.splitinstall.SplitInstallRequest
import com.google.android.play.core.splitinstall.SplitInstallStateUpdatedListener
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus
import top.jayvin.android.spi.ProviderState
import top.jayvin.android.spi.ServiceLoader
import java.util.Locale

/**
 * Demo screen that doubles as the on-device UI test for the build-variant matrix.
 *
 * Every assertion runs at startup and is rendered as PASS/FAIL, so a screenshot of the top of
 * the screen proves which variant was installed and that the per-variant table is correct:
 *
 * | Case | `freeDebug` | `paidDebug` | `freeRelease` | `paidRelease` |
 * |---|---|---|---|---|
 * | C01 registry initialized, table non-empty | PASS | PASS | PASS | PASS |
 * | C07 flavor provider matches flavor | free | paid | free | paid |
 * | C08 table entry count (8 debug / 7 release) | 8 | 8 | 7 | 7 |
 * | C09 debug-only provider present | yes | yes | no | no |
 * | C10 dynamic-module provider MISSING, load=null | PASS | PASS | PASS | PASS |
 *
 * The dynamic module install button exercises the "unavailable provider becomes available" path;
 * on non-Play channels it only needs to fail gracefully.
 */
class MainActivity : Activity() {

    private data class Check(
        val id: String,
        val name: String,
        val expected: String,
        val actual: String,
        val pass: Boolean,
    )

    private lateinit var summaryView: TextView
    private lateinit var checksView: TextView
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
            setPadding(dp(14), dp(24), dp(14), dp(12))
        }

        root.addView(TextView(this).apply {
            text = "Android SPI · variant self-check"
            textSize = 18f
            setTextColor(Color.BLACK)
            setTypeface(typeface, Typeface.BOLD)
        })

        root.addView(TextView(this).apply {
            text = "variant = ${BuildConfig.FLAVOR}${BuildConfig.BUILD_TYPE.replaceFirstChar { it.uppercase() }}" +
                "   (flavor=${BuildConfig.FLAVOR}, buildType=${BuildConfig.BUILD_TYPE})" +
                "\npackage = $packageName"
            textSize = 11f
            setTextColor(Color.GRAY)
            setPadding(0, dp(2), 0, dp(8))
        })

        summaryView = TextView(this).apply {
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        }
        root.addView(summaryView)

        checksView = TextView(this).apply {
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#222222"))
            setPadding(0, dp(6), 0, dp(8))
        }
        root.addView(checksView)

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addWeighted(actionButton("Re-run checks") { render() })
            addWeighted(actionButton("Camera module") { openCamera() })
        })

        output = TextView(this).apply {
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#222222"))
            setPadding(0, dp(8), 0, 0)
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

    // ── UI test cases ────────────────────────────────────────────

    private fun runChecks(): List<Check> {
        val snapshot = ServiceLoader.snapshot()
        val rows = snapshot.size
        val expectedRows = if (BuildConfig.DEBUG) 8 else 7

        val home = ServiceLoader.load(HomeService::class.java)
        val vip = ServiceLoader.load(HomeService::class.java, "vip")
        val account = ServiceLoader.load(AccountService::class.java)
        val guest = ServiceLoader.load(AccountService::class.java, "guest")
        val legacy = ServiceLoader.load(LegacyService::class.java)
        val channel = ServiceLoader.load(ChannelService::class.java)
        val debug = ServiceLoader.load(DebugService::class.java)
        val cameraState = ServiceLoader.stateOf(CameraService::class.java)
        val camera = ServiceLoader.load(CameraService::class.java)

        val expectedFlavor = BuildConfig.FLAVOR
        val expectedChannelClass = when (expectedFlavor) {
            "free" -> "FreeChannelServiceImpl"
            "paid" -> "PaidChannelServiceImpl"
            else -> "?Unknown"
        }
        val channelActual = channel?.let {
            "${it.channelName()} / ${it.javaClass.simpleName}"
        } ?: "null"
        val debugActual = when {
            BuildConfig.DEBUG && debug != null -> "registered: ${debug.marker()}"
            !BuildConfig.DEBUG && debug == null &&
                ServiceLoader.stateOf(DebugService::class.java) == null ->
                "absent (state=null, load=null)"
            else -> "unexpected (debug=${BuildConfig.DEBUG}, instance=${debug != null})"
        }

        return listOf(
            Check("C01", "registry initialized", "true, entries>0", "${ServiceLoader.isInitialized()}, entries=$rows", ServiceLoader.isInitialized() && rows > 0),
            Check("C02", "Home default", "Home · default implementation", home?.title() ?: "null", home?.title() == "Home · default implementation"),
            Check("C03", "Home vip", "Home · VIP variant", vip?.title() ?: "null", vip?.title() == "Home · VIP variant"),
            Check("C04", "Account factory", "garen / Lv.3", account?.let { "${it.userName()} / ${it.level()}" } ?: "null", account != null && account.userName() == "garen" && account.level() == "Lv.3"),
            Check("C05", "Account guest", "Guest / Not signed in", guest?.let { "${it.userName()} / ${it.level()}" } ?: "null", guest != null && guest.userName() == "Guest"),
            Check("C06", "Legacy META-INF", "A provider from META-INF/services", legacy?.describe() ?: "null", legacy?.describe() == "A provider from META-INF/services"),
            Check("C07", "flavor provider", "$expectedFlavor / $expectedChannelClass", channelActual, channel != null && channel.channelName() == expectedFlavor && channel.javaClass.simpleName == expectedChannelClass),
            Check("C08", "variant table entries", "entries=$expectedRows", "entries=$rows", rows == expectedRows),
            Check("C09", "buildType isolation", if (BuildConfig.DEBUG) "registered" else "absent", debugActual, debugActual != "unexpected (debug=${BuildConfig.DEBUG}, instance=${debug != null})"),
            Check("C10", "dynamic module absent", "state=MISSING, load=null", "state=$cameraState, load=${camera?.let { "instance" } ?: "null"}", cameraState == ProviderState.MISSING && camera == null),
            Check("C11", "no DEAD provider", "0", snapshot.count { it.third == ProviderState.DEAD }.toString(), snapshot.none { it.third == ProviderState.DEAD }),
            Check("C12", "multi-provider stream", "2 home providers", "loadAll=${ServiceLoader.loadAll(HomeService::class.java).size}, stream=${ServiceLoader.stream(HomeService::class.java).count()}", ServiceLoader.loadAll(HomeService::class.java).size == 2),
        )
    }

    private fun render() {
        val checks = runChecks()
        val passed = checks.count { it.pass }
        val allPass = passed == checks.size

        summaryView.text = "SELF-CHECK  ${if (allPass) "PASS" else "FAIL"}   $passed/${checks.size}"
        summaryView.setTextColor(if (allPass) Color.parseColor("#1B5E20") else Color.parseColor("#B00020"))

        val spannable = SpannableStringBuilder()
        checks.forEach { check ->
            val line = "[${if (check.pass) "PASS" else "FAIL"}] ${check.id} ${check.name} = ${check.actual}\n"
            val start = spannable.length
            spannable.append(line)
            spannable.setSpan(
                ForegroundColorSpan(if (check.pass) Color.parseColor("#1B5E20") else Color.parseColor("#B00020")),
                start, start + 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            if (!check.pass) {
                spannable.append("       expected: ${check.expected}\n")
                    .setSpan(
                        ForegroundColorSpan(Color.parseColor("#B00020")),
                        spannable.length - ("       expected: ${check.expected}\n").length,
                        spannable.length - 1,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
            }
        }
        checksView.text = spannable

        output.text = buildDetails()
    }

    private fun buildDetails(): String {
        val sb = StringBuilder(log)
        sb.appendLine("── provider table ───────────")
        ServiceLoader.snapshot().forEach { (api, key, state) ->
            sb.appendLine(String.format(Locale.US, "%-24s %-12s %s", api.substringAfterLast('.'), "key=${if (key.isEmpty()) "\"\"" else key}", state))
        }

        sb.appendLine()
        sb.appendLine("── service calls ────────────────")
        val home = ServiceLoader.load(HomeService::class.java)
        val vip = ServiceLoader.load(HomeService::class.java, "vip")
        val account = ServiceLoader.load(AccountService::class.java)
        val guest = ServiceLoader.load(AccountService::class.java, "guest")
        val camera = ServiceLoader.load(CameraService::class.java)
        val channel = ServiceLoader.load(ChannelService::class.java)
        val legacy = ServiceLoader.load(LegacyService::class.java)

        sb.appendLine("Home(default)  = " + (home?.title() ?: "null"))
        sb.appendLine("Home(vip)      = " + (vip?.title() ?: "null"))
        sb.appendLine("Account        = " + (account?.let { "${it.userName()} / ${it.level()}" } ?: "null"))
        sb.appendLine("Account(guest) = " + (guest?.let { "${it.userName()} / ${it.level()}" } ?: "null"))
        sb.appendLine("Channel        = " + (channel?.describe() ?: "null"))
        sb.appendLine("Camera         = " + (camera?.description() ?: "null (module not installed)"))
        sb.appendLine("Legacy(SPI)    = " + (legacy?.describe() ?: "null"))

        sb.appendLine()
        sb.appendLine("── JDK-style Provider API ────")
        val first = ServiceLoader.findFirst(HomeService::class.java)
        sb.appendLine("findFirst = " + (first?.type()?.simpleName ?: "null"))
        ServiceLoader.stream(HomeService::class.java).forEach { p ->
            sb.appendLine("stream    = ${p.type().simpleName} / ${p.state()}")
        }

        return sb.toString()
    }

    private fun openCamera() {
        val camera = ServiceLoader.load(CameraService::class.java)
        if (camera != null) {
            camera.open(this)
            return
        }
        append("CameraService unavailable; requesting the dynamic module…")
        try {
            val request = SplitInstallRequest.newBuilder()
                .addModule("feature_camera")
                .build()
            installManager.startInstall(request)
                .addOnFailureListener { e ->
                    append("startInstall failed: ${e.message} (expected on non-Play channels)")
                    toast("Dynamic delivery is not supported in this environment")
                }
        } catch (e: Exception) {
            append("SplitInstall unavailable: ${e.message} (expected on non-Play channels)")
            toast("Dynamic delivery is not supported in this environment")
        }
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
