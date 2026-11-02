package com.yexclsv.localfiles

import android.app.Application
import android.util.Log
import java.io.File

/** Saves the last crash stack trace so the app can show it on next launch. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                File(filesDir, "crash.txt").writeText(Log.getStackTraceString(e))
            } catch (_: Throwable) {
            }
            old?.uncaughtException(t, e)
        }
    }
}
