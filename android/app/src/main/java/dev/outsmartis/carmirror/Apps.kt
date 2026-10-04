package dev.outsmartis.carmirror

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.Base64
import java.io.ByteArrayOutputStream

data class LaunchableApp(val pkg: String, val label: String)

/** Launchable apps on the phone, and which of them the car shows. */
object Apps {
    /** Shown in the car by default, when installed. */
    private val DEFAULT_FAVORITES = listOf(
        "com.outsmartis.conductore",
        "com.google.android.youtube",
        "com.google.android.apps.youtube.music",
        "com.waze",
        "com.google.android.apps.maps",
        "com.spotify.music",
        "com.whatsapp",
    )

    fun launchable(context: Context): List<LaunchableApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { LaunchableApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.pkg != context.packageName }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }

    fun favorites(context: Context, prefs: Prefs): List<LaunchableApp> {
        val all = launchable(context)
        val chosen = prefs.favorites ?: DEFAULT_FAVORITES.toSet()
        val byPkg = all.associateBy { it.pkg }
        // keep the default order for defaults, alphabetical for the rest
        val ordered = DEFAULT_FAVORITES.filter { it in chosen } + chosen.filter { it !in DEFAULT_FAVORITES }.sortedBy { byPkg[it]?.label?.lowercase() }
        return ordered.mapNotNull { byPkg[it] }
    }

    fun setFavorite(prefs: Prefs, current: List<LaunchableApp>, pkg: String, on: Boolean) {
        val set = (prefs.favorites ?: current.map { it.pkg }.toSet()).toMutableSet()
        if (on) set += pkg else set -= pkg
        prefs.favorites = set
    }

    fun iconPngBase64(context: Context, pkg: String, sizePx: Int = 96): String? = try {
        val d: Drawable = context.packageManager.getApplicationIcon(pkg)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, sizePx, sizePx)
        d.draw(c)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    } catch (_: Exception) {
        null
    }
}
