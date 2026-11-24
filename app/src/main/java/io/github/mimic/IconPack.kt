package io.github.mimic

import android.app.AndroidAppHelper
import android.content.res.Resources
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import de.robv.android.xposed.XposedBridge
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/** Runs inside the launcher process (loaded by the Xposed hook). */
object IconPack {

    private var pack = ""
    private var stamp = "0"
    private var res: Resources? = null
    private var byComponent: Map<String, String> = emptyMap()
    private var byPackage: Map<String, String> = emptyMap()
    private var custom: Set<String> = emptySet()
    private val customCache = HashMap<String, Drawable>()
    private var ready = false
    private var lastTry = 0L

    @Synchronized
    fun stamp(): String { ensure(); return stamp }

    @Synchronized
    fun get(pkg: String, act: String?): Drawable? {
        ensure()

        // 1) user-chosen custom icon wins
        if (act != null) {
            customIcon("$pkg/$act")?.let { return it }
        }

        // 2) icon pack
        val r = res ?: return null
        val name = (if (act != null) byComponent["$pkg/$act"] else null)
            ?: byPackage[pkg] ?: return null
        val id = r.getIdentifier(name, "drawable", pack)
        if (id == 0) return null
        return try { r.getDrawable(id, null) } catch (e: Throwable) { null }
    }

    private fun customIcon(key: String): Drawable? {
        if (key !in custom) return null
        customCache[key]?.let { return it }
        val ctx = AndroidAppHelper.currentApplication() ?: return null
        return try {
            val uri = Uri.parse("${Config.AUTH}/icon").buildUpon().appendQueryParameter("c", key).build()
            val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                ?: return null
            val d = BitmapDrawable(ctx.resources, bmp)
            customCache[key] = d
            d
        } catch (e: Throwable) {
            XposedBridge.log("ICONPACK custom load error $key: $e")
            null
        }
    }

    private fun ensure() {
        if (ready) return
        val now = System.currentTimeMillis()
        if (now - lastTry < 5000) return
        lastTry = now
        try { init() } catch (e: Throwable) {
            XposedBridge.log("ICONPACK init error: $e")
        }
    }

    private fun init() {
        val ctx = AndroidAppHelper.currentApplication()
        val cur = ctx.contentResolver.query(
            Uri.parse("${Config.AUTH}/pack"),
            null, null, null, null
        ) ?: throw IllegalStateException("provider not found")
        cur.use { c ->
            if (c.moveToFirst()) {
                pack = c.getString(0) ?: ""
                stamp = c.getString(1) ?: "0"
                custom = if (c.columnCount > 2) {
                    (c.getString(2) ?: "").split('\n').filter { k -> k.isNotEmpty() }.toSet()
                } else emptySet()
            }
        }
        customCache.clear()
        XposedBridge.log("ICONPACK custom icons=${custom.size}")

        if (pack.isEmpty()) {
            XposedBridge.log("ICONPACK no pack selected, stamp=$stamp")
            res = null; ready = true; return
        }
        val pc = ctx.createPackageContext(pack, 0)
        res = pc.resources
        val comp = HashMap<String, String>()
        val pk = HashMap<String, String>()
        val xid = res!!.getIdentifier("appfilter", "xml", pack)
        val p: XmlPullParser = if (xid != 0) res!!.getXml(xid)
        else XmlPullParserFactory.newInstance().newPullParser().apply {
            setInput(pc.assets.open("appfilter.xml"), "utf-8")
        }
        while (p.eventType != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == "item") {
                val c = p.getAttributeValue(null, "component")
                val d = p.getAttributeValue(null, "drawable")
                if (c != null && d != null) {
                    val key = c.removePrefix("ComponentInfo{").removeSuffix("}")
                    comp[key] = d
                    pk.putIfAbsent(key.substringBefore('/'), d)
                }
            }
            p.next()
        }
        byComponent = comp
        byPackage = pk
        ready = true
        XposedBridge.log("ICONPACK pack=$pack entries=${comp.size} stamp=$stamp")
    }
}