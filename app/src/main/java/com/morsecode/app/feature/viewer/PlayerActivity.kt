package com.morsecode.app.feature.viewer

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.VideoView
import com.morsecode.app.R
import com.morsecode.app.core.model.MediaItem
import com.morsecode.app.core.media.MediaLibrary
import com.morsecode.app.core.ui.Ui
import com.morsecode.app.core.ui.W
import com.morsecode.app.core.ui.onClick
import com.morsecode.app.core.ui.onSeek
import com.morsecode.app.core.ui.pad
import com.morsecode.app.core.ui.runnable
import com.morsecode.app.core.util.D
import com.morsecode.app.core.util.Fmt
import com.morsecode.app.di.Di
import com.morsecode.app.util.ThemeColors

/**
 * Video and music player.
 *
 * Video: VideoView with a custom control row (play/pause, position, rotate, lock, subtitles,
 * volume, fullscreen). Music: the Sunflower "Now playing" layout with an up-next list, shuffle,
 * repeat and a liked heart that persists.
 */
class PlayerActivity : Activity() {

    private var videoMode = false
    private lateinit var video: VideoView
    private var mp: MediaPlayer? = null
    private val handler = Handler()
    private lateinit var scrub: SeekBar
    private lateinit var timeLabel: android.widget.TextView
    private lateinit var playButton: View
    private var duration = 0
    private var locked = false
    private var subtitles = false
    private var showVideoChrome = true
    private lateinit var chrome: View
    private var queue: List<MediaItem> = emptyList()
    private var index = 0
    private var shuffle = false
    private var repeat = false
    private lateinit var titleLabel: android.widget.TextView
    private lateinit var subtitleLabel: android.widget.TextView

    private lateinit var ticker: Runnable

    /** One ticker drives both the video scrubber and the music progress bar. */
    private fun tick() {
        if (videoMode) {
            if (video.isPlaying) {
                scrub.progress = video.currentPosition
                timeLabel.text = "${Fmt.duration(video.currentPosition.toLong())} / ${Fmt.duration(duration.toLong())}"
            }
        } else {
            val p = mp
            if (p != null && p.isPlaying) {
                scrub.progress = p.currentPosition
                timeLabel.text = "${Fmt.duration(p.currentPosition.toLong())} / ${Fmt.duration(duration.toLong())}"
            }
        }
        handler.postDelayed(ticker, 400)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeColors.applyTheme(this)
        super.onCreate(savedInstanceState)
        ticker = runnable { tick() }
        val uri = intent.getStringExtra("uri")
        val mime = intent.getStringExtra("mime") ?: ""
        videoMode = mime.startsWith("video/")
        val root = FrameLayout(this)
        root.setBackgroundColor(if (videoMode) 0xFF000000.toInt() else ThemeColors.bg(this))
        if (!videoMode) {
            val scroll = android.widget.ScrollView(this)
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            scroll.addView(col)
            root.addView(scroll, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        setContentView(root)

        if (videoMode) buildVideo(root, uri) else buildMusic(root, uri)
    }

    // ------------------------------------------------------------------ video

    private fun buildVideo(root: FrameLayout, uri: String?) {
        video = VideoView(this)
        root.addView(video, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        video.setOnPreparedListener(object : MediaPlayer.OnPreparedListener {
            override fun onPrepared(p: MediaPlayer?) {
                duration = video.duration
                scrub.max = duration
                video.start()
                updatePlayIcon()
            }
        })
        video.setOnCompletionListener(object : MediaPlayer.OnCompletionListener {
            override fun onCompletion(p: MediaPlayer?) {
                updatePlayIcon()
            }
        })
        if (uri != null) video.setVideoURI(Uri.parse(uri))

        val top = W.row(this, 6, 0)
        val back = W.iconButton(this, R.drawable.ic_back, 40, 0xFFFFFFFF.toInt(), false)
        back.onClick { finish() }
        top.addView(back)
        top.addView(W.spacer(this))
        val lockBtn = W.iconButton(this, R.drawable.ic_lock, 40, 0xFFFFFFFF.toInt(), false)
        lockBtn.onClick {
            locked = !locked
            Ui.toast(this, if (locked) "Controls locked" else "Controls unlocked")
        }
        top.addView(lockBtn)
        top.addView(W.hgap(this, 8))
        val rotate = W.iconButton(this, R.drawable.ic_rotate, 40, 0xFFFFFFFF.toInt(), false)
        rotate.onClick { video.post { video.rotation = (video.rotation + 90f) % 360f } }
        top.addView(rotate)
        val topLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        topLp.gravity = Gravity.TOP
        topLp.setMargins(D.dp(this, 12f), D.dp(this, 10f), D.dp(this, 12f), 0)
        root.addView(top, topLp)

        chrome = videoChrome()
        val chromeLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        chromeLp.gravity = Gravity.BOTTOM
        chromeLp.setMargins(D.dp(this, 14f), 0, D.dp(this, 14f), D.dp(this, 16f))
        root.addView(chrome, chromeLp)

        video.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View?) {
                if (locked) return
                showVideoChrome = !showVideoChrome
                chrome.visibility = if (showVideoChrome) View.VISIBLE else View.GONE
            }
        })
        handler.postDelayed(ticker, 400)
    }

