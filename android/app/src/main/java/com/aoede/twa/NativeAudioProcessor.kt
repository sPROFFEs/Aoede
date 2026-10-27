package com.aoede.twa

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Always attached to the one audio sink. Sampling only reads PCM; toggling it never rebuilds audio.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NativeAudioProcessor : BaseAudioProcessor() {
    @Volatile var gain = 1f
    @Volatile var fade = 1f
    @Volatile var sampling = false
    @Volatile
    var levels: FloatArray? = null
        private set

    private var multiplier = 1f
    private var rate = 48000
    private var channels = 2
    private var channel = 0
    private var samples = 0
    private var coefficients = DoubleArray(5)
    private val first = DoubleArray(5)
    private val second = DoubleArray(5)

    override fun onConfigure(format: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (format.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        rate = format.sampleRate
        channels = format.channelCount
        coefficients =
            doubleArrayOf(80.0, 250.0, 800.0, 2500.0, 5000.0)
                .map { 2 * cos(2 * Math.PI * it.coerceAtMost(rate * .45) / rate) }
                .toDoubleArray()
        return format
    }

    override fun onFlush() {
        samples = 0
        channel = 0
        first.fill(0.0)
        second.fill(0.0)
        levels = null
    }

    override fun queueInput(input: ByteBuffer) {
        if (!input.hasRemaining()) return
        val output = replaceOutputBuffer(input.remaining()).order(ByteOrder.nativeOrder())
        input.order(ByteOrder.nativeOrder())
        val target =
            (gain.takeIf { it.isFinite() } ?: 1f).coerceIn(0f, 16f) *
                (fade.takeIf { it.isFinite() } ?: 1f).coerceIn(0f, 1f)
        if (!sampling && kotlin.math.abs(multiplier - 1f) < .0001f && target == 1f) {
            output.put(input)
            output.flip()
            return
        }
        while (input.remaining() >= 2) {
            val value = input.short
            multiplier += (target - multiplier) * (1f / (rate * .02f)).coerceAtMost(1f)
            output.putShort(
                (value * multiplier)
                    .toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            )
            if (sampling && channel == 0) {
                val normalized = value / 32768.0
                for (i in 0..4) {
                    val next = normalized + coefficients[i] * first[i] - second[i]
                    second[i] = first[i]
                    first[i] = next
                }
                samples++
                if (samples == 512) {
                    levels =
                        FloatArray(5) { i ->
                            (sqrt(
                                    (first[i] * first[i] + second[i] * second[i] -
                                            coefficients[i] * first[i] * second[i])
                                        .coerceAtLeast(0.0)
                                ) / samples * 4)
                                .toFloat()
                                .coerceIn(0f, 1f)
                        }
                    samples = 0
                    first.fill(0.0)
                    second.fill(0.0)
                }
            }
            channel = (channel + 1) % channels.coerceAtLeast(1)
        }
        output.flip()
    }

    companion object {
        internal fun replayGain(db: Double, peak: Double): Float {
            if (!db.isFinite() || !peak.isFinite() || peak < 0) return 1f
            val desired = 10.0.pow((db - 1.0).coerceIn(-60.0, 24.0) / 20.0)
            return minOf(desired, .99 / peak.coerceAtLeast(.000001)).toFloat().coerceIn(0f, 16f)
        }
    }
}
