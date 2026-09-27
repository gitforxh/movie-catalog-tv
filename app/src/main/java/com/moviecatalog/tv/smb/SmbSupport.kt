package com.moviecatalog.tv.smb

import android.util.Log
import com.moviecatalog.tv.data.Prefs
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import java.util.Properties

private val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "avi", "m4v", "mov", "wmv", "ts")

class SmbException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Builds an authenticated jcifs context and resolves movies.json paths to SmbFiles, mirroring build.py's ROOTS. */
class SmbClient(private val prefs: Prefs) {

    private val context: CIFSContext by lazy {
        val props = Properties().apply {
            setProperty("jcifs.smb.client.minVersion", "SMB202")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
            // Folders nested two levels deep (share/year/folder/file) were failing with "the system
            // cannot find the file specified" even for a byte-for-byte correct, verified-to-exist
            // path - on multiple shares, with the same credentials that a real SMB client (Samsung
            // My Files) uses successfully for the exact same files. That points at jcifs-ng's
            // automatic DFS-referral resolution misbehaving against this router's Samba (which isn't
            // a real DFS server) rather than an actual missing file - so disable it outright.
            setProperty("jcifs.smb.client.dfs.disabled", "true")
        }
        val base = BaseContext(PropertyConfiguration(props))
        base.withCredentials(NtlmPasswordAuthenticator(prefs.domain, prefs.username, prefs.password))
    }

    /**
     * "movies/2020/Foo (2020)/Foo (2020).mkv" -> smb://host/share/2020/Foo (2020)/Foo (2020).mkv,
     * built by walking one path segment at a time via jcifs's own relative-child constructor
     * (SmbFile(parent, name)) rather than a single hand-built URL string.
     *
     * A single-string URL with our own percent-encoding worked for paths one directory deep (e.g.
     * "share/year/movie.mkv") but consistently failed - "the system cannot find the file specified"
     * - for real, verified-to-exist paths that were two directories deep ("share/year/folder/movie.mkv"),
     * on multiple shares, even with credentials a real SMB client (Samsung My Files) uses successfully
     * for the exact same files. That's consistent with a jcifs-ng bug/quirk in how it parses a single
     * multi-segment URL string, not an actual missing file - so each segment is now added one at a
     * time instead, letting jcifs do its own (per-segment) encoding.
     */
    private fun smbFile(catalogPath: String): SmbFile {
        val segments = catalogPath.split('/').filter { it.isNotEmpty() }
        val root = segments.first()
        val share = prefs.shareMap[root] ?: throw SmbException("No SMB share configured for \"$root\" (check Settings)")
        var current: SmbFile = SmbFile("smb://${prefs.host}/$share/", context)
        val rest = segments.drop(1)
        for ((i, segment) in rest.withIndex()) {
            val isLast = i == rest.lastIndex
            current = SmbFile(current, if (isLast) segment else "$segment/")
        }
        Log.d("MovieCatalog", "smbFile(\"$catalogPath\") -> ${current.path}")
        return current
    }

    /** movies.json lives at <catalogRoot's share>/movie-catalog/movies.json. */
    fun fetchCatalogJson(): String {
        val f = smbFile("${prefs.catalogRoot}/movie-catalog/movies.json")
        return f.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /**
     * A movie's `path` in movies.json may be a video file directly, or (much more often) a folder
     * containing one - so pick the largest video file inside, same idea as a "biggest file wins"
     * heuristic to skip samples/trailers.
     */
    fun resolvePlayableFile(catalogPath: String): SmbFile {
        val f = smbFile(catalogPath)
        // f.isDirectory unreliably reports false for real directories here (observed on a real NAS,
        // regardless of a trailing slash) - so try listing it instead of trusting that flag. A real
        // file throws/returns null from listFiles(), which is a fine signal that f itself is playable.
        val all = try {
            f.listFiles()
        } catch (e: Exception) {
            Log.d("MovieCatalog", "resolvePlayableFile: listFiles() threw for ${f.path}", e)
            null
        }
        if (all == null) {
            // listFiles() can throw for a real, existing directory - observed with jcifs-ng against
            // this NAS's Samba for at least one folder that other SMB clients (e.g. MX Player) browse
            // and play just fine, so it's a jcifs-ng enumeration bug/incompatibility, not a real
            // missing file. Many "scene release" folders name their video identically to the folder
            // itself plus an extension (e.g. ".../Foo.2026.1080p-GROUP/Foo.2026.1080p-GROUP.mkv") -
            // try that guess directly (a plain SMB open, not a directory listing) before falling back
            // to treating catalogPath itself as the file.
            val folderName = catalogPath.substringAfterLast('/')
            for (ext in VIDEO_EXTENSIONS) {
                val guess = smbFile("$catalogPath/$folderName.$ext")
                val guessLength = try { guess.length() } catch (e: Exception) { -1L }
                if (guessLength > 0) {
                    Log.d("MovieCatalog", "resolvePlayableFile: listFiles() failed, but guessed \"$folderName.$ext\" exists ($guessLength bytes)")
                    return guess
                }
            }
            Log.d("MovieCatalog", "resolvePlayableFile: treating $catalogPath as a file directly")
            // Not a directory (or listing merely failed and no same-name guess worked) - a fresh
            // SmbFile instance is returned rather than reusing `f`: after listFiles() throws on it,
            // later calls on the same instance (even a valid one like .length()) started failing too
            // on real hardware, as if the failed query left jcifs's cached state for that object bad.
            return smbFile(catalogPath)
        }
        Log.d("MovieCatalog", "resolvePlayableFile: listFiles() for ${f.path} -> ${all.joinToString { it.name }}")
        // jcifs-ng's listFiles() was observed returning each child's name with the parent folder's
        // own name prefixed onto it with no separator (e.g. "Foo (2013) [1080p]" +
        // "Foo.2013.1080p.mp4" -> "Foo (2013) [1080p]Foo.2013.1080p.mp4"), confirmed against the
        // real on-disk filenames - so strip that duplicated prefix back off if present.
        val folderName = catalogPath.substringAfterLast('/')
        fun realName(raw: String) = raw.removePrefix(folderName)
        val candidates = all.filter { it.isFile && realName(it.name).substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS }
        val chosenName = candidates.maxByOrNull { it.length() }?.let { realName(it.name) }
            ?: throw SmbException("No video file found in ${f.path}")
        Log.d("MovieCatalog", "resolvePlayableFile: chose \"$chosenName\"")
        // Rebuild via our own byte-level-encoded URL rather than returning the SmbFile instance
        // straight from listFiles(): reusing an object across the "pick the biggest file" listing
        // and the later streaming open() has been observed leaving jcifs's tree/session state bad
        // for some titles ("the system cannot find the file specified" on a file that clearly
        // exists and was just measured successfully moments earlier).
        return smbFile("$catalogPath/$chosenName")
    }
}
