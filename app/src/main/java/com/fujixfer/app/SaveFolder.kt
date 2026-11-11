package com.fujixfer.app

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.util.Locale

/**
 * Where downloaded files go in the phone's storage, chosen in "Impostazioni › Cartella di salvataggio".
 *
 * One path for everything (the default, Pictures/Fujifilm), or, with "Cartelle separate per tipo", one path for
 * photos, one for RAW and one for videos. A path is a top-level folder the media store takes for that kind of
 * file ([bases]) and a free subfolder. RAW files are stored as images (image/x-fuji-raf) so they can sit next to
 * the photos. A file a folder refuses anyway (a video in Pictures on Android 10) falls back to [defaultFor].
 */
object SaveFolder {
    private const val PREFS = "fuji"
    private const val PREF_SPLIT = "save_split"
    private const val PREF_PREFIX = "save_" // + kind: "save_photo", "save_raw", "save_video"
    /** Settings of the first version of this screen (one base, one name): read once, then removed. */
    private const val PREF_OLD_BASE = "save_base"
    private const val PREF_OLD_NAME = "save_name"
    /** Every folder files were saved to: the uploads still find the files saved before a change. */
    private const val PREF_USED = "save_used"

    const val PICTURES = "Pictures"
    const val DCIM = "DCIM"
    const val MOVIES = "Movies"
    const val DOWNLOAD = "Download"

    const val DEFAULT_NAME = "Fujifilm"

    /** Folders of the versions before the setting: their files stay where they are. */
    private val LEGACY = listOf("Pictures/Fujifilm", "Movies/Fujifilm", "Download/Fujifilm")

    /** The paths that can be set: one for all files, or one per kind. */
    enum class Kind(val key: String) { ALL("all"), PHOTO("photo"), RAW("raw"), VIDEO("video") }

    /** A top-level folder of the shared storage and a subfolder in it ("Pictures" + "Fujifilm"). */
    data class Path(val base: String, val name: String) {
        override fun toString() = "$base/$name"
    }

    /** Where a file is stored: media store collection, folder (RELATIVE_PATH), MIME type. */
    data class Target(val collection: Uri, val folder: String, val mime: String)

    /** The top-level folders the media store takes for [kind], first = the default. */
    fun bases(kind: Kind): List<String> = when (kind) {
        Kind.ALL -> listOf(PICTURES, DCIM, DOWNLOAD)
        Kind.PHOTO -> listOf(PICTURES, DCIM)
        Kind.RAW -> listOf(DOWNLOAD, PICTURES, DCIM)
        Kind.VIDEO -> if (Build.VERSION.SDK_INT >= 30) listOf(MOVIES, DCIM, PICTURES) else listOf(MOVIES, DCIM)
    }

    /** The path a kind has until it is changed, and where a refused file falls back to. */
    fun defaultFor(kind: Kind, name: String = DEFAULT_NAME): Path = Path(bases(kind).first(), name)

    // ------------------------------------------------------------------ settings

    fun isSplit(c: Context): Boolean {
        migrate(c)
        return prefs(c).getBoolean(PREF_SPLIT, false)
    }

    /**
     * Turns "Cartelle separate per tipo" on or off. The first time it goes on, each kind starts from the single
     * path (when that folder takes the kind), so nothing moves until a path is changed.
     */
    fun setSplit(c: Context, on: Boolean) {
        migrate(c)
        val p = prefs(c)
        val e = p.edit().putBoolean(PREF_SPLIT, on)
        if (on) {
            val all = path(c, Kind.ALL)
            for (k in listOf(Kind.PHOTO, Kind.RAW, Kind.VIDEO)) {
                if (p.contains(PREF_PREFIX + k.key)) continue
                val start = if (all.base in bases(k)) all else defaultFor(k, all.name)
                e.putString(PREF_PREFIX + k.key, start.toString())
            }
        }
        e.apply()
        DebugLog.add("cartella di salvataggio: " + describe(c))
    }

    /** The path set for [kind] (also when it is not in use now). */
    fun path(c: Context, kind: Kind): Path {
        migrate(c)
        val s = prefs(c).getString(PREF_PREFIX + kind.key, null) ?: return defaultFor(kind)
        val base = s.substringBefore('/')
        return if (base in bases(kind)) Path(base, clean(s.substringAfter('/', ""))) else defaultFor(kind, clean(s.substringAfter('/', "")))
    }

    fun setPath(c: Context, kind: Kind, path: Path) {
        migrate(c)
        val p = Path(path.base.takeIf { it in bases(kind) } ?: bases(kind).first(), clean(path.name))
        prefs(c).edit().putString(PREF_PREFIX + kind.key, p.toString()).apply()
    }

    /** The path files of [kind] go to now (PHOTO, RAW or VIDEO). */
    fun pathNow(c: Context, kind: Kind): Path = if (kind != Kind.ALL && isSplit(c)) path(c, kind) else path(c, Kind.ALL)

