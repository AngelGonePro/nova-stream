package net.cosmoscraft.nova

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat

/**
 * Two real, separate jobs, not just one: (1) a foreground service is what
 * keeps Android from aggressively throttling network activity when the
 * screen is off — a background browser tab doing the same segment fetches
 * has no such protection, and that throttling was a real, plausible
 * contributor to the "network error" symptoms chased all session; (2) a
 * MediaSessionCompat is what a wired headset button, a Bluetooth play/pause
 * button, or the lock screen's own transport controls actually route to —
 * without one, a hardware button press has nothing to reach.
 *
 * Deliberately does NOT duplicate the web player's own queue/transport
 * logic. A hardware button here just calls into the existing JS functions
 * (togglePlayPause(), playNextInQueue(), etc. — already in app.js) via
 * evaluateJavascript, the same way the on-screen buttons already do. This
 * service is a relay for system-level events into the same JS logic that
 * already handles everything else, not a second, parallel implementation of
 * transport state.
 *
 * Real, direct gap found from a reported "no media info in the lock screen"
 * complaint: this file built the pipes (a MediaSessionCompat, an
 * updateNotification method) but never actually connected them to real
 * data. updateNotification was never called from anywhere — confirmed
 * directly by searching the rest of the project for its name — and, a second
 * problem on top of that one, this never called mediaSession.setPlaybackState()
 * at all. Android's lock-screen media UI needs BOTH metadata and a playback
 * state set to render anything — without the state specifically, there's
 * nothing here for the system to treat as "a session worth showing controls
 * for," regardless of what metadata is or isn't set. Both are wired for real
 * now, called from a live companion-object reference the bridge can reach.
 */
class PlaybackService : Service() {
    companion object {
        const val CHANNEL_ID = "nova_playback"
        const val NOTIFICATION_ID = 1
        var evaluateJs: ((String) -> Unit)? = null
        // Live reference to the running instance — set in onCreate, cleared in
        // onDestroy. NativeAudioBridge calls into this directly rather than
        // through Android's normal bindService/Messenger machinery, since
        // this service and the WebView/bridge already live in the same
        // process (MainActivity starts it) — no cross-process boundary to
        // cross here, so the simpler direct reference is enough.
        var instance: PlaybackService? = null
    }

    private var mediaSession: MediaSessionCompat? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastTitle = "NOVA"
    private var lastArtist = "Playing"
    private var lastArtwork: android.graphics.Bitmap? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        mediaSession = MediaSessionCompat(this, "NovaSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { evaluateJs?.invoke("window.__novaTransport && window.__novaTransport('play')") }
                override fun onPause() { evaluateJs?.invoke("window.__novaTransport && window.__novaTransport('pause')") }
                override fun onSkipToNext() { evaluateJs?.invoke("window.__novaTransport && window.__novaTransport('next')") }
                override fun onSkipToPrevious() { evaluateJs?.invoke("window.__novaTransport && window.__novaTransport('prev')") }
                // Real, direct fix for a reported "media info isn't showing
                // timestamps" complaint: ACTION_SEEK_TO was missing from the
                // actions bitmask below on both playback-state updates.
                // Several Android media UI surfaces (the lock screen's
                // expanded controls especially) treat that flag's absence as
                // "this source has no seekable position," and hide the
                // progress/timestamp display entirely rather than showing a
                // static or non-interactive one. Added the flag, and wired an
                // actual handler for it now that the state claims to support
                // it — forwards to the same JS-side seek function the
                // on-screen seek bar already calls.
                override fun onSeekTo(pos: Long) { evaluateJs?.invoke("window.__novaTransport && window.__novaTransport('seek', $pos)") }
            })
            // Same gap as the notification itself — a session with no
            // playback state set has nothing for the system to treat as
            // active. STATE_PAUSED here at creation time, updated to
            // STATE_PLAYING for real once actual playback metadata arrives
            // (see updateNowPlaying below) — never left permanently unset.
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SEEK_TO)
                    .setState(PlaybackStateCompat.STATE_PAUSED, 0, 1.0f)
                    .build()
            )
            isActive = true
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Nova::PlaybackWakeLock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        wakeLock?.let { if (!it.isHeld) it.acquire(12 * 60 * 60 * 1000L /* 12h safety cap, not held-forever */) }
        startForeground(NOTIFICATION_ID, buildNotification(lastTitle, lastArtist, lastArtwork))
        return START_STICKY
    }

    /**
     * The actual fix — called from NativeAudioBridge once JS reports real
     * track info (see the new 'updateNowPlaying' message type). Updates
     * BOTH the MediaSessionCompat's metadata AND its playback state, plus
     * the visible notification, in one call — the three things that all
     * need to agree for the lock screen to show anything correct at all.
     */
    fun updateNowPlaying(title: String, artist: String, album: String?, durationMs: Long, isPlaying: Boolean, positionMs: Long, artwork: android.graphics.Bitmap?, isBuffering: Boolean = false) {
        lastTitle = title; lastArtist = artist; lastArtwork = artwork
        mediaSession?.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album ?: "")
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
                // Real gap fixed here, direct report of missing album art:
                // both the lock-screen media session AND the small
                // notification icon draw from these same two keys — ART is
                // what MediaStyle notifications actually render; ALBUM_ART is
                // the broader lock-screen/Bluetooth-display convention. Set
                // together so both surfaces pick it up; simply omitted (not
                // set to a placeholder) when null, so a track with no
                // artwork shows no image rather than a broken one.
                .apply { if (artwork != null) { putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artwork); putBitmap(MediaMetadataCompat.METADATA_KEY_ART, artwork) } }
                .build()
        )
        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SEEK_TO)
                // Real, direct fix for a confirmed feedback loop — see
                // app.js's own isBuffering comment (nativeUpdateNowPlaying)
                // for the full mechanism this closes. STATE_BUFFERING while
                // isPlaying is true but real audio hasn't actually started
                // yet tells anything watching (a connected device, the
                // system media framework) "this is expected, working on
                // it" instead of implying a stalled STATE_PLAYING that a
                // reasonable listener might try to "fix" by reissuing its
                // own play command.
                .setState(
                    if (isBuffering && isPlaying) PlaybackStateCompat.STATE_BUFFERING
                    else if (isPlaying) PlaybackStateCompat.STATE_PLAYING
                    else PlaybackStateCompat.STATE_PAUSED,
                    positionMs, 1.0f
                )
                .build()
        )
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(title, artist, artwork))
    }

    private fun buildNotification(title: String, artist: String, artwork: android.graphics.Bitmap?): Notification {
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(artist)
            .setSmallIcon(android.R.drawable.ic_media_play) // placeholder — swap for a real app icon asset before shipping
            .apply { if (artwork != null) setLargeIcon(artwork) } // the actual notification-tray thumbnail; METADATA_KEY_ART on the session is what MediaStyle reads for its own expanded artwork, this is the separate large-icon bitmap the tray view itself uses
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle().setMediaSession(mediaSession?.sessionToken))
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        mediaSession?.release()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
