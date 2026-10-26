package nomi.android.traffic.beacon

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Plays the beeps on its own AudioTrack and thread. It never touches the
 * speech pipe and never requests audio focus.
 */
class BeaconTone(private val nextCue: () -> BeaconCue?) {

    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null

    fun start() {
        if (running) return
        running = true
        val minBytes = AudioTrack.getMinBufferSize(
            RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val audio = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBytes, RATE))
            .build()
        track = audio
        audio.play()
        thread = Thread({ loop(audio) }, "nomi-beacon").apply { start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        try {
            track?.stop()
        } catch (_: Throwable) {
        }
        track?.release()
        track = null
    }

    private fun loop(audio: AudioTrack) {
        while (running) {
            val cue = try {
                nextCue()
            } catch (_: Throwable) {
                null
            }
            if (cue == null) {
                if (!pause(IDLE_MILLIS)) return
                continue
            }
            val pcm = when {
                cue.arrived -> arrivedPcm()
                cue.behind -> beep(BEHIND_HZ, BEHIND_MILLIS, cue.left, cue.right)
                else -> beep(FRONT_HZ, FRONT_MILLIS, cue.left, cue.right)
            }
            try {
                audio.write(pcm, 0, pcm.size)
            } catch (_: Throwable) {
                return
            }
            if (!pause(cue.intervalMillis)) return
        }
    }

    private fun pause(millis: Long): Boolean = try {
        Thread.sleep(millis)
        true
    } catch (_: InterruptedException) {
        false
    }

    private fun arrivedPcm(): ShortArray {
        val gap = ShortArray(RATE * 2 * 80 / 1000)
        return beep(ARRIVED_HZ, 90, 1f, 1f) + gap + beep(ARRIVED_HZ, 90, 1f, 1f) + gap +
            beep(ARRIVED_HZ * 1.25, 160, 1f, 1f)
    }

    companion object {
        private const val RATE = 44_100
        private const val FRONT_HZ = 1200.0
        private const val FRONT_MILLIS = 70
        private const val BEHIND_HZ = 440.0
        private const val BEHIND_MILLIS = 160
        private const val ARRIVED_HZ = 1600.0
        private const val IDLE_MILLIS = 300L
        private const val VOLUME = 0.6
        private const val FADE_MILLIS = 6

        /** Interleaved stereo PCM with short fades so the beep does not click. */
        fun beep(hz: Double, millis: Int, left: Float, right: Float): ShortArray {
            val frames = RATE * millis / 1000
            val fade = RATE * FADE_MILLIS / 1000
            val out = ShortArray(frames * 2)
            for (i in 0 until frames) {
                val envelope = min(1.0, min(i, frames - 1 - i).toDouble() / fade)
                val s = sin(2 * PI * hz * i / RATE) * envelope * VOLUME * Short.MAX_VALUE
                out[i * 2] = (s * left).toInt().toShort()
                out[i * 2 + 1] = (s * right).toInt().toShort()
            }
            return out
        }
    }
}
