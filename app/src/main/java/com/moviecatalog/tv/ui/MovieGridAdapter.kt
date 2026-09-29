package com.moviecatalog.tv.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.moviecatalog.tv.R
import com.moviecatalog.tv.data.Movie
import com.squareup.picasso.Picasso

/** A plain RecyclerView adapter for the movie grid - see MainActivity.kt for why this replaced
 * Leanback's BrowseSupportFragment/VerticalGridSupportFragment/ImageCardView. */
class MovieGridAdapter(
    private val onMovieClick: (Movie) -> Unit,
) : RecyclerView.Adapter<MovieGridAdapter.CardViewHolder>() {

    private val items = ArrayList<Movie>()

    fun setItems(newItems: List<Movie>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_movie_card, parent, false)
        return CardViewHolder(view)
    }

    override fun onBindViewHolder(holder: CardViewHolder, position: Int) {
        holder.bindMovie(items[position], onMovieClick)
    }

    class CardViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val poster = view.findViewById<ImageView>(R.id.poster)
        private val badges = view.findViewById<LinearLayout>(R.id.badges)
        private val title = view.findViewById<TextView>(R.id.title)

        fun bindMovie(movie: Movie, onClick: (Movie) -> Unit) {
            // Date + scores first, title second: scanning a grid of dozens of posters for "is this
            // one any good, and how old" works better with that up top than buried under/below a
            // two-line title, and it's one less line taking up vertical space per card.
            badges.removeAllViews()
            addDate(movie.released?.takeIf { it.isNotEmpty() } ?: movie.year)
            movie.imdb?.let { addBadge("IMDb $it") }
            movie.rt?.let { addBadge("🍅 $it") }

            title.text = movie.name

            poster.setImageDrawable(ColorDrawable(Color.parseColor("#1f1f24")))
            if (!movie.poster.isNullOrEmpty()) {
                Picasso.get().load(movie.poster).into(poster)
            }
            itemView.setOnClickListener { onClick(movie) }
        }

        private fun addDate(text: String) {
            val ctx = itemView.context
            val dp = { v: Int -> (v * ctx.resources.displayMetrics.density).toInt() }
            val date = TextView(ctx).apply {
                this.text = text
                setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.muted))
                textSize = 11f
            }
            badges.addView(date, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        // Same muted, outlined chip style as the genre tags on the details screen - a solid bright
        // badge on every poster in the grid turned out to look too busy/noisy next to the artwork.
        private fun addBadge(text: String) {
            val ctx = itemView.context
            val dp = { v: Int -> (v * ctx.resources.displayMetrics.density).toInt() }
            val mutedColor = androidx.core.content.ContextCompat.getColor(ctx, R.color.muted)
            val badge = TextView(ctx).apply {
                this.text = text
                setTextColor(mutedColor)
                textSize = 10f
                setPadding(dp(6), dp(1), dp(6), dp(1))
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(1), mutedColor)
                    cornerRadius = dp(8).toFloat()
                }
            }
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            if (badges.childCount > 0) params.marginStart = dp(4)
            badges.addView(badge, params)
        }
    }
}
