package io.github.alisial786.watchgpt

import android.app.Application
import io.github.alisial786.watchgpt.security.ReleaseTree
import timber.log.Timber

class WatchAiApp : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        Timber.plant(if (BuildConfig.DEBUG) Timber.DebugTree() else ReleaseTree())
    }
}
