package io.github.mimic

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import java.io.File
import java.io.FileOutputStream

/** Runs in the module app's own process only (UI + PackProvider). */
object CustomIcons {
    private const val FLAGS = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP

    /** Key = "package/activity", same shape the launcher hook sees. */
    fun key(pkg: String, act: String) = "$pkg/$act"

    private fun dir(ctx: Context) = File(ctx.filesDir, "custom").apply { mkdirs() }

    private fun fileName(key: String) =
        Base64.encodeToString(key.toByteArray(), FLAGS) + ".png"

    fun file(ctx: Context, key: String) = File(dir(ctx), fileName(key))

    fun has(ctx: Context, key: String) = file(ctx, key).exists()

    fun keys(ctx: Context): List<String> =
        dir(ctx).listFiles()
            ?.filter { it.name.endsWith(".png") }
            ?.mapNotNull {
                try {
                    String(Base64.decode(it.name.removeSuffix(".png"), FLAGS))
                } catch (e: Exception) {
                    null
                }
            } ?: emptyList()

    fun save(ctx: Context, key: String, bmp: Bitmap) {
        FileOutputStream(file(ctx, key)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bump(ctx)
    }

    fun remove(ctx: Context, key: String) {
        file(ctx, key).delete()
        bump(ctx)
    }

    /** Changing the stamp makes the launcher hook drop its cached icons on next start. */
    private fun bump(ctx: Context) {
        ctx.getSharedPreferences(Config.PREFS_CFG, 0).edit()
            .putLong("stamp", System.currentTimeMillis())
            .apply()
    }

    fun restartLauncher() {
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "am force-stop ${Config.LAUNCHER}"))
        } catch (e: Exception) {
        }
    }
}