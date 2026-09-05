package com.grayvines.runway

import android.app.Application

class RunwayApp : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }
}
