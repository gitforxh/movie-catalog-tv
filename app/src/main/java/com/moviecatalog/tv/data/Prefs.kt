package com.moviecatalog.tv.data

import android.content.Context

/**
 * NAS connection settings, entered once on the TV via [com.moviecatalog.tv.ui.SettingsActivity].
 *
 * A movie's path in movies.json looks like "movies/2020/Foo (2020)/Foo.mkv" or
 * "movies-2T/old/Bar (2011)". The first segment ("movies", "movies-2T", ...) is matched against
 * [shareMap] to find the SMB share it lives on; the rest of the path is the path within that share.
 * This mirrors how build.py itself scans /Volumes/movies and /Volumes/movies-2T on the Mac.
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("nas", Context.MODE_PRIVATE)

    var host: String
        get() = sp.getString("host", "") ?: ""
        set(v) = sp.edit().putString("host", v).apply()

    var username: String
        get() = sp.getString("username", "") ?: ""
        set(v) = sp.edit().putString("username", v).apply()

    var password: String
        get() = sp.getString("password", "") ?: ""
        set(v) = sp.edit().putString("password", v).apply()

    var domain: String
        get() = sp.getString("domain", "") ?: ""
        set(v) = sp.edit().putString("domain", v).apply()

    /** Comma-separated "root=share" pairs, e.g. "movies=movies,movies-2T=movies2t". */
    var shareMapRaw: String
        get() = sp.getString("shareMap", "movies=movies,movies-2T=movies-2T") ?: ""
        set(v) = sp.edit().putString("shareMap", v).apply()

    val shareMap: Map<String, String>
        get() = shareMapRaw.split(",").mapNotNull {
            val parts = it.split("=", limit = 2)
            if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
        }.toMap()

    /** Which root+share holds movie-catalog/movies.json. Defaults to the first configured root. */
    var catalogRoot: String
        get() = sp.getString("catalogRoot", shareMap.keys.firstOrNull() ?: "movies") ?: "movies"
        set(v) = sp.edit().putString("catalogRoot", v).apply()

    val isConfigured: Boolean get() = host.isNotBlank() && shareMap.isNotEmpty()
}
