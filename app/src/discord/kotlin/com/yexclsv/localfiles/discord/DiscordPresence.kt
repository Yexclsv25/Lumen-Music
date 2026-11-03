package com.yexclsv.localfiles.discord

import android.content.Intent
import com.discord.socialsdk.*
import com.yexclsv.localfiles.data.Store

/**
 * Discord Rich Presence through the Discord Social SDK.
 * Compiled only when the SDK .aar is present in app/libs.
 * NOTE: written against Discord's published Android/Kotlin guide; if a call does not match
 * the SDK version you downloaded, only this file needs adjusting.
 */
object DiscordPresence {
    val supported = true
    var status: String = "Not connected"
        private set

    private class State(
        val title: String, val artist: String, val album: String,
        val positionMs: Long, val durationMs: Long, val playing: Boolean
    )

    private var client: Client? = null
    private var ready = false
    private var appId = 0L
    private var pending: State? = null
    private var store: Store? = null
    private var onStatusCb: ((String) -> Unit)? = null

    fun attach(activity: android.app.Activity) {
        val id = DiscordConfig.appId(activity)
        if (id == 0L) {
            status = "Set discordAppId in gradle.properties"
            return
        }
        appId = id
        store = Store.get(activity)
        DiscordSocialSdkInit.setEngineActivity(activity)
        if (client != null) return

        val c = Client()
        c.setApplicationId(id)
        c.setStatusChangedCallback { s, _, _ ->
            ready = s == Client.Status.Ready
            status = if (ready) "Connected" else "Status: $s"
            onStatusCb?.invoke(status)
            if (ready) pending?.let { send(c, it) }
        }
        client = c

        val saved = store?.getStr("discordToken").orEmpty()
        if (saved.isNotEmpty()) {
            status = "Connecting..."
            c.updateToken(AuthorizationTokenType.Bearer, saved) { _ -> c.connect() }
        }
    }

    fun connect(activity: android.app.Activity, onStatus: (String) -> Unit) {
        attach(activity)
        val c = client ?: return onStatus(status)
        onStatusCb = onStatus
        val verifier = c.createAuthorizationCodeVerifier()
        val args = AuthorizationArgs()
        args.setClientId(appId)
        args.setScopes(Client.getDefaultPresenceScopes())
        args.setCodeChallenge(verifier.challenge())
        status = "Waiting for Discord..."
        c.authorize(args) { result, code, redirectUri ->
            if (!result.successful()) {
                status = "Login failed"
                onStatus(status)
                return@authorize
            }
            c.getToken(appId, code, verifier.verifier(), redirectUri) { r2, accessToken, _, _, _, _ ->
                if (!r2.successful()) {
                    status = "Token request failed"
                    onStatus(status)
                    return@getToken
                }
                store?.putStr("discordToken", accessToken)
                c.updateToken(AuthorizationTokenType.Bearer, accessToken) { _ -> c.connect() }
            }
        }
    }

    fun onNewIntent(intent: Intent?) {
        // The SDK completes the browser login through the activity registered in attach().
    }

    fun update(title: String, artist: String, album: String, positionMs: Long, durationMs: Long, playing: Boolean) {
        val s = State(title, artist, album, positionMs, durationMs, playing)
        pending = s
        val c = client ?: return
        if (ready) send(c, s)
    }

    private fun send(c: Client, p: State) {
        val a = Activity()
        a.setType(ActivityTypes.Listening)
        a.setDetails(p.title)
        a.setState(if (p.playing) p.artist else p.artist + " (paused)")
        val assets = ActivityAssets()
        assets.setLargeImage("lumen")
        if (p.album.isNotBlank()) assets.setLargeText(p.album)
        a.setAssets(assets)
        if (p.playing && p.durationMs > 0) {
            val start = System.currentTimeMillis() - p.positionMs
            val ts = ActivityTimestamps()
            ts.setStart(start)
            ts.setEnd(start + p.durationMs)
            a.setTimestamps(ts)
        }
        c.updateRichPresence(a) { _ -> }
    }

    fun clear() {
        pending = null
        if (ready) client?.clearRichPresence()
    }
}
