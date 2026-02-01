package com.weatherwidget.ui

import android.os.Bundle
import android.widget.ImageButton
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.weatherwidget.R
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class FeatureTourActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_feature_tour)

        setupViews()
    }

    private fun setupViews() {
        // Back button
        findViewById<ImageButton>(R.id.back_button).setOnClickListener {
            finish()
        }

        // Load animated GIF for API toggle demonstration
        val apiToggleImage = findViewById<ImageView>(R.id.api_toggle_image)
        Glide.with(this)
            .asGif()
            .load(R.drawable.tour_api_toggle)
            .into(apiToggleImage)
    }
}
