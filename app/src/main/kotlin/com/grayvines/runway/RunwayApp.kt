package com.grayvines.runway

import android.app.Application
import android.content.res.Configuration

class RunwayApp : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        graph.start()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        graph.appRepository.configurationChanged(newConfig)
        graph.widgets.configurationChanged(newConfig)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_BACKGROUND) graph.widgets.invalidateProviders()
    }
}
