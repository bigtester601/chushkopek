package com.example.eqmusicplayer.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.example.eqmusicplayer.audio.ParametricEqAudioProcessor

@UnstableApi
object PlaybackRepository {

    @Volatile
    private var player: ExoPlayer? = null

    @Volatile
    private var eqProcessor: ParametricEqAudioProcessor? = null

    @Volatile
    private var currentBands: List<ParametricEqAudioProcessor.Band> = listOf(
        ParametricEqAudioProcessor.Band(frequencyHz = 60f, q = 1f, gainDb = 0f),
        ParametricEqAudioProcessor.Band(frequencyHz = 120f, q = 1f, gainDb = 0f),
        ParametricEqAudioProcessor.Band(frequencyHz = 250f, q = 1f, gainDb = 0f),
        ParametricEqAudioProcessor.Band(frequencyHz = 500f, q = 1f, gainDb = 0f),
        ParametricEqAudioProcessor.Band(frequencyHz = 1000f, q = 1f, gainDb = 0f),
        ParametricEqAudioProcessor.Band(frequencyHz = 2500f, q = 1f, gainDb = 0f),
        ParametricEqAudioProcessor.Band(frequencyHz = 6000f, q = 1f, gainDb = 0f),
        ParametricEqAudioProcessor.Band(frequencyHz = 16_000f, q = 1f, gainDb = 0f)
    )

    @OptIn(UnstableApi::class)
    fun getPlayer(context: Context): ExoPlayer {
        player?.let { return it }

        return synchronized(this) {
            player?.let { return@synchronized it }

            val processor = ParametricEqAudioProcessor().also {
                it.setBands(currentBands)
            }
            eqProcessor = processor

            val renderersFactory = object : DefaultRenderersFactory(context.applicationContext) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean
                ): AudioSink {
                    return DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .setAudioProcessors(arrayOf(processor))
                        .build()
                }
            }

            ExoPlayer.Builder(context.applicationContext, renderersFactory)
                .build()
                .apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                            .build(),
                        true
                    )
                }
                .also { created ->
                    player = created
                }
        }
    }

    @UnstableApi
    fun updateEqBands(bands: List<ParametricEqAudioProcessor.Band>) {
        currentBands = bands
        eqProcessor?.setBands(bands)
    }

    fun release() {
        synchronized(this) {
            player?.release()
            player = null
            eqProcessor = null
        }
    }
}
