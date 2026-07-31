package com.weatherwidget.data.repository

import android.content.Context
import com.weatherwidget.widget.WidgetStateManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

fun interface PersonalStationWeightProvider {
    fun currentWeight(): Double
}

@Singleton
class WidgetPersonalStationWeightProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : PersonalStationWeightProvider {
    override fun currentWeight(): Double = WidgetStateManager(context).getPersonalStationWeight()
}
