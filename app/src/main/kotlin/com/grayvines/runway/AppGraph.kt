package com.grayvines.runway

import android.content.Context
import com.grayvines.runway.system.apps.AppRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Application-wide singletons. */
class AppGraph(private val context: Context) {
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val appRepository: AppRepository by lazy { AppRepository(context, appScope) }
}

val Context.appGraph: AppGraph
    get() = (applicationContext as RunwayApp).graph
