package io.github.mimic

import android.app.Activity
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * Shows every icon of one icon pack. Tapping an icon renders it to a PNG and saves it as the
 * custom icon of the chosen app, so it works even if the pack has no entry for that app.
 */
class IconPickerActivity : Activity() {

    private val SIZE = 512

    private lateinit var ui: Ui
    private lateinit var pack: String
    private lateinit var key: String
    private lateinit var res: Resources
    private lateinit var status: TextView
    private lateinit var adapter: Adapter

    private var all: List<String> = emptyList()
    private var shown: List<String> = emptyList()

    private val cache = LruCache<String, Drawable>(400)
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        pack = intent.getStringExtra("pack") ?: return finish()
        key = intent.getStringExtra("key") ?: return finish()
        val packLabel = intent.getStringExtra("packLabel") ?: pack
        val appLabel = intent.getStringExtra("label") ?: ""

        try {
            res = createPackageContext(pack, 0).resources
        } catch (e: Throwable) {
            Toast.makeText(this, "Can't open icon pack", Toast.LENGTH_LONG).show()
            return finish()
        }

        ui = Ui(this)
        val root = ui.fixedScreen("Choose icon") { finish() }

        root.addView(TextView(this).apply {
            text = "$appLabel  ·  $packLabel"
            textSize = 13f
            setTextColor(ui.subColor)
            setPadding(ui.dp(12), 0, ui.dp(12), ui.dp(12))
        })

        adapter = Adapter()
        root.addView(
            ui.searchBox("Search icons") { q ->
                val t = q.trim()
                shown = if (t.isEmpty()) all else all.filter { it.contains(t, ignoreCase = true) }
                adapter.notifyDataSetChanged()
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        status = TextView(this).apply {
            text = "Loading icons…"
            textSize = 13f
            setTextColor(ui.subColor)
            setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(4))
        }
        root.addView(status)

        val grid = GridView(this).apply {
            numColumns = 4
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            verticalSpacing = ui.dp(4)
            clipToPadding = false
            setPadding(0, 0, 0, ui.dp(16))
            selector = android.graphics.drawable.ColorDrawable(0)
            isVerticalScrollBarEnabled = false
            adapter = this@IconPickerActivity.adapter
        }
        root.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // parse the pack's icon list off the main thread, keep only names that really exist
        pool.execute {
            val names = try {
                PackIcons.drawableNames(this, pack)
                    .filter { res.getIdentifier(it, "drawable", pack) != 0 }
                    .distinct()
                    .sorted()
            } catch (e: Throwable) {
                emptyList()
            }
            main.post {
                all = names
                shown = names
                status.text = if (names.isEmpty()) "No icons found in this pack" else "${names.size} icons"
                adapter.notifyDataSetChanged()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pool.shutdownNow()
    }

    private fun show(iv: ImageView, d: Drawable) {
        iv.setImageDrawable(d.constantState?.newDrawable(res) ?: d)
    }

    private fun load(name: String, iv: ImageView) {
        pool.execute {
            try {
                val id = res.getIdentifier(name, "drawable", pack)
                val d = res.getDrawable(id, null) ?: return@execute
                cache.put(name, d)
                main.post { if (iv.tag == name) show(iv, d) }
            } catch (e: Throwable) {
            }
        }
    }

    private fun pick(name: String) {
        pool.execute {
            try {
                val id = res.getIdentifier(name, "drawable", pack)
                val d = res.getDrawable(id, null)
                val dd = d.constantState?.newDrawable(res)?.mutate() ?: d
                val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
                dd.setBounds(0, 0, SIZE, SIZE)
                dd.draw(Canvas(bmp))
                CustomIcons.save(this, key, bmp)
                main.post {
                    Toast.makeText(this, "Saved. Tap Apply changes.", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Throwable) {
                main.post { Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        private fun makeCell() = LinearLayout(this@IconPickerActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(8))
            background = ui.pressable()
            addView(ImageView(this@IconPickerActivity).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)))
            addView(TextView(this@IconPickerActivity).apply {
                textSize = 10f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                setTextColor(ui.subColor)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = ui.dp(4) })
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val cell = (convertView as? LinearLayout) ?: makeCell()
            val iv = cell.getChildAt(0) as ImageView
            val tv = cell.getChildAt(1) as TextView
            val name = shown[position]
            tv.text = name
            iv.tag = name
            val cached = cache.get(name)
            if (cached != null) {
                show(iv, cached)
            } else {
                iv.setImageDrawable(null)
                load(name, iv)
            }
            cell.setOnClickListener { pick(name) }
            return cell
        }
    }
}
