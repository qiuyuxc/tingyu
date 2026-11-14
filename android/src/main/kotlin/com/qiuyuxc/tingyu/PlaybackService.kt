package com.qiuyuxc.tingyu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.service.media.MediaBrowserService

/**
 * 播放的前台服务 + 媒体会话。
 *
 * ## 为什么需要它
 *
 * 标准媒体控制使用 MediaSession + MediaStyle 通知。OriginOS 原子岛另外使用 MediaBrowser 绑定、能力位和整份 LRC
 * 的会话扩展字段；系统版本也可能限制接入应用。
 *
 * ## 这一层做什么
 *
 * - 建 MediaSession, 把歌名/艺人/时长/封面填进 MediaMetadata
 * - 建 MediaStyle 通知 (setMediaSession), 并 startForeground 挂住
 * - 接媒体按钮 (耳机线控、蓝牙、锁屏、岛上的按钮) 转给 {@link Player}
 * - 管音频焦点: 来电/别的 App 抢走就暂停, 拿回来接着放
 * - 拔耳机 (ACTION_AUDIO_BECOMING_NOISY) 就暂停, 别外放
 *
 * 它不持有 MediaPlayer —— 播放还是 {@link Player} 一个人管, 这里只是把状态 转述给系统。所以加这一层不影响原来的播放路径。
 */
class PlaybackService : MediaBrowserService(), Player.Listener {

    private var session: MediaSession? = null
    private var audio: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false

    /** 当前贴出去的封面, 换歌才重算 —— 通知每次都重建, 不能每次都解码图片。 */
    private var artwork: Bitmap? = null
    private var artworkKey: String? = ""
    private var artworkSource: ByteArray? = null
    private val lyricHandler = Handler(Looper.getMainLooper())
    private var lyricSong: Song? = null
    private var lyricSource: String? = null
    private var lyricTrack: VivoLyrics.Track? = null
    private val lyricRefresh =
        object : Runnable {
            override fun run() {
                val player = Player.get()
                if (player.current() == null || player.isDismissed) return
                push(publishNotification = false)
            }
        }

    // ------------------------------------------------------------ 音频焦点

    private val noisy =
        object : android.content.BroadcastReceiver() {
            public override fun onReceive(c: Context?, i: Intent?) {
                if (AudioManager.ACTION_AUDIO_BECOMING_NOISY == i!!.getAction()) {
                    Player.get().pause()
                }
            }
        }

    // ------------------------------------------------------------ 生命周期

    public override fun onCreate() {
        super.onCreate()
        isAlive = true
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager?
        buildChannel()
        buildSession()
        Player.get().addListener(this)
        // 拔耳机就暂停, 别突然外放
        registerReceiver(
            noisy,
            android.content.IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
        )
        // A system browser can bind while idle. Binding must not request focus or start playback.
        push(publishNotification = false)
    }

