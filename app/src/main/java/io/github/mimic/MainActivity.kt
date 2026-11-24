package io.github.mimic

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Toast

class MainActivity : Activity() {

    private class Pack(val pkg: String, val label: String, val icon: Drawable)

    private val GREEN = 0xFF2BB673.toInt()
    private val RED = 0xFFE5484D.toInt()
    private val GREY = 0xFF8A8A8A.toInt()
    private val AMBER = 0xFFF5A623.toInt()

    private lateinit var ui: Ui
    private lateinit var screen: Ui.Screen
    private var packs: List<Pack> = emptyList()
    private var snap: Status.Snapshot? = null

    /**
     * The Xposed hook replaces this with `true` when the module is loaded into this app
     * (needs io.github.mimic ticked in Vector's scope). Keep it a plain method.
     */
    @Suppress("FunctionOnlyReturningConstant")
    fun isModuleActive(): Boolean = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val pm = packageManager
        val actions = listOf(
            "org.adw.launcher.THEMES",
            "com.novalauncher.THEME",
            "com.gau.go.launcherex.theme"
        )
        packs = actions
            .flatMap { pm.queryIntentActivities(Intent(it), 0) }
            .map { it.activityInfo.packageName }
            .distinct()
            .map {
                val ai = pm.getApplicationInfo(it, 0)
                Pack(it, ai.loadLabel(pm).toString(), ai.loadIcon(pm))
            }

        ui = Ui(this)
        screen = ui.screen("Icons")
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::screen.isInitialized) {
            render() // refresh custom icon count after coming back
            refreshStatus()
        }
    }

    private fun refreshStatus() {
        val active = isModuleActive()
        Thread {
            val s = Status.read(this, active)
            runOnUiThread {
                if (!isDestroyed) {
                    snap = s
                    render()
                }
            }
        }.start()
    }

    private fun render() {
        val y = screen.scroll.scrollY
        val list: LinearLayout = screen.list
        list.removeAllViews()

        statusCard(list)

        val current = getSharedPreferences(Config.PREFS_CFG, 0).getString("pack", "") ?: ""
        val customCount = CustomIcons.keys(this).size

        ui.section(list, "ICONS")
        val top = ui.card(list)
        ui.row(
            top,
            title = "Custom app icons",
            summary = if (customCount == 0) "Use your own photo as an app icon"
            else "$customCount custom icon${if (customCount == 1) "" else "s"} set",
            summaryAccent = customCount > 0,
            trailing = "›"
        ) { startActivity(Intent(this, CustomIconsActivity::class.java)) }

        ui.section(list, "ICON PACK")
        val card = ui.card(list)
        ui.row(
            card,
            title = "Default (no icon pack)",
            trailing = if (current.isEmpty()) "✓" else null,
            trailingAccent = true
        ) { choose("", "Default") }

        packs.forEach { p ->
            ui.row(
                card,
                title = p.label,
                icon = p.icon,
                trailing = if (current == p.pkg) "✓" else null,
                trailingAccent = true
            ) { choose(p.pkg, p.label) }
        }

        screen.scroll.post { screen.scroll.scrollTo(0, y) }
    }

    private fun statusCard(list: LinearLayout) {
        ui.section(list, "STATUS")
        val card = ui.card(list)
        val s = snap
        if (s == null) {
            ui.row(card, "Checking status…", trailing = "●", trailingColor = GREY) {}
            return
        }

        ui.row(
            card, "Root access",
            summary = if (s.root) "Granted" else "Not granted (needed to apply changes)",
            trailing = "●",
            trailingColor = if (s.root) GREEN else RED
        ) {}

        ui.row(
            card, "Xposed module",
            summary = if (s.moduleActive) "Active" else "Not active. Enable it in Vector",
            trailing = "●",
            trailingColor = if (s.moduleActive) GREEN else RED
        ) {}

        val h = s.hook
        val (text, color) = when {
            h == null -> "No report from launcher yet. Enable scope, then Apply changes" to RED
            !h.ok -> "Hook failed: ${h.info}" to RED
            !s.root -> "Hooked earlier (pid ${h.pid}); root needed to verify" to AMBER
            s.launcherPid == 0 -> "Launcher not running" to GREY
            s.launcherPid == h.pid -> "Hooked · pid ${h.pid} · ${h.info}" to GREEN
            else -> "Launcher running without hook. Check scope, then Apply changes" to RED
        }
        ui.row(
            card, "Launcher hook (${Status.LAUNCHER})",
            summary = text,
            trailing = "●",
            trailingColor = color
        ) {}
    }

    private fun choose(pkg: String, label: String) {
        getSharedPreferences(Config.PREFS_CFG, 0).edit()
            .putString("pack", pkg)
            .putLong("stamp", System.currentTimeMillis())
            .apply()
        CustomIcons.restartLauncher()
        Toast.makeText(this, "Applied: $label", Toast.LENGTH_SHORT).show()
        render()
    }
}
