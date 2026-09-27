package com.moviecatalog.tv.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * One row from movies.json, produced by the movie-catalog build.py script.
 * [path] is null for a "discovered" movie (one you don't have on the NAS) - only library
 * movies can be played.
 */
data class Movie(
    val name: String,
    val year: String,
    val released: String?,
    val overview: String,
    val poster: String?,
    val genres: List<String>,
    val runtimeMinutes: Int?,
    val imdb: String?,
    val imdbId: String?,
    val rt: String?,
    val tmdb: String?,
    val tmdbId: Long?,
    val au: String?,
    val country: String?,
    val path: String?,
) : java.io.Serializable {
    val isPlayable: Boolean get() = !path.isNullOrEmpty()

    val subtitle: String
        get() = listOfNotNull(
            released?.takeIf { it.isNotEmpty() } ?: year,
            runtimeMinutes?.let { "${it / 60}h ${(it % 60).toString().padStart(2, '0')}m" },
            country?.takeIf { it.isNotEmpty() },
        ).joinToString("  ·  ")

    val scoreLine: String
        get() = listOfNotNull(
            imdb?.let { "IMDb $it" },
            rt?.let { "RT $it" },
            tmdb?.let { "TMDB $it" },
            au?.takeIf { it.isNotEmpty() },
        ).joinToString("   ")

    companion object {
        fun fromJson(o: JSONObject): Movie {
            fun str(key: String): String? = if (o.isNull(key)) null else o.optString(key).takeIf { it.isNotEmpty() }
            val genres = mutableListOf<String>()
            o.optJSONArray("genres")?.let { arr: JSONArray -> for (i in 0 until arr.length()) genres += arr.getString(i) }
            return Movie(
                name = o.optString("name", o.optString("title", "?")),
                year = str("year") ?: "",
                released = str("released"),
                overview = o.optString("overview", ""),
                poster = str("poster"),
                genres = genres,
                runtimeMinutes = str("runtime")?.toIntOrNull(),
                imdb = str("imdb"),
                imdbId = str("imdb_id"),
                rt = str("rt"),
                tmdb = str("tmdb"),
                tmdbId = if (o.has("tmdb_id") && !o.isNull("tmdb_id")) o.optLong("tmdb_id") else null,
                au = str("au"),
                country = str("country"),
                path = str("path"),
            )
        }
    }
}

/** movies.json as a whole. */
data class Catalog(
    val movies: List<Movie>,
    val discover: Map<String, List<Movie>>,
) {
    companion object {
        fun parse(json: String): Catalog {
            val root = JSONObject(json)
            val movies = root.getJSONArray("movies").let { arr ->
                (0 until arr.length()).map { Movie.fromJson(arr.getJSONObject(it)) }
            }
            val discover = mutableMapOf<String, List<Movie>>()
            root.optJSONObject("discover")?.let { obj ->
                val years = obj.keys().asSequence().map { it as String }.toList()
                for (year in years) {
                    val arr = obj.getJSONArray(year)
                    discover[year] = (0 until arr.length()).map { Movie.fromJson(arr.getJSONObject(it)) }
                }
            }
            return Catalog(movies, discover)
        }
    }
}
