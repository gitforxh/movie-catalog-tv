package com.moviecatalog.tv.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView

/**
 * An ImageView that sizes its height to a standard 2:3 movie-poster ratio based on whatever width
 * it's given (e.g. by a grid column) - fixing a height that doesn't match the actual column width
 * makes centerCrop chop the top and bottom off posters instead of just filling the width.
 */
class PosterImageView(context: Context, attrs: AttributeSet? = null) : ImageView(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = width * 3 / 2
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }
}
