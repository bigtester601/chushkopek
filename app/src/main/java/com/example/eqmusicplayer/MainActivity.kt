package com.example.eqmusicplayer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.example.eqmusicplayer.audio.ParametricEqAudioProcessor
import com.example.eqmusicplayer.soundcloud.SoundCloudClient
import com.example.eqmusicplayer.soundcloud.SoundCloudClient.PkceSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.exp
import kotlin.math.ln

@UnstableApi
class MainActivity : ComponentActivity() {

    private lateinit var player: ExoPlayer
    private val eqProcessor = ParametricEqAudioProcessor()
    private val soundCloudClient = SoundCloudClient()
    private var soundCloudAuthCallback by mutableStateOf<SoundCloudAuthCallback?>(null)

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleSoundCloudCallback(intent)

        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(eqProcessor))
                    .build()
            }
        }

        player = ExoPlayer.Builder(this, renderersFactory)
            .build()
            .apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                        .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    true
                )
            }

        setContent {
            MaterialTheme {
                MusicPlayerScreen(
                    player = player,
                    eqProcessor = eqProcessor,
                    soundCloudClient = soundCloudClient,
                    soundCloudClientId = getString(R.string.soundcloud_client_id),
                    soundCloudRedirectUri = getString(R.string.soundcloud_redirect_uri),
                    soundCloudClientSecret = getString(R.string.soundcloud_client_secret),
                    authCallback = soundCloudAuthCallback,
                    onAuthCallbackConsumed = { soundCloudAuthCallback = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSoundCloudCallback(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        player.release()
    }

    private fun handleSoundCloudCallback(intent: Intent?) {
        val callbackUri = intent?.data ?: return
        val code = callbackUri.getQueryParameter("code")
        val state = callbackUri.getQueryParameter("state")
        val error = callbackUri.getQueryParameter("error")
        val errorDescription = callbackUri.getQueryParameter("error_description")

        if (code != null || error != null) {
            soundCloudAuthCallback = SoundCloudAuthCallback(
                code = code,
                state = state,
                error = error,
                errorDescription = errorDescription
            )
        }
    }
}

private data class EqBandUi(
    val label: String,
    val frequencyHz: Float,
    val q: Float,
    val gainDb: Float
)

private data class SoundCloudAuthCallback(
    val code: String?,
    val state: String?,
    val error: String?,
    val errorDescription: String?
)

private const val AUTH_PREFS = "soundcloud_auth"
private const val AUTH_ACCESS_TOKEN_KEY = "access_token"

@OptIn(UnstableApi::class)
@Composable
private fun MusicPlayerScreen(
    player: ExoPlayer,
    eqProcessor: ParametricEqAudioProcessor,
    soundCloudClient: SoundCloudClient,
    soundCloudClientId: String,
    soundCloudRedirectUri: String,
    soundCloudClientSecret: String,
    authCallback: SoundCloudAuthCallback?,
    onAuthCallbackConsumed: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var soundCloudUrl by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("Idle") }
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var currentTitle by remember { mutableStateOf(resolveTitle(player)) }
    var accessToken by remember { mutableStateOf(loadSoundCloudAccessToken(context)) }
    var pendingPkceSession by remember { mutableStateOf<PkceSession?>(null) }

    val eqBands = remember {
        mutableStateListOf(
            EqBandUi("Band 1", frequencyHz = 120f, q = 1f, gainDb = 0f),
            EqBandUi("Band 2", frequencyHz = 1000f, q = 1f, gainDb = 0f),
            EqBandUi("Band 3", frequencyHz = 5000f, q = 1f, gainDb = 0f)
        )
    }

    fun pushEqSettings() {
        eqProcessor.setBands(
            eqBands.map {
                ParametricEqAudioProcessor.Band(
                    frequencyHz = it.frequencyHz,
                    q = it.q,
                    gainDb = it.gainDb
                )
            }
        )
    }

    LaunchedEffect(Unit) {
        pushEqSettings()
    }

    LaunchedEffect(authCallback) {
        val callback = authCallback ?: return@LaunchedEffect
        onAuthCallbackConsumed()

        if (!callback.error.isNullOrBlank()) {
            statusMessage = "SoundCloud auth failed: ${callback.errorDescription ?: callback.error}"
            pendingPkceSession = null
            return@LaunchedEffect
        }

        val code = callback.code
        val pendingSession = pendingPkceSession
        if (code.isNullOrBlank() || pendingSession == null) {
            statusMessage = "Missing auth session or authorization code."
            return@LaunchedEffect
        }

        if (callback.state != pendingSession.state) {
            statusMessage = "SoundCloud auth state mismatch. Try again."
            pendingPkceSession = null
            return@LaunchedEffect
        }

        statusMessage = "Exchanging SoundCloud auth code..."
        val tokenResult = withContext(Dispatchers.IO) {
            soundCloudClient.exchangeCodeForToken(
                clientId = soundCloudClientId,
                redirectUri = soundCloudRedirectUri,
                code = code,
                codeVerifier = pendingSession.codeVerifier,
                clientSecret = soundCloudClientSecret.ifBlank { null }
            )
        }

        tokenResult.onSuccess { token ->
            accessToken = token
            saveSoundCloudAccessToken(context, token)
            pendingPkceSession = null
            statusMessage = "SoundCloud authenticated."
        }.onFailure { error ->
            pendingPkceSession = null
            statusMessage = error.message ?: "SoundCloud token exchange failed."
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                currentTitle = resolveTitle(player)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentTitle = resolveTitle(player)
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                statusMessage = "Playback error: ${error.errorCodeName}"
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
        }
    }

    val localFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) {
            statusMessage = "Local file selection canceled."
            return@rememberLauncherForActivityResult
        }

        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(uri.lastPathSegment ?: "Local track")
                    .build()
            )
            .build()

        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()
        statusMessage = "Playing local file."
        currentTitle = resolveTitle(player)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = "EQ Music Player", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "Now Playing: $currentTitle",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(text = "Status: $statusMessage")

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { localFilePicker.launch(arrayOf("audio/*")) }) {
                Text("Open Local File")
            }
            Button(onClick = {
                if (isPlaying) player.pause() else player.play()
            }) {
                Text(if (isPlaying) "Pause" else "Play")
            }
            Button(onClick = {
                player.stop()
                statusMessage = "Stopped"
            }) {
                Text("Stop")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(text = "SoundCloud", style = MaterialTheme.typography.titleMedium)
        Text(
            text = if (accessToken.isBlank()) "Auth: Not connected" else "Auth: Connected",
            style = MaterialTheme.typography.bodySmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (soundCloudClientId.isBlank()) {
                    statusMessage = "Set SOUNDCLOUD_CLIENT_ID in gradle.properties."
                    return@Button
                }
                if (soundCloudRedirectUri.isBlank()) {
                    statusMessage = "Set SOUNDCLOUD_REDIRECT_URI in gradle.properties."
                    return@Button
                }

                val session = soundCloudClient.createPkceSession()
                pendingPkceSession = session

                val authUrl = soundCloudClient.buildAuthorizationUrl(
                    clientId = soundCloudClientId,
                    redirectUri = soundCloudRedirectUri,
                    session = session
                )

                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))
                context.startActivity(intent)
                statusMessage = "Complete SoundCloud login in browser."
            }) {
                Text("Connect SoundCloud")
            }

            Button(onClick = {
                pendingPkceSession = null
                accessToken = ""
                clearSoundCloudAccessToken(context)
                statusMessage = "Disconnected SoundCloud auth."
            }) {
                Text("Disconnect")
            }
        }

        OutlinedTextField(
            value = soundCloudUrl,
            onValueChange = { soundCloudUrl = it },
            label = { Text("Track URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    statusMessage = "Resolving SoundCloud stream..."
                    val resolved = withContext(Dispatchers.IO) {
                        soundCloudClient.resolvePlayableStream(
                            trackUrl = soundCloudUrl.trim(),
                            clientId = soundCloudClientId,
                            accessToken = accessToken.ifBlank { null }
                        )
                    }

                    resolved.onSuccess { streamUrl ->
                        val mediaItem = MediaItem.Builder()
                            .setUri(streamUrl)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(soundCloudUrl)
                                    .build()
                            )
                            .build()
                        player.setMediaItem(mediaItem)
                        player.prepare()
                        player.play()
                        statusMessage = "Playing SoundCloud track."
                    }.onFailure { error ->
                        statusMessage = error.message ?: "Failed to play SoundCloud track."
                    }
                }
            }) {
                Text("Play SoundCloud")
            }
        }
        Text(
            text = if (soundCloudClientId.isBlank()) {
                "Add SOUNDCLOUD_CLIENT_ID to gradle.properties before using SoundCloud."
            } else {
                "SoundCloud client ID configured."
            },
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(modifier = Modifier.height(8.dp))
        Text(text = "Parametric EQ", style = MaterialTheme.typography.titleMedium)
        eqBands.forEachIndexed { index, band ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(text = band.label, style = MaterialTheme.typography.titleSmall)

                    Text(text = "Freq: ${band.frequencyHz.toInt()} Hz")
                    Slider(
                        value = normalizedFrequency(band.frequencyHz),
                        onValueChange = { normalized ->
                            eqBands[index] = band.copy(frequencyHz = denormalizeFrequency(normalized))
                            pushEqSettings()
                        },
                        valueRange = 0f..1f
                    )

                    Text(text = "Q: ${"%.2f".format(band.q)}")
                    Slider(
                        value = band.q,
                        onValueChange = { q ->
                            eqBands[index] = band.copy(q = q)
                            pushEqSettings()
                        },
                        valueRange = 0.3f..4f
                    )

                    Text(text = "Gain: ${"%.1f".format(band.gainDb)} dB")
                    Slider(
                        value = band.gainDb,
                        onValueChange = { gain ->
                            eqBands[index] = band.copy(gainDb = gain)
                            pushEqSettings()
                        },
                        valueRange = -12f..12f
                    )
                }
            }
        }
    }
}

