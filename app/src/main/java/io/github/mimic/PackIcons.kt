package io.github.mimic

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/** Runs in the module app's own process (UI side). */
object PackIcons {
    class Pack(val pkg: String, val label: String)

    private val ACTIONS = listOf(
        "org.adw.launcher.THEMES",
        "com.novalauncher.THEME",
        "com.gau.go.launcherex.theme"
    )

    fun installed(pm: PackageManager): List<Pack> =
        ACTIONS
            .flatMap { pm.queryIntentActivities(Intent(it), 0) }
            .map { it.activityInfo.packageName }
            .distinct()
            .map { Pack(it, pm.getApplicationInfo(it, 0).loadLabel(pm).toString()) }

    /**
     * Every icon name an icon pack declares: drawable.xml (the full icon list) plus
     * the drawables referenced by appfilter.xml. Names are not verified here.
     */
    fun drawableNames(ctx: Context, pack: String): List<String> {
        val pc = ctx.createPackageContext(pack, 0)
        val res = pc.resources
        val out = LinkedHashSet<String>()

        fun scan(p: XmlPullParser) {
            while (p.eventType != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.name == "item") {
                    val d = p.getAttributeValue(null, "drawable")
                    if (!d.isNullOrEmpty()) out.add(d)
                }
                p.next()
            }
        }

        for (file in listOf("drawable", "appfilter")) {
            try {
                val xid = res.getIdentifier(file, "xml", pack)
                if (xid != 0) {
                    scan(res.getXml(xid))
                } else {
                    pc.assets.open("$file.xml").use { s ->
                        val p = XmlPullParserFactory.newInstance().newPullParser()
                        p.setInput(s, "utf-8")
                        scan(p)
                    }
                }
            } catch (e: Throwable) {
                // file not present in this pack, skip
            }
        }
        return out.toList()
    }
}
