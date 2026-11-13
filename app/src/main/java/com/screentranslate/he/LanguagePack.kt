package com.screentranslate.he

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Puts the Google translation languages that ship inside the APK
 * (`assets/languages.zip`) where ML Kit expects them, on first launch and whenever the
 * bundled pack changes.
 *
 * Google's ML Kit has no official way to ship its language models inside an app. The pack is a
 * copy of the app's private data folder after the languages were downloaded once (models plus
 * ML Kit's own record of which models are installed). Writing it back and restarting the app makes
 * ML Kit find them as if it had downloaded them itself.
 */
object LanguagePack {

    const val BUNDLED_ASSET = "languages.zip"

    private const val MARKER = "ScreenTranslator-languages.txt"
    private const val PREFS = "language_pack"
    private const val KEY_BUNDLED = "bundled_size"

    /** Paths (relative to the data folder) that never go into a pack and are never overwritten. */
    private val SKIP = listOf(
        "cache", "code_cache", "lib", "app_textures", "app_webview",
        "files/tesseract",                 // copied from the APK's own assets
        "shared_prefs/settings.xml",       // this app's settings (OCR mode)
        "shared_prefs/$PREFS.xml",         // which bundled pack was installed
    )

    private fun skipped(path: String) = SKIP.any { path == it || path.startsWith("$it/") }

    private fun root(context: Context): File = context.dataDir.canonicalFile

    // ------------------------------------------------------------------ import

    /**
     * Restores a pack. Throws [IllegalArgumentException] when [input] is not one.
     * Call [restart] afterwards.
     */
    fun import(context: Context, input: InputStream) {
        val root = root(context)
        val rootPrefix = root.path + File.separator
        ZipInputStream(input.buffered()).use { zip ->
            val first = zip.nextEntry
            require(first != null && first.name == MARKER) { "זה לא קובץ שפות של האפליקציה" }
            val owner = zip.readBytes().toString(Charsets.UTF_8).lineSequence().firstOrNull()?.trim()
            require(owner == context.packageName) { "הקובץ נוצר בגרסה אחרת של האפליקציה" }

            while (true) {
                val entry = zip.nextEntry ?: break
                val path = entry.name.replace('\\', '/').trimStart('/')
                if (entry.isDirectory || path.isEmpty() || skipped(path)) continue
                val target = File(root, path).canonicalFile
                require(target.path.startsWith(rootPrefix)) { "קובץ השפות פגום" } // no ../ escapes
                target.parentFile?.mkdirs()
                val temp = File(target.path + ".importing")
                temp.outputStream().use { zip.copyTo(it) }
                if (!temp.renameTo(target)) {
                    target.delete()
                    check(temp.renameTo(target)) { "לא הצלחתי לכתוב את $path" }
                }
                if (entry.time > 0) target.setLastModified(entry.time)
            }
        }
    }

    // ------------------------------------------------------------------ bundled pack

    private fun bundledSize(context: Context): Long =
        try {
            context.assets.openFd(BUNDLED_ASSET).use { it.length }
        } catch (e: Exception) {
            -1L // no pack in this APK
        }

    /** True when the APK carries a language pack that has not been installed yet. */
    fun bundledPending(context: Context): Boolean {
        val size = bundledSize(context)
        if (size <= 0) return false
        val done = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_BUNDLED, -1L)
        return done != size
    }

    /**
     * Installs the pack inside the APK. Returns true when it was recorded as installed; only then
     * call [restart] (otherwise every launch would install and restart again).
     */
    fun installBundled(context: Context): Boolean {
        val size = bundledSize(context)
        context.assets.open(BUNDLED_ASSET).use { import(context, it) }
        // commit(), not apply(): the process is killed right after this.
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_BUNDLED, size).commit()
    }

    /** Starts the app fresh, so ML Kit reloads the restored languages. */
    fun restart(activity: Activity) {
        val intent = Intent.makeRestartActivityTask(ComponentName(activity, MainActivity::class.java))
        activity.startActivity(intent)
        Runtime.getRuntime().exit(0)
    }
}
