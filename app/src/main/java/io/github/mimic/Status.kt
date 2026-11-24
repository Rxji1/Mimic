package io.github.mimic

import android.content.Context

/** Runs in the module app's own process only (UI + PackProvider). */
object Status {
    const val LAUNCHER = Config.LAUNCHER

    /** Last report sent by the hook running inside the launcher. */
    class Hook(val pid: Int, val time: Long, val ok: Boolean, val info: String)

    class Snapshot(
        val root: Boolean,
        val moduleActive: Boolean,
        val hook: Hook?,        // null = launcher never reported
        val launcherPid: Int    // 0 = not running / unknown (no root)
    )

    private fun su(cmd: String): String? = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        out
    } catch (e: Throwable) {
        null
    }

    /** Blocking: call from a background thread. */
    fun read(ctx: Context, selfHooked: Boolean): Snapshot {
        val root = su("id")?.contains("uid=0") == true
        val pid = if (root) {
            su("pidof $LAUNCHER")?.split(" ")?.firstOrNull()?.toIntOrNull() ?: 0
        } else 0

        val sp = ctx.getSharedPreferences(Config.PREFS_STATUS, 0)
        val time = sp.getLong("time", 0L)
        val hook = if (time == 0L) null
        else Hook(
            sp.getInt("pid", 0),
            time,
            sp.getBoolean("ok", false),
            sp.getString("info", "") ?: ""
        )
        // If the launcher ever reported, the module is certainly loaded by Vector
        return Snapshot(root, selfHooked || hook != null, hook, pid)
    }
}
