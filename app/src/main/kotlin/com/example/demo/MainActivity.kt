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
 * It runs [runChecks] at startup and renders PASS/FAIL per case, so a screenshot of the top of
 * the screen proves which variant was installed and that the per-variant table is correct.
 * The expected values per variant are documented in README.md ("Demo variant matrix").
 */
class MainActivity : Activity() {

    private data class Check(
        val id: String,
        val name: String,
        val expected: String,
        val actual: String,
        val pass: Boolean = expected == actual,
    )

    /** One pass over the runtime state shared by the checks and the details pane. */
    private class Probes(
        val snapshot: List<Triple<String, String, ProviderState>>,
        val home: HomeService?,
        val vip: HomeService?,
        val account: AccountService?,
        val guest: AccountService?,
        val legacy: LegacyService?,
        val channel: ChannelService?,
        val debug: DebugService?,
        val debugState: ProviderState?,
        val camera: CameraService?,
        val cameraState: ProviderState?,
    )

    private lateinit var summaryView: TextView
    private lateinit var checksView: TextView
    private lateinit var output: TextView
    private val log = StringBuilder()
    private var lastProbes: Probes? = null

    private val passColor = Color.parseColor("#1B5E20")
    private val failColor = Color.parseColor("#B00020")
    private val bodyColor = Color.parseColor("#222222")

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
            setTextColor(bodyColor)
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
            setTextColor(bodyColor)
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

    /** Loads every provider once; checks and the details pane both read from this snapshot. */
    private fun probe(): Probes {
        val snapshot = ServiceLoader.snapshot()
        return Probes(
            snapshot = snapshot,
            home = ServiceLoader.load(HomeService::class.java),
            vip = ServiceLoader.load(HomeService::class.java, "vip"),
            account = ServiceLoader.load(AccountService::class.java),
            guest = ServiceLoader.load(AccountService::class.java, "guest"),
            legacy = ServiceLoader.load(LegacyService::class.java),
            channel = ServiceLoader.load(ChannelService::class.java),
            debug = ServiceLoader.load(DebugService::class.java),
            debugState = ServiceLoader.stateOf(DebugService::class.java),
            camera = ServiceLoader.load(CameraService::class.java),
            cameraState = ServiceLoader.stateOf(CameraService::class.java),
        )
    }

    private fun runChecks(p: Probes): List<Check> {
        val expectedRows = if (BuildConfig.DEBUG) 8 else 7
        val expectedFlavor = BuildConfig.FLAVOR
        val expectedChannelClass = when (expectedFlavor) {
            "free" -> "FreeChannelServiceImpl"
            "paid" -> "PaidChannelServiceImpl"
            else -> "?Unknown"
        }
        val debug = p.debug
        val debugOk = if (BuildConfig.DEBUG) {
            debug != null && p.debugState == ProviderState.READY
        } else {
            debug == null && p.debugState == null
        }
        val debugActual = when {
            debug != null -> "registered: ${debug.marker()}"
            BuildConfig.DEBUG -> "absent (state=${p.debugState})"
            else -> "absent (state=${p.debugState}, load=null)"
        }
        val homeVariants = ServiceLoader.loadAll(HomeService::class.java).size
        val streamCount = ServiceLoader.stream(HomeService::class.java).count()
        val dead = p.snapshot.count { it.third == ProviderState.DEAD }

        return listOf(
            Check("C01", "registry initialized", "initialized=true, entries>0",
                "initialized=${ServiceLoader.isInitialized()}, entries=${p.snapshot.size}",
                ServiceLoader.isInitialized() && p.snapshot.isNotEmpty()),
            Check("C02", "Home default", "Home · default implementation", p.home?.title() ?: "null"),
            Check("C03", "Home vip", "Home · VIP variant", p.vip?.title() ?: "null"),
            Check("C04", "Account factory", "garen / Lv.3", p.account?.summary() ?: "null"),
            Check("C05", "Account guest", "Guest / Not signed in", p.guest?.summary() ?: "null"),
            Check("C06", "Legacy META-INF", "A provider from META-INF/services", p.legacy?.describe() ?: "null"),
            Check("C07", "flavor provider", "$expectedFlavor / $expectedChannelClass",
                p.channel?.let { "${it.channelName()} / ${it.javaClass.simpleName}" } ?: "null"),
            Check("C08", "variant table entries", "entries=$expectedRows", "entries=${p.snapshot.size}"),
            Check("C09", "buildType isolation", if (BuildConfig.DEBUG) "registered" else "absent",
                debugActual, debugOk),
            Check("C10", "dynamic module absent", "state=MISSING, load=null",
                "state=${p.cameraState}, load=${if (p.camera == null) "null" else "instance"}"),
            Check("C11", "no DEAD provider", "0", "$dead"),
            Check("C12", "multi-provider stream", "loadAll=2, stream=2",
                "loadAll=$homeVariants, stream=$streamCount"),
        )
    }

