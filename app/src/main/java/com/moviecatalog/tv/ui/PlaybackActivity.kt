package com.moviecatalog.tv.ui

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.moviecatalog.tv.R
import com.moviecatalog.tv.data.Prefs
import com.moviecatalog.tv.smb.SmbClient
import com.moviecatalog.tv.smb.SmbDataSource

/** Streams a movie straight off the NAS - see [SmbDataSource] for how the SMB read is wired into ExoPlayer. */
class PlaybackActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playback)

        val path = intent.getStringExtra("path") ?: run { finish(); return }
        val client = SmbClient(Prefs(this))

        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(SmbDataSource.Factory(client))
            )
            .build()
        player = exoPlayer
        findViewById<PlayerView>(R.id.player_view).player = exoPlayer

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.e("MovieCatalog", "Playback error for \"$path\"", error)
                Toast.makeText(this@PlaybackActivity, "Playback failed for \"$path\": ${error.message}", Toast.LENGTH_LONG).show()
            }
        })

        exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse("smbcatalog:" + Uri.encode(path))))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    override fun onStop() {
        super.onStop()
        player?.release()
        player = null
    }
}
