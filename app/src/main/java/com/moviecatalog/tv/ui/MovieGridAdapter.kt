package com.moviecatalog.tv.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
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
        private val title = view.findViewById<TextView>(R.id.title)
        private val subtitle = view.findViewById<TextView>(R.id.subtitle)

        fun bindMovie(movie: Movie, onClick: (Movie) -> Unit) {
            title.text = movie.name
            subtitle.text = if (movie.imdb != null) "${movie.year} · IMDb ${movie.imdb}" else movie.year
            poster.setImageDrawable(ColorDrawable(Color.parseColor("#1f1f24")))
            if (!movie.poster.isNullOrEmpty()) {
                Picasso.get().load(movie.poster).into(poster)
            }
            itemView.setOnClickListener { onClick(movie) }
        }
    }
}
