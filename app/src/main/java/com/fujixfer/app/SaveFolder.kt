package com.fujixfer.app

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.util.Locale

/**
 * Where downloaded files go in the phone's storage, chosen in "Impostazioni › Cartella di salvataggio".
 *
 * By default photos, RAW and videos go into one folder (Pictures/Fujifilm). The media store only takes some
 * top-level folders for each kind of file (images: DCIM or Pictures; videos: DCIM, Movies and, from Android 11,
 * Pictures; anything else: Download), so RAW files are stored as images (image/x-fuji-raf), and a file a folder
 * refuses falls back to the folder for its type ([split]).
 */
object SaveFolder {
    private const val PREFS = "fuji"
    private const val PREF_BASE = "save_base"
    private const val PREF_NAME = "save_name"
    /** Every folder files were saved to: the uploads still find the files saved before a change. */
    private const val PREF_USED = "save_used"

    const val BASE_PICTURES = "Pictures"
    const val BASE_DCIM = "DCIM"
    const val BASE_DOWNLOAD = "Download"
    /** Photos in Pictures, videos in Movies, RAW and the rest in Download (how the app saved before). */
    const val BASE_SPLIT = "split"
    val BASES = listOf(BASE_PICTURES, BASE_DCIM, BASE_DOWNLOAD, BASE_SPLIT)

    const val DEFAULT_NAME = "Fujifilm"

    /** Folders of the versions before the setting: their files stay where they are. */
    private val LEGACY = listOf("Pictures/Fujifilm", "Movies/Fujifilm", "Download/Fujifilm")

    data class Choice(val base: String, val name: String)

    /** Where a file is stored: media store collection, folder (RELATIVE_PATH), MIME type. */
    data class Target(val collection: Uri, val folder: String, val mime: String)

    fun get(c: Context): Choice {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val base = p.getString(PREF_BASE, BASE_PICTURES)!!.takeIf { it in BASES } ?: BASE_PICTURES
        return Choice(base, clean(p.getString(PREF_NAME, DEFAULT_NAME)!!))
    }

    fun set(c: Context, choice: Choice) {
        val ch = Choice(choice.base.takeIf { it in BASES } ?: BASE_PICTURES, clean(choice.name))
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(PREF_BASE, ch.base).putString(PREF_NAME, ch.name).apply()
        DebugLog.add("cartella di salvataggio: " + describe(c, ch))
    }

    /**
     * A folder name the media store takes: no characters a file system or a LIKE query trips on, no empty or
     * "." / ".." parts, subfolders kept ("Fujifilm/X-T5"). Empty = [DEFAULT_NAME].
     */
    fun clean(name: String): String =
        name.replace('\\', '/').split('/')
            .map { part -> part.replace(Regex("[:*?\"<>|%\\p{Cntrl}]"), "").trim() }
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .joinToString("/").take(100).trimEnd('/', ' ')
            .ifEmpty { DEFAULT_NAME }

    /** The single folder everything goes to, or null for [BASE_SPLIT]. */
    fun path(ch: Choice): String? = if (ch.base == BASE_SPLIT) null else "${ch.base}/${ch.name}"

    /** The choice in words: "Pictures/Fujifilm", or where each kind goes. */
    fun describe(c: Context, ch: Choice = get(c)): String =
        path(ch) ?: c.getString(R.string.folder_split_desc, ch.name)

    /** Where [fileName] goes with the current choice. */
    fun target(c: Context, fileName: String): Target {
        val ch = get(c)
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val mime = mimeOf(ext)
        val volume = MediaStore.VOLUME_EXTERNAL_PRIMARY
        return when {
            ch.base == BASE_SPLIT -> split(fileName, ch.name)
            ch.base == BASE_DOWNLOAD -> Target(MediaStore.Downloads.getContentUri(volume), "${ch.base}/${ch.name}", mime)
            mime.startsWith("image/") -> Target(MediaStore.Images.Media.getContentUri(volume), "${ch.base}/${ch.name}", mime)
            mime.startsWith("video/") -> Target(MediaStore.Video.Media.getContentUri(volume), "${ch.base}/${ch.name}", mime)
            else -> split(fileName, ch.name) // not a photo or a video: only Download takes it
        }
    }

    /** The folder for the file's type: what the media store takes in every Android version. */
    fun split(fileName: String, name: String): Target {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val volume = MediaStore.VOLUME_EXTERNAL_PRIMARY
        return when (ext) {
            "jpg", "jpeg", "heic", "hif" -> Target(MediaStore.Images.Media.getContentUri(volume), "Pictures/$name", mimeOf(ext))
            "mov", "mp4" -> Target(MediaStore.Video.Media.getContentUri(volume), "Movies/$name", mimeOf(ext))
            else -> Target(MediaStore.Downloads.getContentUri(volume), "Download/$name", "application/octet-stream")
        }
    }

    private fun mimeOf(ext: String): String = when (ext) {
        "jpg", "jpeg" -> "image/jpeg"
        "heic", "hif" -> "image/heif"
        "raf" -> "image/x-fuji-raf"
        "mov" -> "video/quicktime"
        "mp4" -> "video/mp4"
        else -> "application/octet-stream"
    }

    /** A file went into [folder]: remembered for [allFolders]. */
    fun used(c: Context, folder: String) {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = p.getStringSet(PREF_USED, emptySet())!!
        if (folder in set) return
        p.edit().putStringSet(PREF_USED, HashSet(set).apply { add(folder) }).apply()
    }

    /** Every folder this app may have saved files to: the current ones, the ones used before, the old defaults. */
    fun allFolders(c: Context): List<String> {
        val ch = get(c)
        val now = path(ch)?.let { listOf(it, "Download/${ch.name}") }
            ?: listOf("Pictures/${ch.name}", "Movies/${ch.name}", "Download/${ch.name}")
        val used = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(PREF_USED, emptySet())!!
        return (now + used + LEGACY).distinct()
    }
}