    private fun videoChrome(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val d = GradientDrawable()
        d.setShape(GradientDrawable.RECTANGLE)
        d.setCornerRadius(D.dp(this, 16f).toFloat())
        d.setColor(0xCC141414.toInt())
        col.background = d
        col.pad(12, 10, 12, 10)

        scrub = SeekBar(this)
        scrub.max = 100
        scrub.onSeek { p -> if (scrub.isPressed) seekTo((duration * (p / 100f)).toInt()) }
        col.addView(scrub, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val row = W.row(this, 4, 0)
        val play = W.iconButton(this, R.drawable.ic_play, 40, 0xFFFFFFFF.toInt(), false)
        playButton = play
        play.onClick { toggle() }
        row.addView(play)
        row.addView(W.hgap(this, 6))
        timeLabel = W.label(this, "0:00 / 0:00", 11f, 0xFFFFFFFF.toInt(), mono = true)
        row.addView(timeLabel)
        row.addView(W.spacer(this))
        val cc = W.iconButton(this, R.drawable.ic_cc, 36, if (subtitles) ThemeColors.accentStart(this) else 0xFFFFFFFF.toInt(), false)
        cc.onClick {
            subtitles = !subtitles
            W.tint(cc, if (subtitles) ThemeColors.accentStart(this) else 0xFFFFFFFF.toInt())
            if (subtitles) Ui.toast(this, "Subtitles follow the system caption settings")
        }
        row.addView(cc)
        row.addView(W.hgap(this, 6))
        val volume = W.iconButton(this, R.drawable.ic_volume, 36, 0xFFFFFFFF.toInt(), false)
        volume.onClick {
            val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            val next = if (am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) > 0) 0 else am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, next, 0)
            Ui.toast(this, if (next == 0) "Muted" else "Volume ${next}")
        }
        row.addView(volume)
        row.addView(W.hgap(this, 6))
        val next = W.iconButton(this, R.drawable.ic_next, 36, 0xFFFFFFFF.toInt(), false)
        next.onClick { Ui.toast(this, "Next video: open it from Files") }
        row.addView(next)
        col.addView(row)
        return col
    }

    private fun seekTo(position: Int) {
        if (videoMode) {
            video.seekTo(position)
            timeLabel.text = "${Fmt.duration(position.toLong())} / ${Fmt.duration(duration.toLong())}"
        } else {
            mp?.seekTo(position)
        }
    }

    private fun toggle() {
        if (videoMode) {
            if (video.isPlaying) video.pause() else video.start()
        } else {
            val p = mp ?: return
            if (p.isPlaying) p.pause() else p.start()
        }
        updatePlayIcon()
    }

    private fun updatePlayIcon() {
        val playing = if (videoMode) video.isPlaying else (mp?.isPlaying == true)
        (playButton as? ImageView)?.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    // ------------------------------------------------------------------ music

    private fun buildMusic(root: FrameLayout, uri: String?) {
        val scroll = root.getChildAt(0) as android.widget.ScrollView
        val col = scroll.getChildAt(0) as LinearLayout
        col.pad(20, 18, 20, 26)

        val top = W.row(this)
        val back = W.iconButton(this, R.drawable.ic_chevron_down, 36)
        back.onClick { finish() }
        top.addView(back)
        top.addView(W.spacer(this))
        top.addView(W.label(this, getString(R.string.now_playing), 11f, ThemeColors.muted(this), bold = true, mono = true))
        top.addView(W.spacer(this))
        val more = W.iconButton(this, R.drawable.ic_more, 36)
        more.onClick { musicActions() }
        top.addView(more)
        col.addView(top)
        col.addView(W.gap(this, 18))

        val art = ImageView(this)
        art.setImageResource(R.drawable.ic_music)
        art.setColorFilter(ThemeColors.accentStart(this))
        art.setPadding(D.dp(this, 52f), D.dp(this, 52f), D.dp(this, 52f), D.dp(this, 52f))
        art.background = ThemeColors.accentDrawable(this, 24f)
        val artLp = LinearLayout.LayoutParams(D.dp(this, 220f), D.dp(this, 220f))
        artLp.gravity = Gravity.CENTER_HORIZONTAL
        col.addView(art, artLp)
        col.addView(W.gap(this, 20))

        titleLabel = W.label(this, intent.getStringExtra("name") ?: "Unknown track", 20f, ThemeColors.text(this), bold = true, gravity = Gravity.CENTER)
        col.addView(titleLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        subtitleLabel = W.label(this, "${Di.prefs(this).deviceName} \u00b7 local file", 12.5f, ThemeColors.text2(this), gravity = Gravity.CENTER)
        col.addView(subtitleLabel)
        col.addView(W.gap(this, 18))

        scrub = SeekBar(this)
        scrub.max = 100
        scrub.onSeek { p -> if (scrub.isPressed) seekTo((duration * (p / 100f)).toInt()) }
        col.addView(scrub)
        val times = W.row(this)
        timeLabel = W.label(this, "0:00", 11f, ThemeColors.muted(this), mono = true)
        times.addView(timeLabel)
        times.addView(W.spacer(this))
        times.addView(W.label(this, Fmt.duration(intent.getLongExtra("duration", 0L)), 11f, ThemeColors.muted(this), mono = true))
        col.addView(times)
        col.addView(W.gap(this, 14))

        val controls = W.row(this)
        val shuffleBtn = W.iconButton(this, R.drawable.ic_shuffle, 40)
        shuffleBtn.onClick {
            shuffle = !shuffle
            W.tint(shuffleBtn, if (shuffle) ThemeColors.accentStart(this) else ThemeColors.text2(this))
        }
        controls.addView(shuffleBtn)
        controls.addView(W.spacer(this))
        val prev = W.iconButton(this, R.drawable.ic_prev, 44)
        prev.onClick { step(-1) }
        controls.addView(prev)
        controls.addView(W.spacer(this))
        val play = W.iconButton(this, R.drawable.ic_pause, 60, ThemeColors.accentInk(this))
        play.background = ThemeColors.accentDrawable(this)
        playButton = play
        play.onClick { toggle() }
        controls.addView(play)
        controls.addView(W.spacer(this))
        val next = W.iconButton(this, R.drawable.ic_next, 44)
        next.onClick { step(1) }
        controls.addView(next)
        controls.addView(W.spacer(this))
        val repeatBtn = W.iconButton(this, R.drawable.ic_repeat, 40)
        repeatBtn.onClick {
            repeat = !repeat
            W.tint(repeatBtn, if (repeat) ThemeColors.accentStart(this) else ThemeColors.text2(this))
        }
        controls.addView(repeatBtn)
        col.addView(controls)
        col.addView(W.gap(this, 12))

        val liked = Di.prefs(this).likedSongs().contains(intent.getStringExtra("uri") ?: "")
        val heart = W.outlineButton(this, if (liked) getString(R.string.liked) else "Like")
        heart.onClick {
            val key = intent.getStringExtra("uri") ?: return@onClick
            val now = Di.prefs(this).toggleLiked(key)
            Ui.toast(this, if (now) "Added to Liked" else "Removed from Liked")
        }
        col.addView(heart)
        col.addView(W.gap(this, 20))

        queue = try {
            MediaLibrary(this).all(MediaLibrary.Category.MUSIC)
        } catch (t: Throwable) {
            emptyList()
        }
        col.addView(W.label(this, getString(R.string.up_next_n_songs, queue.size), 11f, ThemeColors.muted(this), bold = true, mono = true))
        col.addView(W.gap(this, 8))
        val card = W.card(this, 0, 18f, 6)
        for ((i, song) in queue.take(12).withIndex()) {
            val row = W.row(this, 10, 8)
            row.addView(W.icon(this, R.drawable.ic_music, 20, ThemeColors.accentStart(this)))
            row.addView(W.hgap(this, 10))
            val c = W.column(this)
            c.addView(W.label(this, song.displayName, 13.5f, ThemeColors.text(this), bold = true))
            c.addView(W.label(this, "${song.bucket} \u00b7 ${Fmt.size(song.size)}", 11f, ThemeColors.text2(this), mono = true))
            row.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.isClickable = true
            row.onClick { playSong(song) }
            card.addView(row)
            if (i < queue.take(12).size - 1) card.addView(W.divider(this))
        }
        col.addView(card)

        val startUri = uri ?: queue.firstOrNull()?.uri
        val startIndex = queue.indexOfFirst { it.uri == startUri }
        if (startIndex >= 0) index = startIndex
        if (uri != null) {
            startAudio(uri)
        } else if (queue.isNotEmpty()) {
            playSong(queue.first())
        } else {
            Ui.toast(this, "No music found on this device")
        }
    }

    private fun startAudio(uri: String) {
        val p = MediaPlayer()
        mp = p
        try {
            p.setDataSource(this, Uri.parse(uri))
            p.setOnPreparedListener(object : MediaPlayer.OnPreparedListener {
                override fun onPrepared(mp2: MediaPlayer?) {
                    duration = p.duration
                    scrub.max = if (duration > 0) duration else 1
                    p.start()
                    updatePlayIcon()
                }
            })
            p.setOnCompletionListener(object : MediaPlayer.OnCompletionListener {
                override fun onCompletion(mp2: MediaPlayer?) {
                    if (repeat) {
                        p.start()
                    } else step(1)
                }
            })
            p.prepareAsync()
        } catch (t: Throwable) {
            Ui.toast(this, "Could not play this file: ${t.message}")
        }
        handler.postDelayed(ticker, 400)
    }

    private fun playSong(song: MediaItem) {
        try {
            mp?.release()
        } catch (t: Throwable) {
        }
        titleLabel.text = song.displayName
        subtitleLabel.text = "${song.bucket} \u00b7 ${Fmt.size(song.size)}"
        startAudio(song.uri)
    }

    private fun step(delta: Int) {
        if (queue.isEmpty()) return
        index = if (shuffle) (0 until queue.size).random() else {
            val n = index + delta
            if (n < 0) queue.size - 1 else if (n >= queue.size) 0 else n
        }
        playSong(queue[index.coerceIn(0, queue.size - 1)])
    }

    private fun musicActions() {
        val sheet = Ui.bottomSheet(this, titleLabel.text.toString())
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val info = W.settingRow(this, getString(R.string.file_info),
            "${Fmt.duration(intent.getLongExtra("duration", 0L))} \u00b7 ${Fmt.size(intent.getLongExtra("size", 0L))}",
            null) {
            sheet.dismiss()
            Ui.info(this, getString(R.string.file_info), "${titleLabel.text}\n${subtitleLabel.text}")
        }
        val close = W.settingRow(this, "Close player", null, null) {
            sheet.dismiss()
            finish()
        }
        col.addView(info)
        col.addView(W.divider(this))
        col.addView(close)
        sheet.add(col)
        sheet.show()
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onPause() {
        super.onPause()
        if (videoMode) {
            if (video.isPlaying) video.pause()
        } else {
            val p = mp
            if (p != null && p.isPlaying) p.pause()
        }
        updatePlayIcon()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        try {
            mp?.release()
        } catch (t: Throwable) {
        }
        mp = null
        super.onDestroy()
    }
}
