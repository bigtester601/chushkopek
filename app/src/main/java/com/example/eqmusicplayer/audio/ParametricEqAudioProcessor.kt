package com.example.eqmusicplayer.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.BaseAudioProcessor
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

@UnstableApi
class ParametricEqAudioProcessor : BaseAudioProcessor() {

    data class Band(
        val frequencyHz: Float,
        val q: Float,
        val gainDb: Float
    )

    private data class Coeff(
        val b0: Float,
        val b1: Float,
        val b2: Float,
        val a1: Float,
        val a2: Float
    )

    private data class FilterState(
        var x1: Float = 0f,
        var x2: Float = 0f,
        var y1: Float = 0f,
        var y2: Float = 0f
    )

    @Volatile
    private var bands: List<Band> = listOf(
        Band(frequencyHz = 120f, q = 1f, gainDb = 0f),
        Band(frequencyHz = 1000f, q = 1f, gainDb = 0f),
        Band(frequencyHz = 5000f, q = 1f, gainDb = 0f)
    )

    @Volatile
    private var coeffs: List<Coeff> = emptyList()

    @Volatile
    private var coeffsDirty: Boolean = true

    private var channelStates: Array<Array<FilterState>> = emptyArray()

    fun setBands(newBands: List<Band>) {
        bands = newBands
        coeffsDirty = true
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun onFlush() {
        resetStates()
    }

    override fun onReset() {
        channelStates = emptyArray()
        coeffs = emptyList()
        coeffsDirty = true
    }

    override fun queueInput(inputBuffer: java.nio.ByteBuffer) {
        if (!inputBuffer.hasRemaining()) {
            return
        }

        ensureFiltersReady()

        val outputBuffer = replaceOutputBuffer(inputBuffer.remaining())
        val localCoeffs = coeffs
        val channels = inputAudioFormat.channelCount

        while (inputBuffer.remaining() >= 2) {
            val inSample = inputBuffer.short.toInt().toFloat()
            val channelIndex = ((outputBuffer.position() / 2) % channels)

            var value = inSample
            for (bandIndex in localCoeffs.indices) {
                val c = localCoeffs[bandIndex]
                val state = channelStates[channelIndex][bandIndex]
                val y = c.b0 * value + c.b1 * state.x1 + c.b2 * state.x2 - c.a1 * state.y1 - c.a2 * state.y2

                state.x2 = state.x1
                state.x1 = value
                state.y2 = state.y1
                state.y1 = y
                value = y
            }

            val clipped = value.coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort()
            outputBuffer.putShort(clipped)
        }

        outputBuffer.flip()
    }

    private fun ensureFiltersReady() {
        if (coeffsDirty) {
            coeffs = bands.map { band ->
                peakingEqCoefficients(
                    sampleRate = inputAudioFormat.sampleRate,
                    centerFrequencyHz = band.frequencyHz,
                    q = band.q,
                    gainDb = band.gainDb
                )
            }
            coeffsDirty = false
            resetStates()
        }

        val needsStateReset = channelStates.size != inputAudioFormat.channelCount ||
            channelStates.firstOrNull()?.size != coeffs.size

        if (needsStateReset) {
            resetStates()
        }
    }

    private fun resetStates() {
        val channels = inputAudioFormat.channelCount
        val bandCount = coeffs.size
        if (channels <= 0 || bandCount < 0) {
            channelStates = emptyArray()
            return
        }
        channelStates = Array(channels) { Array(bandCount) { FilterState() } }
    }

    private fun peakingEqCoefficients(
        sampleRate: Int,
        centerFrequencyHz: Float,
        q: Float,
        gainDb: Float
    ): Coeff {
        val sr = sampleRate.toFloat().coerceAtLeast(1f)
        val nyquistSafeFreq = centerFrequencyHz.coerceIn(20f, (sr / 2f) - 10f)
        val safeQ = q.coerceAtLeast(0.1f)
        val a = 10.0.pow(gainDb / 40.0).toFloat()
        val w0 = (2.0 * PI * (nyquistSafeFreq / sr)).toFloat()
        val alpha = (sin(w0.toDouble()) / (2f * safeQ)).toFloat()
        val cosW0 = cos(w0.toDouble()).toFloat()

        val b0 = 1f + alpha * a
        val b1 = -2f * cosW0
        val b2 = 1f - alpha * a
        val a0 = 1f + alpha / a
        val a1 = -2f * cosW0
        val a2 = 1f - alpha / a

        return Coeff(
            b0 = b0 / a0,
            b1 = b1 / a0,
            b2 = b2 / a0,
            a1 = a1 / a0,
            a2 = a2 / a0
        )
    }
}