private fun loadSoundCloudAccessToken(context: Context): String {
    return context.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE)
        .getString(AUTH_ACCESS_TOKEN_KEY, "")
        .orEmpty()
}

private fun saveSoundCloudAccessToken(context: Context, token: String) {
    context.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(AUTH_ACCESS_TOKEN_KEY, token)
        .apply()
}

private fun clearSoundCloudAccessToken(context: Context) {
    context.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE)
        .edit()
        .remove(AUTH_ACCESS_TOKEN_KEY)
        .apply()
}

private fun resolveTitle(player: Player): String {
    val explicit = player.currentMediaItem?.mediaMetadata?.title?.toString()
    if (!explicit.isNullOrBlank()) {
        return explicit
    }
    return player.currentMediaItem?.localConfiguration?.uri?.lastPathSegment ?: "Nothing playing"
}

private fun normalizedFrequency(frequencyHz: Float): Float {
    val min = 40f
    val max = 12_000f
    val minLn = ln(min)
    val maxLn = ln(max)
    return ((ln(frequencyHz.coerceIn(min, max)) - minLn) / (maxLn - minLn)).coerceIn(0f, 1f)
}

private fun denormalizeFrequency(normalized: Float): Float {
    val min = 40f
    val max = 12_000f
    val minLn = ln(min)
    val maxLn = ln(max)
    return exp(minLn + normalized.coerceIn(0f, 1f) * (maxLn - minLn))
}