    private fun render() {
        val probes = probe()
        lastProbes = probes
        val checks = runChecks(probes)
        val passed = checks.count { it.pass }
        val allPass = passed == checks.size

        summaryView.text = "SELF-CHECK  ${if (allPass) "PASS" else "FAIL"}   $passed/${checks.size}"
        summaryView.setTextColor(if (allPass) passColor else failColor)

        val spannable = SpannableStringBuilder()
        checks.forEach { check ->
            val line = "[${if (check.pass) "PASS" else "FAIL"}] ${check.id} ${check.name} = ${check.actual}\n"
            val tagStart = spannable.length
            spannable.append(line)
            spannable.setSpan(
                ForegroundColorSpan(if (check.pass) passColor else failColor),
                tagStart, tagStart + 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            if (!check.pass) {
                val expectedLine = "       expected: ${check.expected}\n"
                val expectedStart = spannable.length
                spannable.append(expectedLine)
                spannable.setSpan(
                    ForegroundColorSpan(failColor),
                    expectedStart, expectedStart + expectedLine.length - 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        checksView.text = spannable

        output.text = buildDetails(probes)
    }

    private fun buildDetails(p: Probes): String {
        val sb = StringBuilder(log)
        sb.appendLine("── provider table ───────────")
        p.snapshot.forEach { (api, key, state) ->
            sb.appendLine(String.format(Locale.US, "%-24s %-12s %s", api.substringAfterLast('.'),
                "key=${if (key.isEmpty()) "\"\"" else key}", state))
        }

        sb.appendLine()
        sb.appendLine("── service calls ────────────────")
        sb.appendLine("Home(default)  = " + (p.home?.title() ?: "null"))
        sb.appendLine("Home(vip)      = " + (p.vip?.title() ?: "null"))
        sb.appendLine("Account        = " + (p.account?.summary() ?: "null"))
        sb.appendLine("Account(guest) = " + (p.guest?.summary() ?: "null"))
        sb.appendLine("Channel        = " + (p.channel?.describe() ?: "null"))
        sb.appendLine("Camera         = " + (p.camera?.description() ?: "null (module not installed)"))
        sb.appendLine("Legacy(SPI)    = " + (p.legacy?.describe() ?: "null"))

        sb.appendLine()
        sb.appendLine("── JDK-style Provider API ────")
        val first = ServiceLoader.findFirst(HomeService::class.java)
        sb.appendLine("findFirst = " + (first?.type()?.simpleName ?: "null"))
        ServiceLoader.stream(HomeService::class.java).forEach { provider ->
            sb.appendLine("stream    = ${provider.type().simpleName} / ${provider.state()}")
        }

        return sb.toString()
    }

    private fun AccountService.summary(): String = "${userName()} / ${level()}"

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

    /** Only the log changed; re-render the details pane from the probes already on screen. */
    private fun append(line: String) {
        log.append(line).append('\n')
        runOnUiThread {
            lastProbes?.let { output.text = buildDetails(it) }
        }
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
