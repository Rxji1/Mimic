package io.github.mimic

import android.app.AndroidAppHelper
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Modifier

class MainHook : IXposedHookLoadPackage {
    private var bLogs = 0
    private var vLogs = 0
    private var jLogs = 0
    private var hsLogs = 0
    private var diagLogs = 0

    // Icon edge length the launcher uses (B returns 280x280 on this device); learned from B at runtime
    @Volatile private var iconPx = 280

    // Decision for this process, computed exactly once (null = not decided yet)
    private var regen: Boolean? = null
    private val regenLock = Any()

    companion object {
        // DIAGNOSTIC: set to false once everything is confirmed working.
        private const val DIAG = false

        // Only calls whose arguments mention one of these texts are logged by the diagnostic.
        private val DIAG_KEYS = listOf("facebook", "android.vending", "bin.mt.plus")

    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Our own app: make MainActivity.isModuleActive() return true so the UI can show "Active"
        if (lpparam.packageName == Config.SELF) {
            try {
                XposedHelpers.findAndHookMethod(
                    MainActivity::class.java.name, lpparam.classLoader, "isModuleActive",
                    XC_MethodReplacement.returnConstant(true)
                )
            } catch (e: Throwable) {
                XposedBridge.log("ICONPACK self hook error: $e")
            }
            return
        }

        if (lpparam.packageName != Config.LAUNCHER) return
        if (lpparam.processName != Config.LAUNCHER) return

        val proc = XposedHelpers.findClassIfExists(Config.PROC_CLASS, lpparam.classLoader)
        val cache = XposedHelpers.findClassIfExists(Config.CACHE_CLASS, lpparam.classLoader)
        if (proc == null || cache == null) {
            XposedBridge.log("ICONPACK class missing proc=$proc cache=$cache")
            report(false, "Launcher classes not found (launcher updated?)")
            return
        }

        // Grid / drawer path: swap the bitmap when the launcher builds it
        XposedBridge.hookAllMethods(proc, Config.M_BITMAP, object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                try {
                    val res = p.result as? Bitmap ?: return
                    if (res.width > 0) iconPx = res.width
                    val s = p.args.filterIsInstance<String>()
                    if (s.isEmpty()) return
                    val d = IconPack.get(s[0], s.getOrNull(1))
                    if (bLogs < 10) {
                        bLogs++
                        XposedBridge.log("ICONPACK B ${s[0]}/${s.getOrNull(1)} found=${d != null}")
                    }
                    if (d == null) return
                    p.result = render(d, res.width, res.height)
                } catch (e: Throwable) {
                    XposedBridge.log("ICONPACK B error: $e")
                }
            }
        })

        // Workspace Drawable path: IconManager.getDrawable -> s0.c.L -> s0.c.J -> s0.c.x
        // (IconManager.getDrawable is a plain pass-through to s0.c.L).
        val jn = XposedBridge.hookAllMethods(cache, Config.M_DRAWABLE, object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                try {
                    p.result as? Drawable ?: return
                    val cn = p.args.filterIsInstance<Intent>().firstOrNull()?.component ?: return
                    val d = IconPack.get(cn.packageName, cn.className)
                    if (jLogs < 10) {
                        jLogs++
                        XposedBridge.log("ICONPACK J ${cn.flattenToShortString()} found=${d != null}")
                    }
                    if (d == null) return

                    // Must match the launcher's own icon size: the stock drawable here is ~1008px, and
                    // vivo's IconRedrawManager rejects a drawable that is not iconPx x iconPx and falls
                    // back to the original app icon.
                    val res = AndroidAppHelper.currentApplication().resources
                    p.result = BitmapDrawable(res, render(d, iconPx, iconPx))
                } catch (e: Throwable) {
                    XposedBridge.log("ICONPACK J error: $e")
                }
            }
        }).size

        // Dock / hotseat path. MorphItemIcon.X4 does NOT use the Drawable from getDrawable for items in
        // container -101; it calls IconManager.getExploreHotseatIcon(item, flag, iconStyle) and wraps the
        // returned Bitmap in a new y5.y. So swap the bitmap here.
        val hn = hookHotseat(lpparam.classLoader)

        // After a pack change, drop cached icon bytes so icons are rebuilt
        val n = XposedBridge.hookAllMethods(cache, Config.M_BLOB, object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                try {
                    val cn = p.args.getOrNull(1) as? ComponentName
                    val blob = p.args.getOrNull(2) as? ByteArray
                    val need = shouldRegen()
                    if (vLogs < 10) {
                        vLogs++
                        XposedBridge.log("ICONPACK V ${cn?.flattenToString()} blob=${blob?.size} regen=$need")
                    }
                    if (need && blob != null) p.args[2] = null
                } catch (e: Throwable) {
                    XposedBridge.log("ICONPACK V error: $e")
                }
            }
        }).size
        XposedBridge.log("ICONPACK hooks installed, J overloads=$jn, hotseat overloads=$hn, V overloads=$n")

        // Tell the module app that the launcher is hooked (shown in the Status card)
        val ok = jn > 0 && hn > 0 && n > 0
        report(ok, "J=$jn, hotseat=$hn, V=$n")

        if (DIAG) installDiag(proc, cache)
    }

    /**
     * Sends this launcher process' PID and hook result to the module app's PackProvider.
     * Delayed so the Application object exists; retried a few times if it does not yet.
     */
    private fun report(ok: Boolean, info: String, tries: Int = 0) {
        Handler(Looper.getMainLooper()).postDelayed({
            val app = AndroidAppHelper.currentApplication()
            if (app == null) {
                if (tries < 10) report(ok, info, tries + 1)
                return@postDelayed
            }
            Thread {
                try {
                    app.contentResolver.call(
                        Uri.parse(Config.AUTH),
                        "hooked", null,
                        Bundle().apply {
                            putInt("pid", Process.myPid())
                            putBoolean("ok", ok)
                            putString("info", info)
                        }
                    )
                } catch (e: Throwable) {
                    XposedBridge.log("ICONPACK report error: $e")
                }
            }.start()
        }, 3000L)
    }

    /**
     * Hooks IconManager.getExploreHotseatIcon(i item, boolean, int): Bitmap.
     * The stock result has already been scaled by covertForChangeDensity to the hotseat icon size
     * (LauncherEnvironmentManager.W1()), so the replacement copies the stock bitmap's width, height
     * and density. The item class is obfuscated: L() returns its ComponentName, F() its package.
     */
    private fun hookHotseat(cl: ClassLoader): Int {
        val im = XposedHelpers.findClassIfExists(Config.ICON_MANAGER, cl)
        if (im == null) {
            XposedBridge.log("ICONPACK IconManager missing")
            return 0
        }
        return XposedBridge.hookAllMethods(im, Config.M_HOTSEAT, object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                try {
                    val item = p.args.getOrNull(0) ?: return
                    val cn = try {
                        XposedHelpers.callMethod(item, Config.M_ITEM_COMPONENT) as? ComponentName
                    } catch (e: Throwable) {
                        null
                    }
                    val pkg = cn?.packageName ?: try {
                        XposedHelpers.callMethod(item, Config.M_ITEM_PACKAGE) as? String
                    } catch (e: Throwable) {
                        null
                    } ?: return

                    val d = IconPack.get(pkg, cn?.className)
                    if (hsLogs < 10) {
                        hsLogs++
                        XposedBridge.log("ICONPACK HS $pkg/${cn?.className} found=${d != null}")
                    }
                    if (d == null) return

                    val stock = p.result as? Bitmap
                    val w = stock?.width?.takeIf { it > 0 } ?: iconPx
                    val h = stock?.height?.takeIf { it > 0 } ?: iconPx
                    val bmp = render(d, w, h)
                    if (stock != null) bmp.density = stock.density
                    p.result = bmp
                } catch (e: Throwable) {
                    XposedBridge.log("ICONPACK HS error: $e")
                }
            }
        }).size
    }

    /**
     * DIAGNOSTIC ONLY. Lists every method of the icon classes that returns a Bitmap, Drawable
     * or byte[], then logs each call whose arguments mention a DIAG_KEY together with a short
     * stack trace.
     */
    private fun installDiag(proc: Class<*>, cache: Class<*>) {
        var hooked = 0
        for (c in listOf(proc, cache)) {
            for (m in c.declaredMethods) {
                val rt = m.returnType
                val interesting = rt == Bitmap::class.java ||
                        Drawable::class.java.isAssignableFrom(rt) ||
                        rt == ByteArray::class.java
                if (!interesting || Modifier.isAbstract(m.modifiers)) continue
                val sig = "${c.name}.${m.name}(${m.parameterTypes.joinToString { it.simpleName }}): ${rt.simpleName}"
                XposedBridge.log("ICONPACK DIAG method $sig")
                try {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun afterHookedMethod(p: MethodHookParam) {
                            try {
                                if (diagLogs >= 250) return
                                val desc = describeArgs(p.args)
                                if (DIAG_KEYS.none { desc.contains(it) }) return
                                diagLogs++
                                val r = p.result
                                val rs = when (r) {
                                    is Bitmap -> "Bitmap ${r.width}x${r.height}"
                                    is ByteArray -> "bytes ${r.size}"
                                    null -> "null"
                                    else -> r.javaClass.name
                                }
                                val stack = Thread.currentThread().stackTrace
                                    .drop(3).take(12)
                                    .joinToString(" < ") { "${it.className}.${it.methodName}" }
                                XposedBridge.log("ICONPACK DIAG call $sig args=[$desc] -> $rs\n    stack: $stack")
                            } catch (e: Throwable) {
                                // never break the launcher because of diagnostics
                            }
                        }
                    })
                    hooked++
                } catch (e: Throwable) {
                    XposedBridge.log("ICONPACK DIAG hook failed $sig: $e")
                }
            }
        }
        XposedBridge.log("ICONPACK DIAG hooked $hooked methods")
    }

    private fun describeArgs(args: Array<Any?>): String {
        val sb = StringBuilder()
        for (a in args) {
            val s = when (a) {
                null -> "null"
                is String -> a
                is ComponentName -> a.flattenToString()
                is Intent -> a.toString()
                is Number, is Boolean -> a.toString()
                else -> {
                    val cn = a.javaClass.name
                    if (cn.startsWith("android.") || cn.startsWith("java.") ||
                        cn.startsWith("kotlin.") || cn.startsWith("androidx.")
                    ) cn
                    else try { a.toString().take(200) } catch (e: Throwable) { cn }
                }
            }
            if (sb.isNotEmpty()) sb.append(", ")
            sb.append(s)
        }
        return sb.toString()
    }

    /**
     * Thread-safe. Decides once per process whether cached icon blobs must be dropped.
     * The stamp is saved to prefs only after a delay, so if the launcher is killed
     * before it finishes rebuilding all icons, the next start regenerates again.
     */
    private fun shouldRegen(): Boolean = synchronized(regenLock) {
        regen?.let { return it }

        val ctx = AndroidAppHelper.currentApplication() ?: return false
        val cur = IconPack.stamp()
        // "0" = provider not read yet (init failed / not ready); don't decide or cache
        if (cur == "0") return false

        val prefs = ctx.getSharedPreferences(Config.PREFS_HOOK, 0)
        val old = prefs.getString("stamp", null)
        val need = old != cur
        regen = need
        XposedBridge.log("ICONPACK regen=$need stamp=$cur old=$old")

        if (need) {
            Handler(Looper.getMainLooper()).postDelayed({
                prefs.edit().putString("stamp", cur).apply()
                XposedBridge.log("ICONPACK stamp committed $cur")
            }, 60_000L)
        }
        need
    }

    private fun render(src: Drawable, w: Int, h: Int): Bitmap {
        val d = src.constantState?.newDrawable()?.mutate() ?: src
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, w, h)
        d.draw(Canvas(bmp))
        return bmp
    }
}