    /** The choice in words: "Pictures/Fujifilm", or "foto in …, RAW in …, video in …". */
    fun describe(c: Context): String =
        if (!isSplit(c)) path(c, Kind.ALL).toString()
        else c.getString(R.string.folder_split_desc, path(c, Kind.PHOTO), path(c, Kind.RAW), path(c, Kind.VIDEO))

    /** Where the files named [fileNames] went: their folders, without repeats ("Pictures/Fujifilm"). */
    fun describeFor(c: Context, fileNames: List<String>): String {
        if (!isSplit(c)) return path(c, Kind.ALL).toString()
        val folders = fileNames.mapNotNull { kindOf(it) }.distinct().sortedBy { it.ordinal }.map { pathNow(c, it).toString() }.distinct()
        return folders.joinToString(", ").ifEmpty { describe(c) }
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

    // ------------------------------------------------------------------ saving

    /** PHOTO, RAW or VIDEO; null = another kind of file (only Download takes it). */
    fun kindOf(fileName: String): Kind? = when (fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "jpg", "jpeg", "heic", "heif", "hif" -> Kind.PHOTO
        "raf" -> Kind.RAW
        "mov", "mp4" -> Kind.VIDEO
        else -> null
    }

    /** Where [fileName] goes now: first the path chosen for it, then the default folder of its type. */
    fun targets(c: Context, fileName: String): List<Target> {
        val kind = kindOf(fileName)
        if (kind == null) {
            val name = pathNow(c, Kind.RAW).name
            return listOf(Target(MediaStore.Downloads.getContentUri(VOLUME), "$DOWNLOAD/$name", "application/octet-stream"))
        }
        val chosen = pathNow(c, kind)
        return listOf(target(fileName, kind, chosen), target(fileName, kind, defaultFor(kind, chosen.name))).distinct()
    }

    private const val VOLUME = MediaStore.VOLUME_EXTERNAL_PRIMARY

    private fun target(fileName: String, kind: Kind, p: Path): Target {
        val mime = mimeOf(fileName)
        val collection = when {
            p.base == DOWNLOAD -> MediaStore.Downloads.getContentUri(VOLUME)
            kind == Kind.VIDEO -> MediaStore.Video.Media.getContentUri(VOLUME)
            else -> MediaStore.Images.Media.getContentUri(VOLUME)
        }
        return Target(collection, p.toString(), mime)
    }

    private fun mimeOf(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "jpg", "jpeg" -> "image/jpeg"
        "heic", "heif", "hif" -> "image/heif"
        "raf" -> "image/x-fuji-raf"
        "mov" -> "video/quicktime"
        "mp4" -> "video/mp4"
        else -> "application/octet-stream"
    }

    /** A file went into [folder]: remembered for [allFolders]. */
    fun used(c: Context, folder: String) {
        val p = prefs(c)
        val set = p.getStringSet(PREF_USED, emptySet())!!
        if (folder in set) return
        p.edit().putStringSet(PREF_USED, HashSet(set).apply { add(folder) }).apply()
    }

    /** Every folder this app may have saved files to: the ones set now, the ones used before, the old defaults. */
    fun allFolders(c: Context): List<String> {
        val now = Kind.values().map { path(c, it).toString() } + "$DOWNLOAD/${pathNow(c, Kind.RAW).name}"
        val used = prefs(c).getStringSet(PREF_USED, emptySet())!!
        return (now + used + LEGACY).distinct()
    }

    // ------------------------------------------------------------------

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The first version kept one base ("Pictures", "DCIM", "Download" or "split") and one name. */
    private fun migrate(c: Context) {
        val p = prefs(c)
        if (!p.contains(PREF_OLD_BASE) && !p.contains(PREF_OLD_NAME)) return
        val base = p.getString(PREF_OLD_BASE, PICTURES)!!
        val name = clean(p.getString(PREF_OLD_NAME, DEFAULT_NAME)!!)
        val e = p.edit().remove(PREF_OLD_BASE).remove(PREF_OLD_NAME)
        if (base == "split") {
            e.putBoolean(PREF_SPLIT, true)
                .putString(PREF_PREFIX + Kind.ALL.key, Path(PICTURES, name).toString())
                .putString(PREF_PREFIX + Kind.PHOTO.key, Path(PICTURES, name).toString())
                .putString(PREF_PREFIX + Kind.RAW.key, Path(DOWNLOAD, name).toString())
                .putString(PREF_PREFIX + Kind.VIDEO.key, Path(MOVIES, name).toString())
        } else {
            e.putString(PREF_PREFIX + Kind.ALL.key, Path(base.takeIf { it in bases(Kind.ALL) } ?: PICTURES, name).toString())
        }
        e.commit()
    }
}
