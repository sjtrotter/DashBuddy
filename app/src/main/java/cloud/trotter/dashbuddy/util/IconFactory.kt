package cloud.trotter.dashbuddy.util

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import androidx.core.content.ContextCompat
import cloud.trotter.dashbuddy.R

object IconFactory {

    fun createBadgedIcon(context: Context, badgeResId: Int): Drawable {
        // 1. Get the two drawables
        val baseIcon = ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground)
        val badge = ContextCompat.getDrawable(context, badgeResId)

        // 2. Create an array of the drawables
        val drawables = arrayOf(baseIcon, badge)

        // 3. Create the LayerDrawable
        val layerDrawable = LayerDrawable(drawables)

        // 4. Position the badge on top of the base icon
        // You can adjust these values to get the perfect placement.
        // This example places the badge in the bottom-right corner.
        layerDrawable.setLayerInset(
            1, // Index of the badge drawable
            24, // Left inset
            24, // Top inset
            0,  // Right inset
            0   // Bottom inset
        )

        return layerDrawable
    }
}