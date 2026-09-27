# Movie Catalog TV

An Android TV app for browsing the same movie collection as [movie-catalog](../movie-catalog)'s
`movies.html`, and playing movies straight off your NAS.

**⚠️ Not yet run on a device.** This was written without access to an Android SDK, emulator, or a
TV device, so it hasn't been built or tested the way the HTML tool was verified in a browser. Treat
this as a first draft: build it in Android Studio, sideload it, and expect to iterate on real errors
(`adb logcat` output, Gradle build errors) - send those back and they can be fixed from there.

## How it fits with movie-catalog

- `build.py` now also writes `movies.json` (same data as `movies.html`) and copies it into
  `movie-catalog/movies.json` inside each of your NAS roots (`/Volumes/movies`, `/Volumes/movies-2T`),
  since those folders are already synced to the NAS.
- This app reads that `movies.json` over SMB, the same protocol your Mac uses to mount the NAS, and
  plays a movie's video file the same way.
- Nothing about the HTML tool changed except adding this export - `movies.html` still works exactly
  as before.

## Setup

1. Run `python3 build.py` in `movie-catalog/` at least once, so `movies.json` exists on the NAS.
2. Open this folder (`movie-catalog-tv/`) as a project in Android Studio. Let it sync Gradle (it will
   offer to generate the Gradle wrapper if it's missing).
3. Build and install onto your Android TV:
   - Over the network: `adb connect <tv-ip>:5555` then `adb install -r app/build/outputs/apk/debug/app-debug.apk`
     (enable Developer options + "Network debugging"/"ADB debugging" on the TV first).
   - Or run it directly from Android Studio with the TV selected as the deploy target.
4. On first launch, the app asks for your NAS connection:
   - **Host**: the NAS's IP or hostname.
   - **Username / password / domain**: your NAS login. Domain can usually be left blank.
   - **root=share pairs**: how `movies.json`'s paths map to SMB shares. If your NAS shares are
     literally named `movies` and `movies-2T` (matching `/Volumes/movies` and `/Volumes/movies-2T`),
     the default `movies=movies,movies-2T=movies-2T` is already correct.
   - **Catalog root**: which of those roots has the `movie-catalog/movies.json` file. Default `movies`.
   - These can be changed later from the "Settings" row on the home screen.

## What it does

- Home screen: a row of your library sorted by IMDb score (same default as the HTML page), then one
  row per year of "Best Movies of `<year>`" - the same discovered-movies feature from `movies.html`.
- Selecting a movie shows its poster, summary, genres, runtime, country, and scores.
- "Play" streams the video directly from the NAS via SMB - it isn't downloaded first.
- A movie stored as a folder (the common case, e.g. `Interstellar (2014) [1080p]/`) is handled by
  picking the largest video file inside it, the same idea `build.py` uses to skip samples/trailers.
- Discovered movies (not in your library) show their info but have no Play button.

## Known limitations / not yet built

- **No search or filtering** on the TV yet - `movies.html`'s search box and year filter aren't here.
  Worth adding once the basics are confirmed working.
- **No subtitle handling** - if a movie's folder has a separate `.srt` file, it isn't picked up.
- **Password stored in plain SharedPreferences**, not encrypted. Fine for a device on your own home
  network; don't reuse a sensitive password for it.
- **SMB only** - no DLNA/UPnP fallback.
- Error handling is minimal: failures show a Toast and a Logcat entry, not a friendly retry screen.

## Project layout

```
app/src/main/java/com/moviecatalog/tv/
  data/    Movie.kt, Catalog.kt (parsing movies.json), Prefs.kt (NAS settings)
  smb/     SmbSupport.kt (jcifs-ng: connect, resolve a movie's video file, fetch movies.json)
           SmbDataSource.kt (feeds SMB reads into ExoPlayer/media3)
  ui/      MainActivity (Leanback browse rows), DetailsActivity, PlaybackActivity, SettingsActivity
```
