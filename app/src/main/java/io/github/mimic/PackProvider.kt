package io.github.mimic

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import java.io.FileNotFoundException

class PackProvider : ContentProvider() {
    override fun onCreate() = true

    // columns: pack, stamp, custom (newline-separated "package/activity" keys that have a custom icon)
    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?
    ): Cursor {
        val ctx = context!!
        val sp = ctx.getSharedPreferences(Config.PREFS_CFG, 0)
        val pack = sp.getString("pack", "") ?: ""
        val stamp = sp.getLong("stamp", 0L).toString()
        val custom = CustomIcons.keys(ctx).joinToString("\n")
        return MatrixCursor(arrayOf("pack", "stamp", "custom")).apply {
            addRow(arrayOf(pack, stamp, custom))
        }
    }

    // content://io.github.mimic.packprovider/icon?c=<package>/<activity>
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val key = uri.getQueryParameter("c") ?: throw FileNotFoundException("no key")
        val f = CustomIcons.file(context!!, key)
        if (!f.exists()) throw FileNotFoundException(key)
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    // The launcher hook reports here once its hooks are installed:
    // call("hooked", extras = pid, ok, info)
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method == "hooked" && extras != null) {
            context!!.getSharedPreferences(Config.PREFS_STATUS, 0).edit()
                .putInt("pid", extras.getInt("pid"))
                .putLong("time", System.currentTimeMillis())
                .putBoolean("ok", extras.getBoolean("ok"))
                .putString("info", extras.getString("info"))
                .apply()
        }
        return null
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0
}
