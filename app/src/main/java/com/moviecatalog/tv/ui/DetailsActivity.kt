package com.moviecatalog.tv.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.moviecatalog.tv.R
import com.moviecatalog.tv.data.Movie
import com.squareup.picasso.Picasso

class DetailsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_details)

        @Suppress("DEPRECATION")
        val movie = intent.getSerializableExtra("movie") as? Movie ?: run { finish(); return }

        findViewById<TextView>(R.id.title).text = movie.name
        findViewById<TextView>(R.id.subtitle).text = movie.subtitle
        findViewById<TextView>(R.id.genres).text = movie.genres.joinToString("  ·  ")
        findViewById<TextView>(R.id.scores).text = movie.scoreLine
        findViewById<TextView>(R.id.overview).text = movie.overview

        val poster = findViewById<ImageView>(R.id.poster)
        if (!movie.poster.isNullOrEmpty()) Picasso.get().load(movie.poster).into(poster)

        val play = findViewById<Button>(R.id.play)
        if (movie.isPlayable) {
            play.setOnClickListener {
                startActivity(
                    Intent(this, PlaybackActivity::class.java)
                        .putExtra("path", movie.path)
                        .putExtra("title", movie.name)
                )
            }
            play.requestFocus()
        } else {
            play.text = "Not in your library"
            play.isEnabled = false
        }
    }
}
