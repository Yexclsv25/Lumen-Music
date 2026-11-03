package com.yexclsv.localfiles.discord

import android.app.Activity
import android.content.Intent

/** Stand-in used when the Discord Social SDK .aar is not in app/libs. */
object DiscordPresence {
    const val supported = false
    var status: String = "SDK not installed"
        private set

    fun attach(activity: Activity) {}
    fun connect(activity: Activity, onStatus: (String) -> Unit) = onStatus("Add the Discord Social SDK first")
    fun onNewIntent(intent: Intent?) {}
    fun update(title: String, artist: String, album: String, positionMs: Long, durationMs: Long, playing: Boolean) {}
    fun clear() {}
}
