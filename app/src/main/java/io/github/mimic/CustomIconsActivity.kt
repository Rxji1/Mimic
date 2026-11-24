package io.github.mimic

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.widget.Toast

class CustomIconsActivity : Activity() {

    private class App(val label: String, val pkg: String, val act: String, val icon: Drawable) {
        val key get() = CustomIcons.key(pkg, act)
    }

    private val SIZE = 512
    private val REQ_PICK = 1

    private lateinit var ui: Ui
    private lateinit var screen: Ui.Screen
    private var apps: List<App> = emptyList()
    private val customCache = HashMap<String, Drawable?>()
    private var pendingKey: String? = null
    private var created = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        pendingKey = b?.getString("pendingKey")

        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        apps = pm.queryIntentActivities(intent, 0)
            .map {
                App(
                    it.loadLabel(pm).toString(),
                    it.activityInfo.packageName,
                    it.activityInfo.name,
                    it.loadIcon(pm)
                )
            }
            .sortedBy { it.label.lowercase() }

        ui = Ui(this)
        screen = ui.screen(
            title = "Custom icons",
            onBack = { finish() },
            bottomText = "Apply changes",
            onBottom = {
                CustomIcons.restartLauncher()
                Toast.makeText(this, "Restarting launcher…", Toast.LENGTH_SHORT).show()
            }
        )
        render()
        created = true
    }

    override fun onResume() {
        super.onResume()
        // coming back from the icon pack picker: re-read saved icons
        if (created) {
            customCache.clear()
            render()
        }
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString("pendingKey", pendingKey)
    }

    private fun render() {
        val y = screen.scroll.scrollY
        screen.list.removeAllViews()

        ui.section(screen.list, "TAP AN APP TO CHOOSE AN ICON")
        val card = ui.card(screen.list)
        apps.forEach { app ->
            val has = CustomIcons.has(this, app.key)
            ui.row(
                card,
                title = app.label,
                summary = if (has) "Custom icon set" else null,
                summaryAccent = true,
                icon = iconFor(app),
                trailing = "›"
            ) { showOptions(app) }
        }

        screen.scroll.post { screen.scroll.scrollTo(0, y) }
    }

    private fun showOptions(app: App) {
        val has = CustomIcons.has(this, app.key)
        val items = mutableListOf("Choose from icon pack", "Choose photo from gallery")
        if (has) items.add("Remove custom icon")
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> chooseFromPack(app)
                    1 -> pickPhoto(app)
                    else -> {
                        CustomIcons.remove(this, app.key)
                        customCache.remove(app.key)
                        render()
                        Toast.makeText(this, "Removed. Tap Apply changes.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun chooseFromPack(app: App) {
        val packs = PackIcons.installed(packageManager)
        if (packs.isEmpty()) {
            Toast.makeText(this, "No icon packs installed", Toast.LENGTH_SHORT).show()
            return
        }
        val active = getSharedPreferences(Config.PREFS_CFG, 0).getString("pack", "") ?: ""
        val sorted = packs.sortedByDescending { it.pkg == active }
        if (sorted.size == 1) {
            openPicker(app, sorted[0])
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Icon pack")
            .setItems(
                sorted.map { if (it.pkg == active) "${it.label} (active)" else it.label }.toTypedArray()
            ) { _, i -> openPicker(app, sorted[i]) }
            .show()
    }

    private fun openPicker(app: App, p: PackIcons.Pack) {
        startActivity(
            Intent(this, IconPickerActivity::class.java)
                .putExtra("pack", p.pkg)
                .putExtra("packLabel", p.label)
                .putExtra("key", app.key)
                .putExtra("label", app.label)
        )
    }

    private fun pickPhoto(app: App) {
        pendingKey = app.key
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_PICK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val key = pendingKey ?: return
        Thread {
            try {
                val bmp = loadSquare(uri)
                CustomIcons.save(this, key, bmp)
                runOnUiThread {
                    customCache.remove(key)
                    render()
                    Toast.makeText(this, "Saved. Tap Apply changes.", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** Center-crop to a square and scale to SIZE x SIZE. */
    private fun loadSquare(uri: Uri): Bitmap {
        val src = ImageDecoder.createSource(contentResolver, uri)
        val bmp = ImageDecoder.decodeBitmap(src) { dec, info, _ ->
            dec.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
            val w = info.size.width
            val h = info.size.height
            val m = minOf(w, h)
            if (m > SIZE * 2) {
                val s = (SIZE * 2).toFloat() / m
                dec.setTargetSize(
                    (w * s).toInt().coerceAtLeast(1),
                    (h * s).toInt().coerceAtLeast(1)
                )
            }
        }
        val side = minOf(bmp.width, bmp.height)
        val crop = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
        return Bitmap.createScaledBitmap(crop, SIZE, SIZE, true)
    }

    private fun iconFor(app: App): Drawable {
        if (!CustomIcons.has(this, app.key)) return app.icon
        return customCache.getOrPut(app.key) {
            val bmp = BitmapFactory.decodeFile(CustomIcons.file(this, app.key).path)
            if (bmp != null) BitmapDrawable(resources, bmp) else null
        } ?: app.icon
    }
}