    public override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Exporting the browser must not expose the app's private service commands.
        // OEM controllers receive their transport access through onGetRoot/MediaSession.
        if (intent?.getStringExtra(INTERNAL_COMMAND_KEY) != commandToken) {
            if (Player.get().current() == null || Player.get().isDismissed) stopSelf(startId)
            return START_NOT_STICKY
        }
        val action = if (intent == null) null else intent.getAction()
        if (ACTION_TOGGLE == action) {
            Player.get().toggle()
        } else if (ACTION_NEXT == action) {
            Player.get().next()
        } else if (ACTION_PREV == action) {
            Player.get().prev()
        }
        if (Player.get().current() == null || Player.get().isDismissed) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!hasFocus) requestFocus()
        // Post immediately for startForegroundService, including while preparing audio.
        push()
        return START_NOT_STICKY
    }

    public override fun onDestroy() {
        isAlive = false
        lyricHandler.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(noisy)
        } catch (ignored: Exception) {}

        Player.get().removeListener(this)
        abandonFocus()
        if (session != null) {
            session!!.setActive(false)
            session!!.release()
            session = null
        }
        artwork = null
        stopForeground(true)
        super.onDestroy()
    }

    public override fun onBind(intent: Intent?): IBinder? {
        val request = intent ?: return null
        if (request.action == VivoLyrics.BIND_ACTION) {
            return super.onBind(Intent(request).setAction(SERVICE_INTERFACE))
        }
        return super.onBind(request)
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?,
    ): BrowserRoot? {
        val allowed =
            clientUid == Process.myUid() ||
                clientUid == Process.SYSTEM_UID ||
                clientPackageName == "com.vivo.musicwidgetmix" ||
                clientPackageName == "com.android.systemui"
        if (!allowed) return null
        push(publishNotification = false)
        return BrowserRoot("playback", null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowser.MediaItem>>,
    ) {
        // Only lyric/transport capabilities are advertised; no remote library is exposed.
        result.sendResult(mutableListOf())
    }

    // ------------------------------------------------------------ 会话与通知

    private fun buildChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch =
            NotificationChannel(
                CHANNEL_ID,
                "播放控制",
                NotificationManager.IMPORTANCE_LOW,
            )
        ch.setDescription("正在播放的音乐")
        ch.setShowBadge(false)
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC)
        // 这条通道只放媒体控制, 不要震动也不要提示音
        ch.enableVibration(false)
        ch.setSound(null, null)
        nm.createNotificationChannel(ch)
    }

    private fun buildSession() {
        session = MediaSession(this, "Tingyu")
        session!!.setFlags(
            (MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        )
        session!!.setCallback(
            object : MediaSession.Callback() {
                public override fun onPlay() {
                    Player.get().play()
                }

                public override fun onPause() {
                    Player.get().pause()
                }

                public override fun onStop() {
                    Player.get().stop()
                }

                public override fun onSkipToNext() {
                    Player.get().next()
                }

                public override fun onSkipToPrevious() {
                    Player.get().prev()
                }

                public override fun onSeekTo(pos: Long) {
                    Player.get().seekTo(pos.toInt())
                }
            }
        )
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        session!!.setSessionActivity(open)
        setSessionToken(session!!.sessionToken)
    }

    private fun action(a: String?): PendingIntent? {
        val i =
            Intent(this, PlaybackService::class.java)
                .setAction(a)
                .putExtra(INTERNAL_COMMAND_KEY, commandToken)
        return PendingIntent.getService(
            this,
            a!!.hashCode(),
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 把 Player 的当前状态转述给系统, 并把通知重新贴一遍。 */
    private fun push(publishNotification: Boolean = true) {
        if (session == null) return
        val player = Player.get()
        val s = player.current()?.takeUnless { player.isDismissed }
        val playing = player.isPlaying
        val pos = player.position()
        val dur = player.duration()
        session!!.isActive = s != null
        val lyrics = lyricsFor(s)

        // --- 元数据 ---
        val md =
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, if (s == null) "" else s.title)!!
                .putString(MediaMetadata.METADATA_KEY_ARTIST, if (s == null) "" else s.artist)!!
                .putString(MediaMetadata.METADATA_KEY_ALBUM, if (s == null) "" else s.album)!!
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, lyrics?.mediaId.orEmpty())
                .putLong(VivoLyrics.SUPPORT_EVENT, VivoLyrics.CAPABILITIES)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, Math.max(0, dur).toLong())
        if (s != null) {
            val art = coverOf(s)
            if (art != null) md!!.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art)
        } else {
            artwork = null
            artworkKey = ""
        }
        session!!.setMetadata(md!!.build())

        // --- 播放状态 ---
        val state =
            if (s == null) PlaybackState.STATE_STOPPED
            else if (player.isLoading) PlaybackState.STATE_BUFFERING
            else (if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED)
        session!!.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    (PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_SEEK_TO or
                        PlaybackState.ACTION_STOP)
                )!!
                .setState(state, pos.toLong(), if (playing) 1f else 0f)!!
                .build()
        )
        pushLyrics()
        lyricHandler.removeCallbacks(lyricRefresh)
        if (s != null) {
            lyricHandler.postDelayed(lyricRefresh, VivoLyrics.REFRESH_MS)
        }

        if (s == null) {
            stopForeground(true)
            return
        }
        if (!publishNotification) return

        // --- 通知 ---
        val n = buildNotification(s, playing)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?
        if (nm != null) nm.notify(NOTIF_ID, n)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIF_ID,
                    n,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            MainActivity.diagLine("startForeground 失败: " + e)
        }
    }

    private fun lyricsFor(song: Song?): VivoLyrics.Track? {
        if (song == null) {
            lyricSong = null
            lyricSource = null
            lyricTrack = null
        } else if (
            song !== lyricSong ||
                song.lyrics != lyricSource ||
                lyricTrack?.mediaId != "${song.title}|${song.artist}"
        ) {
            lyricSong = song
            lyricSource = song.lyrics
            lyricTrack = VivoLyrics.track(song.title, song.artist, song.lyrics)
        }
        return lyricTrack
    }

    private fun pushLyrics() {
        val player = Player.get()
        val song = player.current()?.takeUnless { player.isDismissed }
        val fields = lyricsFor(song)?.extras().orEmpty()
        // An empty bundle clears the previous song; never resend its lyrics for a new track.
        session?.setExtras(
            Bundle().apply { fields.forEach { (key, value) -> putString(key, value) } }
        )
    }

    private fun buildNotification(s: Song?, playing: Boolean): Notification {
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val b =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                Notification.Builder(this, CHANNEL_ID)
            else Notification.Builder(this)
        b.setSmallIcon(R.drawable.ic_notif_music)
            .setContentTitle(if (s == null) "没有在播放" else s.title)
            .setContentText(if (s == null) "" else s.artist)
            .setContentIntent(open)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(playing)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(
                            this,
                            R.drawable.ic_media_prev,
                        ),
                        "上一首",
                        action(ACTION_PREV),
                    )
                    .build()
            )
            .addAction(
                Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(
                            this,
                            if (playing) R.drawable.ic_media_pause else R.drawable.ic_media_play,
                        ),
                        if (playing) "暂停" else "播放",
                        action(ACTION_TOGGLE),
                    )
                    .build()
            )
            .addAction(
                Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(
                            this,
                            R.drawable.ic_media_next,
                        ),
                        "下一首",
                        action(ACTION_NEXT),
                    )
                    .build()
            )

        val art = artwork
        if (art != null) b.setLargeIcon(art)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && session != null) {
            b.setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session!!.getSessionToken())!!
                    .setShowActionsInCompactView(0, 1, 2)
            )
        }
        return b.build()
    }

    /** Reuse the same thumbnails as the app; cold artwork never blocks media-session updates. */
    private fun coverOf(s: Song?): Bitmap? {
        val key = SongArtwork.key(requireNotNull(s), 384)
        if (key == artworkKey && (!s.isPlatform || s.platformArtwork === artworkSource))
            return artwork
        artworkKey = key
        artworkSource = s.platformArtwork
        artwork = SongArtwork.cached(s, 384)
        if (artwork == null) {
            SongArtwork.load(this, s, 384) { bitmap ->
                if (
                    bitmap != null &&
                        session != null &&
                        artworkKey == key &&
                        Player.get().current()?.key == s.key
                ) {
                    artwork = bitmap
                    lyricHandler.post { push() }
                }
            }
        }
        return artwork
    }

    private fun requestFocus() {
        if (audio == null) return
        val l =
            object : AudioManager.OnAudioFocusChangeListener {
                public override fun onAudioFocusChange(change: Int) {
                    when (change) {
                        AudioManager.AUDIOFOCUS_LOSS,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                            hasFocus = false
                            Player.get().pause()
                        }
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                            // 导航播报之类: 压低一点就行, 不用停
                            Player.get().duck(true)
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            hasFocus = true
                            Player.get().duck(false)
                        }
                        else -> {}
                    }
                }
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest =
                AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)!!
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)!!
                            .build()
                    )
                    .setOnAudioFocusChangeListener(l)
                    .setWillPauseWhenDucked(false)
                    .build()
            hasFocus =
                (audio!!.requestAudioFocus(requireNotNull(focusRequest)) ==
                    AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        } else {
            hasFocus =
                (audio!!.requestAudioFocus(
                    l,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN,
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        }
    }

    private fun abandonFocus() {
        if (audio == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
            focusRequest?.let { audio!!.abandonAudioFocusRequest(it) }
        }
    }

    // ------------------------------------------------------------ Player 回调

    public override fun onPlayerChanged() {
        push()
    }

    companion object {

        private val CHANNEL_ID = "playback"
        private val NOTIF_ID = 1001

        private val ACTION_TOGGLE = "com.qiuyuxc.tingyu.action.TOGGLE"
        private val ACTION_NEXT = "com.qiuyuxc.tingyu.action.NEXT"
        private val ACTION_PREV = "com.qiuyuxc.tingyu.action.PREV"
        private const val INTERNAL_COMMAND_KEY = "com.qiuyuxc.tingyu.internal.PLAYBACK_COMMAND"
        private val commandToken = java.util.UUID.randomUUID().toString()

        var isAlive = false
            private set

        // ------------------------------------------------------------ 对外入口

        /** 开始播放时叫一下。已经在跑就什么都不做。 */
        @JvmStatic
        fun ensure(ctx: Context?) {
            try {
                val i =
                    Intent(ctx, PlaybackService::class.java)
                        .putExtra(INTERNAL_COMMAND_KEY, commandToken)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx!!.startForegroundService(i)
                } else {
                    ctx!!.startService(i)
                }
            } catch (e: Exception) {
                MainActivity.diagLine("播放服务起不来: " + e)
            }
        }

        /** 停止播放时叫一下, 把通知收掉。 */
        @JvmStatic
        fun shutdown(ctx: Context?) {
            try {
                ctx!!.stopService(Intent(ctx, PlaybackService::class.java))
            } catch (ignored: Exception) {}
        }
    }
}
