package com.weatherwidget.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.flexbox.FlexboxLayout
import com.weatherwidget.R

class IconGalleryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_icon_gallery)

        setupViews()
    }

    private fun setupViews() {
        findViewById<android.widget.ImageButton>(R.id.back_button).setOnClickListener {
            finish()
        }

        setupIconGallery()
    }

    private data class GalleryIcon(val drawableRes: Int, val stringRes: Int)

    private val allGalleryIcons = listOf(
        // Clear / Sunny
        GalleryIcon(R.drawable.ic_weather_clear, R.string.gallery_icon_clear),
        GalleryIcon(R.drawable.ic_weather_mostly_clear, R.string.gallery_icon_mostly_clear),
        GalleryIcon(R.drawable.ic_weather_horizon_sun, R.string.gallery_icon_horizon_sun),

        // Cloudy
        GalleryIcon(R.drawable.ic_weather_partly_cloudy, R.string.gallery_icon_partly_cloudy),
        GalleryIcon(R.drawable.ic_weather_mostly_cloudy, R.string.gallery_icon_mostly_cloudy),
        GalleryIcon(R.drawable.ic_weather_cloudy, R.string.gallery_icon_cloudy),

        // Night
        GalleryIcon(R.drawable.ic_weather_night, R.string.gallery_icon_night),
        GalleryIcon(R.drawable.ic_weather_partly_cloudy_night, R.string.gallery_icon_partly_cloudy_night),
        GalleryIcon(R.drawable.ic_weather_mostly_cloudy_night, R.string.gallery_icon_mostly_cloudy_night),

        // Rain
        GalleryIcon(R.drawable.ic_weather_rain, R.string.gallery_icon_rain),
        GalleryIcon(R.drawable.ic_weather_cloudy_chance_rain, R.string.gallery_icon_chance_rain),
        GalleryIcon(R.drawable.ic_weather_cloudy_slight_chance_rain, R.string.gallery_icon_slight_rain),
        GalleryIcon(R.drawable.ic_weather_partly_cloudy_chance_rain, R.string.gallery_icon_partly_cloudy_chance_rain),
        GalleryIcon(R.drawable.ic_weather_partly_cloudy_slight_chance_rain, R.string.gallery_icon_partly_cloudy_slight_rain),

        // Rain (Night)
        GalleryIcon(R.drawable.ic_weather_partly_cloudy_chance_rain_night, R.string.gallery_icon_partly_cloudy_chance_rain_night),
        GalleryIcon(R.drawable.ic_weather_partly_cloudy_slight_chance_rain_night, R.string.gallery_icon_partly_cloudy_slight_rain_night),

        // Fog
        GalleryIcon(R.drawable.ic_weather_fog, R.string.gallery_icon_fog),
        GalleryIcon(R.drawable.ic_weather_fog_sunny, R.string.gallery_icon_fog_sunny),
        GalleryIcon(R.drawable.ic_weather_fog_light, R.string.gallery_icon_fog_light),
        GalleryIcon(R.drawable.ic_weather_fog_dense, R.string.gallery_icon_fog_dense),
        GalleryIcon(R.drawable.ic_weather_fog_cloudy, R.string.gallery_icon_fog_cloudy),
        GalleryIcon(R.drawable.ic_weather_fog_night, R.string.gallery_icon_fog_night),
        GalleryIcon(R.drawable.ic_weather_fog_light_night, R.string.gallery_icon_fog_light_night),

        // Others
        GalleryIcon(R.drawable.ic_weather_snow, R.string.gallery_icon_snow),
        GalleryIcon(R.drawable.ic_weather_storm, R.string.gallery_icon_storm),
        GalleryIcon(R.drawable.ic_weather_wind, R.string.gallery_icon_wind),
    )

    private fun setupIconGallery() {
        val container = findViewById<FlexboxLayout>(R.id.icon_gallery_container)
        container.removeAllViews()

        for (icon in allGalleryIcons) {
            val itemView = LayoutInflater.from(this).inflate(R.layout.item_gallery_icon, container, false)
            val imageView = itemView.findViewById<ImageView>(R.id.gallery_icon_image)
            val textView = itemView.findViewById<TextView>(R.id.gallery_icon_name)

            imageView.setImageResource(icon.drawableRes)
            textView.setText(icon.stringRes)

            container.addView(itemView)
        }
    }
}
