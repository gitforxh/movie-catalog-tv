package com.moviecatalog.tv.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
        bindGenres(movie)
        bindScores(movie)
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

    /** Outlined, rounded-corner genre chips, matching movies.html's ".g" styling. */
    private fun bindGenres(movie: Movie) {
        val container = findViewById<LinearLayout>(R.id.genres)
        val mutedColor = ContextCompat.getColor(this, R.color.muted)
        movie.genres.forEach { genre ->
            val chip = TextView(this).apply {
                text = genre
                setTextColor(mutedColor)
                textSize = 12f
                setPadding(dp(10), dp(3), dp(10), dp(3))
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(1), mutedColor)
                    cornerRadius = dp(12).toFloat()
                }
            }
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            if (container.childCount > 0) params.marginStart = dp(6)
            container.addView(chip, params)
        }
    }

    /** Colored score badges, matching movies.html's .imdb/.rt/.tm/.au styling. */
    private fun bindScores(movie: Movie) {
        val container = findViewById<LinearLayout>(R.id.scores)
        movie.imdb?.let { addBadge(container, "IMDb $it", "#F5C518", "#000000") }
        movie.rt?.let { addBadge(container, "🍅 $it", "#FA320A", "#FFFFFF") }
        movie.tmdb?.let { addBadge(container, "TMDB $it", "#0369A1", "#FFFFFF") }
        movie.au?.takeIf { it.isNotEmpty() }?.let { addBadge(container, it.replace(" ", ""), "#0B6E4F", "#FFFFFF") }
        if (movie.hasSub) addBadge(container, "SUB", "#64748B", "#FFFFFF")
    }

    private fun addBadge(container: LinearLayout, text: String, backgroundColor: String, textColor: String) {
        val badge = TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor(textColor))
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(backgroundColor))
                cornerRadius = dp(5).toFloat()
            }
        }
        val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        if (container.childCount > 0) params.marginStart = dp(6)
        container.addView(badge, params)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
