package com.weatherwidget.ui

import android.os.Bundle
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
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
    }
}
