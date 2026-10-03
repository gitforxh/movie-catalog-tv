package com.moviecatalog.tv.ui

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import com.moviecatalog.tv.R
import com.moviecatalog.tv.data.Prefs
import com.moviecatalog.tv.smb.SmbClient
import com.moviecatalog.tv.smb.SmbDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "MovieCatalog"
private const val POSITIONS_FILE = "playback_positions"

// Don't bother resuming a movie that was barely started, or that was watched to (nearly) the end -
// in the latter case the next play should start from the beginning again.
private const val MIN_RESUME_MS = 10_000L
private const val END_MARGIN_MS = 60_000L
private const val SAVE_INTERVAL_MS = 10_000L

// Left/right seeking: one tap skips 30s; holding the key speeds up from 60s per second of holding
// towards a cap of 10 minutes per second.
private const val SEEK_TAP_MS = 30_000L
private const val SEEK_HOLD_START_RATE_MS = 60_000.0
private const val SEEK_HOLD_MAX_RATE_MS = 600_000.0
private const val SEEK_HOLD_ACCEL_PER_SEC = 1.5

/** Streams a movie straight off the NAS - see [SmbDataSource] for how the SMB read is wired into ExoPlayer. */
class PlaybackActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var path: String
    private lateinit var client: SmbClient
    private var setupJob: Job? = null
    private var exitDialog: AlertDialog? = null
    private val handler = Handler(Looper.getMainLooper())
    private val saveTick = object : Runnable {
        override fun run() {
            savePosition()
            handler.postDelayed(this, SAVE_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playback)

        path = intent.getStringExtra("path") ?: run { finish(); return }
        client = SmbClient(Prefs(this))

        // Back used to exit playback immediately, which is easy to hit by accident with a remote.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (exitDialog?.isShowing == true) return
                // With the playback controls on screen, Back should just close them first.
                val playerView = findViewById<PlayerView>(R.id.player_view)
                if (playerView.isControllerFullyVisible) {
                    playerView.hideController()
                    return
                }
                exitDialog = AlertDialog.Builder(this@PlaybackActivity)
                    .setMessage("Stop playing and go back?")
                    .setPositiveButton("Exit") { _, _ -> finish() }
                    .setNegativeButton("Keep watching", null)
                    .show()
                // Focus "Exit" by default, so Back followed by a press of the remote's select
                // button leaves in two quick presses.
                exitDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
            }
        })
    }

    // The player lives between onStart and onStop (it's released when the app is backgrounded), so
    // it's rebuilt here - and resumes from the saved position - when coming back to the app too.
    override fun onStart() {
        super.onStart()
        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(SmbDataSource.Factory(client)))
            .build()
        player = exoPlayer
        val playerView = findViewById<PlayerView>(R.id.player_view)
        playerView.player = exoPlayer
        // PlayerView nests its SubtitleView inside the same aspect-ratio-locked frame as the video
        // surface, so captions cling to the bottom edge of the picture itself - if the video is
        // letterboxed, they sit above the black bar instead of using it. Moving the view out to the
        // activity's own root (matching the full screen, not the video's content rect) fixes that.
        playerView.subtitleView?.let { subtitleView ->
            (subtitleView.parent as? android.view.ViewGroup)?.removeView(subtitleView)
            (playerView.parent as android.view.ViewGroup).addView(
                subtitleView,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                )
            )
            subtitleView.setBottomPaddingFraction(0.02f)
            // Text size is a fraction of the SubtitleView's own height - now the full screen instead
            // of just the video frame above, so the same default fraction rendered noticeably bigger.
            subtitleView.setFractionalTextSize(0.042f)
        }

        // A Fire TV/Android TV screensaver would otherwise still kick in from D-pad idleness alone
        // while a movie plays, since that isn't "user activity" the system tracks as such.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.e(TAG, "Playback error for \"$path\"", error)
                Toast.makeText(this@PlaybackActivity, "Playback failed for \"$path\": ${error.message}", Toast.LENGTH_LONG).show()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) clearPosition()
            }
        })

        // Finding a sibling subtitle file needs its own SMB directory listing, so it's looked up in
        // the background rather than blocking the player from starting - the movie starts playing
        // (without subtitles) even if this lookup is slow or finds nothing.
        setupJob = CoroutineScope(Dispatchers.Main).launch {
            val subtitlePaths = try {
                withContext(Dispatchers.IO) { client.findSubtitlePaths(path) }
            } catch (e: Exception) {
                Log.d(TAG, "findSubtitlePaths failed for \"$path\"", e)
                emptyList()
            }
            // Every subtitle file in the folder is offered in the player's subtitle picker, labelled
            // by language where the file name says (".chi.srt", ".en.srt"...). The first Chinese-looking
            // one - or, failing that, the first file - is switched on by default.
            val ordered = subtitlePaths.sortedBy { if (subtitleLanguage(it) == "Chinese") 0 else 1 }
            val mediaItem = MediaItem.Builder()
                .setUri(Uri.parse("smbcatalog:" + Uri.encode(path)))
                .apply {
                    if (ordered.isNotEmpty()) {
                        Log.d(TAG, "Found ${ordered.size} subtitle file(s) for \"$path\": $ordered")
                        setSubtitleConfigurations(ordered.mapIndexed { i, subtitlePath ->
                            MediaItem.SubtitleConfiguration.Builder(Uri.parse("smbcatalog:" + Uri.encode(subtitlePath)))
                                .setMimeType(subtitleMimeType(subtitlePath))
                                .setSelectionFlags(if (i == 0) C.SELECTION_FLAG_DEFAULT else 0)
                                .setLabel(subtitleLabel(subtitlePath))
                                .build()
                        })
                    }
                }
                .build()

            val resumeAt = savedPosition()
            if (resumeAt > 0) {
                Toast.makeText(this@PlaybackActivity, "Resuming from ${formatTime(resumeAt)}", Toast.LENGTH_SHORT).show()
                exoPlayer.setMediaItem(mediaItem, resumeAt)
            } else {
                exoPlayer.setMediaItem(mediaItem)
            }
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }
        handler.postDelayed(saveTick, SAVE_INTERVAL_MS)
    }

    /** "Chinese" / "English" when a subtitle file's name says so (".chi.srt", ".en.srt", "简体"...), else
     * null. Only explicit language markers count - Chinese characters elsewhere in the name are usually
     * just the movie's Chinese title, which says nothing about the subtitle's language. */
    private fun subtitleLanguage(path: String): String? {
        val name = path.substringAfterLast('/').lowercase()
        val tokens = name.split('.', ' ', '-', '_', '[', ']', '(', ')').filter { it.isNotEmpty() }
        return when {
            tokens.any { it in setOf("chi", "zh", "zho", "chs", "cht", "chinese", "cn", "sc", "tc", "gb", "big5") } ||
                listOf("中文", "简体", "簡體", "繁体", "繁體", "双语", "雙語", "中英").any { it in name } -> "Chinese"
            tokens.any { it in setOf("en", "eng", "english") } || "english" in name -> "English"
            else -> null
        }
    }

    /** What the subtitle picker shows: the language if known, plus the end of the file name so two
     * files of the same language can still be told apart. */
    private fun subtitleLabel(path: String): String {
        val name = path.substringAfterLast('/')
        val tail = if (name.length > 28) "..." + name.takeLast(25) else name
        return (subtitleLanguage(path) ?: "Subtitle") + " - " + tail
    }

    private fun subtitleMimeType(path: String) = when (path.substringAfterLast('.', "").lowercase()) {
        "vtt" -> MimeTypes.TEXT_VTT
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        else -> MimeTypes.APPLICATION_SUBRIP
    }

    private val positions get() = getSharedPreferences(POSITIONS_FILE, Context.MODE_PRIVATE)

    private fun savedPosition(): Long = positions.getLong(path, 0L)

    private fun clearPosition() {
        positions.edit().remove(path).apply()
    }

    /** Remembers where playback is up to, so the next play of this movie can pick up from there. */
    private fun savePosition() {
        val p = player ?: return
        if (p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED) return
        val pos = p.currentPosition
        val duration = p.duration
        when {
            pos < MIN_RESUME_MS -> clearPosition()
            duration != C.TIME_UNSET && pos > duration - END_MARGIN_MS -> clearPosition()
            else -> positions.edit().putLong(path, pos).apply()
        }
    }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    private var seekHoldStart = 0L
    private var seekLastEvent = 0L

    // The stock time bar already scrubs without seeking (it moves the bar, then sends a single seek
    // about a second after the last key press) - but with a fixed step of 1/20th of the movie, 10
    // minutes on a long film. So left/right are routed to the time bar with the step size set here:
    // 30s per tap, speeding up while the key is held. No seek is sent per key press, so a burst of
    // presses doesn't hammer the NAS.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if ((code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) && exitDialog?.isShowing != true) {
            val playerView = findViewById<PlayerView>(R.id.player_view)
            val timeBar = playerView.findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)
            if (timeBar != null) {
                if (!playerView.isControllerFullyVisible) {
                    playerView.showController()
                    timeBar.requestFocus()
                }
                // While focus is on one of the buttons (play/pause, subtitles...) left/right keep
                // moving between them as usual.
                if (timeBar.hasFocus()) {
                    if (event.action == KeyEvent.ACTION_DOWN) timeBar.setKeyTimeIncrement(stepFor(event))
                    val handled = super.dispatchKeyEvent(event)
                    if (event.action == KeyEvent.ACTION_UP) finishScrubNow(timeBar)
                    return handled
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // The time bar waits a fixed second after the last key press before sending its seek, and
    // exposes no setting for that. Its "stop scrubbing" step is private, so it's invoked directly on
    // key release to send the seek immediately (it's a no-op if no scrub is in progress, and the
    // time bar's own delayed call later does nothing once scrubbing has ended).
    private fun finishScrubNow(timeBar: DefaultTimeBar) {
        try {
            val stop = DefaultTimeBar::class.java.getDeclaredMethod("stopScrubbing", Boolean::class.javaPrimitiveType)
            stop.isAccessible = true
            stop.invoke(timeBar, false)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't end the time bar scrub early; the seek will follow after its own delay", e)
        }
    }

    private fun stepFor(event: KeyEvent): Long {
        val now = event.eventTime
        val step = if (event.repeatCount == 0) {
            seekHoldStart = now
            SEEK_TAP_MS.toDouble()
        } else {
            val heldSec = (now - seekHoldStart) / 1000.0
            val rate = minOf(SEEK_HOLD_START_RATE_MS * (1 + heldSec * SEEK_HOLD_ACCEL_PER_SEC), SEEK_HOLD_MAX_RATE_MS)
            rate * (now - seekLastEvent) / 1000.0
        }
        seekLastEvent = now
        return maxOf(step.toLong(), 1000L)
    }

    override fun onStop() {
        super.onStop()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handler.removeCallbacks(saveTick)
        setupJob?.cancel()
        savePosition()
        exitDialog?.dismiss()
        player?.release()
        player = null
    }
}
