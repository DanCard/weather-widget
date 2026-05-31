package com.weatherwidget.ui

import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.weatherwidget.R

class PrivacyPolicyActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density
        val padding = (20 * density).toInt()

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.background))
            fitsSystemWindows = true
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        val title = TextView(this).apply {
            text = getString(R.string.privacy_policy_title)
            setTextColor(getColor(R.color.widget_text_primary))
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val body = TextView(this).apply {
            text = getString(R.string.privacy_policy_body)
            setTextColor(getColor(R.color.widget_text_secondary))
            textSize = 15f
            setLineSpacing(4f * density, 1.0f)
        }

        container.addView(
            title,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = (16 * density).toInt() },
        )
        container.addView(
            body,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        scrollView.addView(container)
        setContentView(scrollView)
    }
}
