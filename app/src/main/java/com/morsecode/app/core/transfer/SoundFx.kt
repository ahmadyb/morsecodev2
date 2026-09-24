package com.morsecode.app.core.transfer

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.morsecode.app.di.Di

/**
 * Three short synthesised tones - connected, transfer failed, success - gated by the Sounds
 * setting. Tones are generated on the fly (a few hundred bytes of PCM), which keeps the APK
 * tiny and avoids shipping audio assets.
 */
object SoundFx {

    private const val SAMPLE_RATE = 22050

    fun connected(ctx: Context) = play(ctx, intArrayOf(660, 880), 90)
    fun success(ctx: Context) = play(ctx, intArrayOf(880, 1175), 110)
    fun failed(ctx: Context) = play(ctx, intArrayOf(320, 240), 150)

    private fun play(ctx: Context, notes: IntArray, durationMs: Int) {
        if (!Di.prefs(ctx).sounds) return
        Thread({
            var track: AudioTrack? = null
            try {
                val total = SAMPLE_RATE * (durationMs * notes.size) / 1000
                val samples = ShortArray(total)
                var offset = 0
                for (freq in notes) {
                    val count = SAMPLE_RATE * durationMs / 1000
                    for (i in 0 until count) {
                        val t = i.toDouble() / SAMPLE_RATE
                        val envelope = 1.0 - (i.toDouble() / count)      // simple decay
                        samples[offset + i] = (kotlin.math.sin(2 * Math.PI * freq * t) * 7000 * envelope).toInt().toShort()
                    }
                    offset += count
                }
                track = AudioTrack(
                    AudioManager.STREAM_NOTIFICATION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    samples.size * 2,
                    AudioTrack.MODE_STATIC
                )
                track.write(samples, 0, samples.size)
                track.play()
                Thread.sleep((durationMs * notes.size + 80).toLong())
            } catch (t: Throwable) {
                // sounds are a nicety - never let them break a transfer
            } finally {
                try { track?.release() } catch (ignored: Throwable) {}
            }
        }, "mc-sound").start()
    }
}
