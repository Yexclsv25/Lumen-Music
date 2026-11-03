package com.yexclsv.localfiles

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.yexclsv.localfiles.alac.AlacRenderersFactory
import com.yexclsv.localfiles.audio.AudioFx
import com.yexclsv.localfiles.discord.DiscordPresence
import com.yexclsv.localfiles.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Background playback + lock-screen / notification controls via Media3.
 * Adds Spotify-style extra buttons (favorite, shuffle) around the transport controls.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var store: Store
    private val handler = Handler(Looper.getMainLooper())
    private var lastPresence = 0L
    private val presenceRun = Runnable { pushPresence() }

    companion object {
        const val CMD_FAV = "lumen.fav"
        const val CMD_SHUFFLE = "lumen.shuffle"
    }

    private val callback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val cmds = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(CMD_FAV, Bundle.EMPTY))
                .add(SessionCommand(CMD_SHUFFLE, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(cmds)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            val p = session.player
            when (customCommand.customAction) {
                CMD_FAV -> p.currentMediaItem?.mediaId?.let { store.toggleFavorite(it) }
                CMD_SHUFFLE -> p.shuffleModeEnabled = !p.shuffleModeEnabled
            }
            refreshButtons()
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = Store.get(this)
        val player = ExoPlayer.Builder(this, AlacRenderersFactory(this))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(open)
            .setCallback(callback)
            .build()

        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                refreshButtons()
                schedulePresence()
            }
            override fun onAudioSessionIdChanged(audioSessionId: Int) = AudioFx.attach(audioSessionId)
        })
        AudioFx.attach(player.audioSessionId)
        scope.launch {
            store.settings.collect {
                AudioFx.apply(it)
                player.setSkipSilenceEnabled(it.skipSilence)
                schedulePresence()
            }
        }
        scope.launch { store.favorites.collect { refreshButtons() } }
        refreshButtons()
    }

    /** Sends the current song to Discord at most once per second, but immediately after a quiet period. */
    private fun schedulePresence() {
        if (!DiscordPresence.supported) return
        handler.removeCallbacks(presenceRun)
        val wait = (1000L - (SystemClock.elapsedRealtime() - lastPresence)).coerceAtLeast(0L)
        handler.postDelayed(presenceRun, wait)
    }

    private fun pushPresence() {
        lastPresence = SystemClock.elapsedRealtime()
        val p = session?.player ?: return
        if (!store.settings.value.discordOn || p.mediaItemCount == 0 || p.currentMediaItem == null) {
            DiscordPresence.clear()
            return
        }
        val md = p.mediaMetadata
        DiscordPresence.update(
            md.title?.toString() ?: "Unknown title",
            md.artist?.toString() ?: "Unknown artist",
            md.albumTitle?.toString() ?: "",
            p.currentPosition,
            if (p.duration == C.TIME_UNSET) 0L else p.duration,
            p.isPlaying
        )
    }

    private fun refreshButtons() {
        val s = session ?: return
        val p = s.player
        val id = p.currentMediaItem?.mediaId
        val fav = id != null && id in store.favorites.value
        val shuffle = p.shuffleModeEnabled

        val favBtn = CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setCustomIconResId(if (fav) R.drawable.ic_heart else R.drawable.ic_heart_outline)
            .setDisplayName(if (fav) "Remove from favorites" else "Add to favorites")
            .setSessionCommand(SessionCommand(CMD_FAV, Bundle.EMPTY))
            .build()
        val shuffleBtn = CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setCustomIconResId(if (shuffle) R.drawable.ic_shuffle_on else R.drawable.ic_shuffle)
            .setDisplayName(if (shuffle) "Shuffle on" else "Shuffle off")
            .setSessionCommand(SessionCommand(CMD_SHUFFLE, Bundle.EMPTY))
            .build()
        s.setMediaButtonPreferences(ImmutableList.of(favBtn, shuffleBtn))
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
        DiscordPresence.clear()
        AudioFx.release()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
