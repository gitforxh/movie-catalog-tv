package com.moviecatalog.tv.ui

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.moviecatalog.tv.R
import com.moviecatalog.tv.data.Prefs
import com.moviecatalog.tv.smb.SmbClient
import com.moviecatalog.tv.smb.SmbDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Streams a movie straight off the NAS - see [SmbDataSource] for how the SMB read is wired into ExoPlayer. */
class PlaybackActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playback)

        val path = intent.getStringExtra("path") ?: run { finish(); return }
        val client = SmbClient(Prefs(this))

        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(SmbDataSource.Factory(client)))
            .build()
        player = exoPlayer
        findViewById<PlayerView>(R.id.player_view).player = exoPlayer

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.e("MovieCatalog", "Playback error for \"$path\"", error)
                Toast.makeText(this@PlaybackActivity, "Playback failed for \"$path\": ${error.message}", Toast.LENGTH_LONG).show()
            }
        })

        // Finding a sibling subtitle file needs its own SMB directory listing, so it's looked up in
        // the background rather than blocking the player from starting - the movie starts playing
        // (without subtitles) even if this lookup is slow or finds nothing.
        CoroutineScope(Dispatchers.Main).launch {
            val subtitlePath = try {
                withContext(Dispatchers.IO) { client.findSubtitlePath(path) }
            } catch (e: Exception) {
                Log.d("MovieCatalog", "findSubtitlePath failed for \"$path\"", e)
                null
            }
            val mediaItem = MediaItem.Builder()
                .setUri(Uri.parse("smbcatalog:" + Uri.encode(path)))
                .apply {
                    if (subtitlePath != null) {
                        Log.d("MovieCatalog", "Found subtitle \"$subtitlePath\" for \"$path\"")
                        setSubtitleConfigurations(
                            listOf(
                                MediaItem.SubtitleConfiguration.Builder(Uri.parse("smbcatalog:" + Uri.encode(subtitlePath)))
                                    .setMimeType(subtitleMimeType(subtitlePath))
                                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                                    .setLabel("From NAS folder")
                                    .build()
                            )
                        )
                    }
                }
                .build()
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }
    }

    private fun subtitleMimeType(path: String) = when (path.substringAfterLast('.', "").lowercase()) {
        "vtt" -> MimeTypes.TEXT_VTT
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        else -> MimeTypes.APPLICATION_SUBRIP
    }

    override fun onStop() {
        super.onStop()
        player?.release()
        player = null
    }
}
