package io.github.mimic

/**
 * Every name the module depends on, in one place.
 * Used by both the app process and the hook running inside the launcher.
 */
object Config {

    // ---- This module -------------------------------------------------------------------
    /** Always equals the applicationId from build.gradle, so renaming the package never needs edits here. */
    const val SELF = "io.github.mimic"
    /** Must match android:authorities in the manifest (it uses ${applicationId}.packprovider). */
    const val AUTH = "content://$SELF.packprovider"

    const val PREFS_CFG = "cfg"          // pack choice + stamp (module app side)
    const val PREFS_STATUS = "status"    // last hook report (module app side)
    const val PREFS_HOOK = "${SELF}_hook" // regen stamp (launcher side)

    // ---- Target launcher ---------------------------------------------------------------
    const val LAUNCHER = "com.bbk.launcher2"

    /**
     * Obfuscated names inside the launcher. They change when vivo updates the launcher.
     * If the Status card says "Launcher classes not found", this is the only block to update.
     */
    const val ICON_MANAGER = "com.bbk.launcher2.data.iconcache.IconManager"
    const val PROC_CLASS = "d6.c"        // builds grid / drawer bitmaps
    const val CACHE_CLASS = "s0.c"       // icon cache
    const val M_BITMAP = "B"             // PROC_CLASS: Bitmap builder
    const val M_DRAWABLE = "J"           // CACHE_CLASS: workspace Drawable
    const val M_BLOB = "V"               // CACHE_CLASS: cached icon bytes
    const val M_HOTSEAT = "getExploreHotseatIcon" // ICON_MANAGER
    const val M_ITEM_COMPONENT = "L"     // hotseat item -> ComponentName
    const val M_ITEM_PACKAGE = "F"       // hotseat item -> package String
}
