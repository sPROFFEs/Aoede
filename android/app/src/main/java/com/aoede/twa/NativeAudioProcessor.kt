package com.aoede.twa

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.log10
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
    private var coefficients = DoubleArray(4)
    private var filters = DoubleArray(0)
    private val power = DoubleArray(5)
    private var analyzing = false

    override fun onConfigure(format: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (format.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        return format
    }

    override fun onFlush() {
        rate = inputAudioFormat.sampleRate.takeIf { it > 0 } ?: 48000
        channels = inputAudioFormat.channelCount.takeIf { it > 0 } ?: 2
        coefficients =
            doubleArrayOf(150.0, 400.0, 1200.0, 4000.0)
                .map { 1 - exp(-2 * Math.PI * it.coerceAtMost(rate * .45) / rate) }
                .toDoubleArray()
        samples = 0
        channel = 0
        filters = DoubleArray(channels.coerceAtLeast(1) * 4)
        power.fill(0.0)
        analyzing = sampling
        levels = null
    }

    override fun queueInput(input: ByteBuffer) {
        if (!input.hasRemaining()) return
        val output = replaceOutputBuffer(input.remaining()).order(ByteOrder.nativeOrder())
        input.order(ByteOrder.nativeOrder())
        if (analyzing != sampling) {
            analyzing = sampling
            samples = 0
            filters.fill(0.0)
            power.fill(0.0)
            levels = null
        }
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
            if (analyzing) {
                val normalized = value / 32768.0
                var previous = 0.0
                for (i in 0..3) {
                    val index = channel * 4 + i
                    filters[index] += coefficients[i] * (normalized - filters[index])
                    val band = filters[index] - previous
                    power[i] += band * band
                    previous = filters[index]
                }
                power[4] += (normalized - previous) * (normalized - previous)
                samples++
                if (samples >= (rate / 20).coerceAtLeast(1) * channels.coerceAtLeast(1)) {
                    // Broad-band RMS sees music between bins and either stereo channel.
                    levels =
                        FloatArray(5) { i ->
                            ((20 * log10(sqrt(power[i] / samples).coerceAtLeast(.000001)) + 60) /
                                    60)
                                .toFloat()
                                .coerceIn(0f, 1f)
                        }
                    samples = 0
                    power.fill(0.0)
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
