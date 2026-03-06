package com.example.eqmusicplayer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.eqmusicplayer.audio.ParametricEqAudioProcessor
import com.example.eqmusicplayer.playback.MusicPlaybackService
import com.example.eqmusicplayer.playback.PlaybackRepository
import com.example.eqmusicplayer.soundcloud.SoundCloudClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.ln

class MainActivity : ComponentActivity() {

    private val soundCloudClient = SoundCloudClient()
    private lateinit var player: ExoPlayer
    private var soundCloudAuthCallback by mutableStateOf<SoundCloudAuthCallback?>(null)

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = PlaybackRepository.getPlayer(this)
        handleSoundCloudCallback(intent)

        setContent {
            MaterialTheme {
                MusicPlayerScreen(
                    player = player,
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

private enum class EqEditFieldType {
    FREQUENCY,
    Q,
    GAIN
}

private data class EqEditTarget(
    val bandIndex: Int,
    val fieldType: EqEditFieldType
)

private data class SoundCloudAuthCallback(
    val code: String?,
    val state: String?,
    val error: String?,
    val errorDescription: String?
)

private const val AUTH_PREFS = "soundcloud_auth"
private const val AUTH_ACCESS_TOKEN_KEY = "access_token"
private const val AUTH_PKCE_STATE_KEY = "pkce_state"
private const val AUTH_PKCE_VERIFIER_KEY = "pkce_verifier"
private const val EQ_BANDS_KEY = "eq_bands"
private const val SOUNDCLOUD_PLAY_DEBOUNCE_MS = 2_000L
private const val SOUNDCLOUD_STREAM_CACHE_TTL_MS = 15 * 60 * 1000L

private data class CachedSoundCloudStream(
    val streamUrl: String,
    val cachedAtElapsedMs: Long
)

private data class PendingPkceAuthSession(
    val state: String,
    val codeVerifier: String
)

@OptIn(UnstableApi::class)
@Composable
private fun MusicPlayerScreen(
    player: ExoPlayer,
    soundCloudClient: SoundCloudClient,
    soundCloudClientId: String,
    soundCloudRedirectUri: String,
    soundCloudClientSecret: String,
    authCallback: SoundCloudAuthCallback?,
    onAuthCallbackConsumed: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var mediaController by remember { mutableStateOf<MediaController?>(null) }

    var soundCloudUrl by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("Idle") }
    var isPlaying by remember { mutableStateOf(false) }
    var currentTitle by remember { mutableStateOf("Nothing playing") }
    var playbackPositionMs by remember { mutableStateOf(0L) }
    var playbackDurationMs by remember { mutableStateOf(0L) }
    var accessToken by remember { mutableStateOf(loadSoundCloudAccessToken(context)) }
    var pendingPkceSession by remember { mutableStateOf(loadPendingPkceSession(context)) }
    var eqEditTarget by remember { mutableStateOf<EqEditTarget?>(null) }
    var eqEditInput by remember { mutableStateOf("") }
    var lastSoundCloudPlayAttemptMs by remember { mutableStateOf(0L) }
    var soundCloudStreamCache by remember {
        mutableStateOf<Map<String, CachedSoundCloudStream>>(emptyMap())
    }

    val eqBands = remember {
        mutableStateListOf<EqBandUi>().apply {
            addAll(loadEqBands(context))
        }
    }

    fun pushEqSettings() {
        PlaybackRepository.updateEqBands(
            eqBands.map {
                ParametricEqAudioProcessor.Band(
                    frequencyHz = it.frequencyHz,
                    q = it.q,
                    gainDb = it.gainDb
                )
            }
        )
    }

    fun saveEqSettings() {
        saveEqBands(context, eqBands)
    }

    fun applyBandUpdate(index: Int, updatedBand: EqBandUi) {
        eqBands[index] = updatedBand
        pushEqSettings()
        saveEqSettings()
    }

    fun openEqEditor(index: Int, fieldType: EqEditFieldType, value: Float) {
        eqEditTarget = EqEditTarget(bandIndex = index, fieldType = fieldType)
        eqEditInput = when (fieldType) {
            EqEditFieldType.FREQUENCY -> value.toInt().toString()
            EqEditFieldType.Q -> "%.2f".format(value)
            EqEditFieldType.GAIN -> "%.1f".format(value)
        }
    }

    DisposableEffect(context) {
        ensurePlaybackServiceRunning(context)
        val sessionToken = SessionToken(
            context,
            ComponentName(context, MusicPlaybackService::class.java)
        )
        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture.addListener(
            {
                runCatching { controllerFuture.get() }
                    .onSuccess {
                        mediaController = it
                        statusMessage = "Media controls connected."
                    }
                    .onFailure {
                        statusMessage = "Failed to connect media controls."
                    }
            },
            ContextCompat.getMainExecutor(context)
        )

        onDispose {
            MediaController.releaseFuture(controllerFuture)
            mediaController = null
        }
    }

    val controlledPlayer: Player = mediaController ?: player

    LaunchedEffect(Unit) {
        pushEqSettings()
    }

    LaunchedEffect(controlledPlayer) {
        isPlaying = controlledPlayer.isPlaying
        currentTitle = resolveTitle(controlledPlayer)
        playbackPositionMs = controlledPlayer.currentPosition.coerceAtLeast(0L)
        playbackDurationMs = controlledPlayer.duration.takeIf { it > 0 } ?: 0L
    }

    LaunchedEffect(authCallback) {
        val callback = authCallback ?: return@LaunchedEffect

        if (!callback.error.isNullOrBlank()) {
            statusMessage = "SoundCloud auth failed: ${callback.errorDescription ?: callback.error}"
            pendingPkceSession = null
            clearPendingPkceSession(context)
            onAuthCallbackConsumed()
            return@LaunchedEffect
        }

        val code = callback.code
        val pendingSession = pendingPkceSession
        if (code.isNullOrBlank() || pendingSession == null) {
            statusMessage = "Missing auth session or authorization code."
            onAuthCallbackConsumed()
            return@LaunchedEffect
        }

        if (callback.state != pendingSession.state) {
            statusMessage = "SoundCloud auth state mismatch. Try again."
            pendingPkceSession = null
            clearPendingPkceSession(context)
            onAuthCallbackConsumed()
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
            clearPendingPkceSession(context)
            statusMessage = "SoundCloud authenticated."
        }.onFailure { error ->
            pendingPkceSession = null
            clearPendingPkceSession(context)
            statusMessage = error.message ?: "SoundCloud token exchange failed."
        }
        onAuthCallbackConsumed()
    }

    DisposableEffect(controlledPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                currentTitle = resolveTitle(controlledPlayer)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentTitle = resolveTitle(controlledPlayer)
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                statusMessage = "Playback error: ${error.errorCodeName}"
            }

            override fun onEvents(player: Player, events: Player.Events) {
                playbackPositionMs = player.currentPosition.coerceAtLeast(0L)
                playbackDurationMs = player.duration.takeIf { it > 0 } ?: 0L
            }
        }
        controlledPlayer.addListener(listener)
        onDispose {
            controlledPlayer.removeListener(listener)
        }
    }

    LaunchedEffect(controlledPlayer, isPlaying) {
        while (true) {
            playbackPositionMs = controlledPlayer.currentPosition.coerceAtLeast(0L)
            playbackDurationMs = controlledPlayer.duration.takeIf { it > 0 } ?: 0L
            delay(500)
        }
    }

    val localFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) {
            statusMessage = "Local file selection canceled."
            return@rememberLauncherForActivityResult
        }

        scope.launch {
            val mediaItem = withContext(Dispatchers.IO) {
                buildLocalMediaItem(context, uri)
            }
            ensurePlaybackServiceRunning(context)
            controlledPlayer.setMediaItem(mediaItem)
            controlledPlayer.prepare()
            controlledPlayer.play()
            statusMessage = "Playing local file."
            currentTitle = resolveTitle(controlledPlayer)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = "Баскаса", style = MaterialTheme.typography.headlineSmall)
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
                if (isPlaying) {
                    controlledPlayer.pause()
                } else {
                    ensurePlaybackServiceRunning(context)
                    controlledPlayer.play()
                }
            }) {
                Text(if (isPlaying) "Pause" else "Play")
            }
            Button(onClick = {
                controlledPlayer.stop()
                statusMessage = "Stopped"
            }) {
                Text("Stop")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { controlledPlayer.seekToPreviousMediaItem() }) {
                Text("Prev")
            }
            Button(onClick = {
                val target = (controlledPlayer.currentPosition - 10_000L).coerceAtLeast(0L)
                controlledPlayer.seekTo(target)
            }) {
                Text("-10s")
            }
            Button(onClick = {
                val max = controlledPlayer.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                val target = (controlledPlayer.currentPosition + 10_000L).coerceAtMost(max)
                controlledPlayer.seekTo(target)
            }) {
                Text("+10s")
            }
            Button(onClick = { controlledPlayer.seekToNextMediaItem() }) {
                Text("Next")
            }
        }

        val sliderMax = playbackDurationMs.takeIf { it > 0 } ?: 1L
        Slider(
            value = playbackPositionMs.coerceIn(0L, sliderMax).toFloat(),
            onValueChange = { newPosition ->
                controlledPlayer.seekTo(newPosition.toLong().coerceIn(0L, sliderMax))
            },
            valueRange = 0f..sliderMax.toFloat()
        )
        Text(text = "Position: ${formatMs(playbackPositionMs)} / ${formatMs(playbackDurationMs)}")

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
                pendingPkceSession = PendingPkceAuthSession(
                    state = session.state,
                    codeVerifier = session.codeVerifier
                )
                savePendingPkceSession(
                    context = context,
                    state = session.state,
                    codeVerifier = session.codeVerifier
                )

                val authUrl = soundCloudClient.buildAuthorizationUrl(
                    clientId = soundCloudClientId,
                    redirectUri = soundCloudRedirectUri,
                    session = session
                )

                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)))
                statusMessage = "Complete SoundCloud login in browser."
            }) {
                Text("Connect SoundCloud")
            }

            Button(onClick = {
                pendingPkceSession = null
                clearPendingPkceSession(context)
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
                val normalizedTrackUrl = normalizeTrackUrl(soundCloudUrl)
                if (normalizedTrackUrl.isBlank()) {
                    statusMessage = "Enter a SoundCloud track URL."
                    return@Button
                }
                val now = SystemClock.elapsedRealtime()
                val remainingDebounceMs =
                    SOUNDCLOUD_PLAY_DEBOUNCE_MS - (now - lastSoundCloudPlayAttemptMs)
                if (remainingDebounceMs > 0) {
                    statusMessage = "Please wait ${remainingDebounceMs / 1000.0}s before retrying."
                    return@Button
                }
                lastSoundCloudPlayAttemptMs = now

                scope.launch {
                    val cacheEntry = soundCloudStreamCache[normalizedTrackUrl]
                    val isCacheFresh = cacheEntry != null &&
                        now - cacheEntry.cachedAtElapsedMs <= SOUNDCLOUD_STREAM_CACHE_TTL_MS

                    if (isCacheFresh) {
                        val mediaItem = MediaItem.Builder()
                            .setUri(cacheEntry!!.streamUrl)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(soundCloudUrl.trim())
                                    .build()
                            )
                            .build()
                        ensurePlaybackServiceRunning(context)
                        controlledPlayer.setMediaItem(mediaItem)
                        controlledPlayer.prepare()
                        controlledPlayer.play()
                        statusMessage = "Playing SoundCloud track (cached)."
                        return@launch
                    }

                    statusMessage = "Resolving SoundCloud stream..."
                    val resolved = withContext(Dispatchers.IO) {
                        soundCloudClient.resolvePlayableStream(
                            trackUrl = normalizedTrackUrl,
                            clientId = soundCloudClientId,
                            accessToken = accessToken.ifBlank { null }
                        )
                    }
                    resolved.onSuccess { streamUrl ->
                        val cacheNow = SystemClock.elapsedRealtime()
                        val prunedCache = soundCloudStreamCache
                            .filterValues {
                                cacheNow - it.cachedAtElapsedMs <= SOUNDCLOUD_STREAM_CACHE_TTL_MS
                            }
                            .toMutableMap()
                        prunedCache[normalizedTrackUrl] = CachedSoundCloudStream(
                            streamUrl = streamUrl,
                            cachedAtElapsedMs = cacheNow
                        )
                        soundCloudStreamCache = prunedCache

                        val mediaItem = MediaItem.Builder()
                            .setUri(streamUrl)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(soundCloudUrl.trim())
                                    .build()
                            )
                            .build()
                        ensurePlaybackServiceRunning(context)
                        controlledPlayer.setMediaItem(mediaItem)
                        controlledPlayer.prepare()
                        controlledPlayer.play()
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
        Text(
            text = "Tip: tap frequency, Q, or gain value to type an exact number.",
            style = MaterialTheme.typography.bodySmall
        )
        eqBands.forEachIndexed { index, band ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(text = band.label, style = MaterialTheme.typography.titleSmall)

                    Text(
                        text = "Freq: ${band.frequencyHz.toInt()} Hz",
                        modifier = Modifier.clickable {
                            openEqEditor(index, EqEditFieldType.FREQUENCY, band.frequencyHz)
                        }
                    )
                    Slider(
                        value = normalizedFrequency(band.frequencyHz),
                        onValueChange = { normalized ->
                            applyBandUpdate(index, band.copy(frequencyHz = denormalizeFrequency(normalized)))
                        },
                        valueRange = 0f..1f
                    )

                    Text(
                        text = "Q: ${"%.2f".format(band.q)}",
                        modifier = Modifier.clickable {
                            openEqEditor(index, EqEditFieldType.Q, band.q)
                        }
                    )
                    Slider(
                        value = band.q,
                        onValueChange = { q ->
                            applyBandUpdate(index, band.copy(q = q))
                        },
                        valueRange = 0.3f..4f
                    )

                    Text(
                        text = "Gain: ${"%.1f".format(band.gainDb)} dB",
                        modifier = Modifier.clickable {
                            openEqEditor(index, EqEditFieldType.GAIN, band.gainDb)
                        }
                    )
                    Slider(
                        value = band.gainDb,
                        onValueChange = { gain ->
                            applyBandUpdate(index, band.copy(gainDb = gain))
                        },
                        valueRange = -12f..12f
                    )
                }
            }
        }

        val activeEditTarget = eqEditTarget
        if (activeEditTarget != null) {
            val currentBand = eqBands[activeEditTarget.bandIndex]
            val (dialogTitle, rangeText) = when (activeEditTarget.fieldType) {
                EqEditFieldType.FREQUENCY -> "Set Frequency" to "40 - 20000 Hz"
                EqEditFieldType.Q -> "Set Q" to "0.30 - 4.00"
                EqEditFieldType.GAIN -> "Set Gain" to "-12.0 - 12.0 dB"
            }
            AlertDialog(
                onDismissRequest = { eqEditTarget = null },
                title = { Text(dialogTitle) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(text = rangeText, style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(
                            value = eqEditInput,
                            onValueChange = { eqEditInput = it },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val parsedValue = eqEditInput.toFloatOrNull()
                        if (parsedValue == null) {
                            statusMessage = "Enter a valid number."
                            return@TextButton
                        }

                        val updatedBand = when (activeEditTarget.fieldType) {
                            EqEditFieldType.FREQUENCY -> {
                                currentBand.copy(frequencyHz = parsedValue.coerceIn(40f, 20_000f))
                            }
                            EqEditFieldType.Q -> {
                                currentBand.copy(q = parsedValue.coerceIn(0.3f, 4f))
                            }
                            EqEditFieldType.GAIN -> {
                                currentBand.copy(gainDb = parsedValue.coerceIn(-12f, 12f))
                            }
                        }

                        applyBandUpdate(activeEditTarget.bandIndex, updatedBand)
                        eqEditTarget = null
                    }) {
                        Text("Apply")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { eqEditTarget = null }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

private fun ensurePlaybackServiceRunning(context: Context) {
    context.startService(Intent(context, MusicPlaybackService::class.java))
}

private fun buildLocalMediaItem(context: Context, uri: Uri): MediaItem {
    val displayName = queryDisplayName(context, uri)

    var title: String? = null
    var artist: String? = null
    var album: String? = null
    var artwork: ByteArray? = null

    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, uri)
        title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
        artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
        album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
        artwork = retriever.embeddedPicture
    } catch (_: Exception) {
        // Keep fallback metadata if extraction fails.
    } finally {
        runCatching { retriever.release() }
    }

    val mediaMetadataBuilder = MediaMetadata.Builder()
        .setTitle(title ?: displayName ?: uri.lastPathSegment ?: "Local track")

    if (!artist.isNullOrBlank()) {
        mediaMetadataBuilder.setArtist(artist)
    }
    if (!album.isNullOrBlank()) {
        mediaMetadataBuilder.setAlbumTitle(album)
    }
    if (artwork != null) {
        mediaMetadataBuilder.setArtworkData(artwork, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
    }

    return MediaItem.Builder()
        .setUri(uri)
        .setMediaMetadata(mediaMetadataBuilder.build())
        .build()
}

private fun queryDisplayName(context: Context, uri: Uri): String? {
    val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
    return context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex < 0) return@use null
        cursor.getString(nameIndex)
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

private fun loadPendingPkceSession(context: Context): PendingPkceAuthSession? {
    val prefs = context.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE)
    val state = prefs.getString(AUTH_PKCE_STATE_KEY, "").orEmpty()
    val codeVerifier = prefs.getString(AUTH_PKCE_VERIFIER_KEY, "").orEmpty()
    if (state.isBlank() || codeVerifier.isBlank()) {
        return null
    }
    return PendingPkceAuthSession(state = state, codeVerifier = codeVerifier)
}

private fun savePendingPkceSession(context: Context, state: String, codeVerifier: String) {
    context.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(AUTH_PKCE_STATE_KEY, state)
        .putString(AUTH_PKCE_VERIFIER_KEY, codeVerifier)
        .apply()
}

private fun clearPendingPkceSession(context: Context) {
    context.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE)
        .edit()
        .remove(AUTH_PKCE_STATE_KEY)
        .remove(AUTH_PKCE_VERIFIER_KEY)
        .apply()
}

private fun resolveTitle(player: Player): String {
    val metadataTitle = player.mediaMetadata.title?.toString()
    if (!metadataTitle.isNullOrBlank()) {
        return metadataTitle
    }
    val explicit = player.currentMediaItem?.mediaMetadata?.title?.toString()
    if (!explicit.isNullOrBlank()) {
        return explicit
    }
    return player.currentMediaItem?.localConfiguration?.uri?.lastPathSegment ?: "Nothing playing"
}

private fun normalizedFrequency(frequencyHz: Float): Float {
    val min = 40f
    val max = 20_000f
    val minLn = ln(min)
    val maxLn = ln(max)
    return ((ln(frequencyHz.coerceIn(min, max)) - minLn) / (maxLn - minLn)).coerceIn(0f, 1f)
}

private fun denormalizeFrequency(normalized: Float): Float {
    val min = 40f
    val max = 20_000f
    val minLn = ln(min)
    val maxLn = ln(max)
    return exp(minLn + normalized.coerceIn(0f, 1f) * (maxLn - minLn))
}

private fun defaultEqBands(): List<EqBandUi> {
    return listOf(
        EqBandUi("Band 1", frequencyHz = 60f, q = 1f, gainDb = 0f),
        EqBandUi("Band 2", frequencyHz = 120f, q = 1f, gainDb = 0f),
        EqBandUi("Band 3", frequencyHz = 250f, q = 1f, gainDb = 0f),
        EqBandUi("Band 4", frequencyHz = 500f, q = 1f, gainDb = 0f),
        EqBandUi("Band 5", frequencyHz = 1000f, q = 1f, gainDb = 0f),
        EqBandUi("Band 6", frequencyHz = 2500f, q = 1f, gainDb = 0f),
        EqBandUi("Band 7", frequencyHz = 6000f, q = 1f, gainDb = 0f),
        EqBandUi("Band 8", frequencyHz = 16_000f, q = 1f, gainDb = 0f)
    )
}

private fun loadEqBands(context: Context): List<EqBandUi> {
    val raw = context.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE)
        .getString(EQ_BANDS_KEY, null)
        .orEmpty()
    if (raw.isBlank()) {
        return defaultEqBands()
    }

    return runCatching {
        val array = JSONArray(raw)
        val parsed = mutableListOf<EqBandUi>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val frequencyHz = item.optDouble("frequencyHz", 1000.0).toFloat().coerceIn(40f, 20_000f)
            val q = item.optDouble("q", 1.0).toFloat().coerceIn(0.3f, 4f)
            val gainDb = item.optDouble("gainDb", 0.0).toFloat().coerceIn(-12f, 12f)
            parsed += EqBandUi(
                label = "Band ${parsed.size + 1}",
                frequencyHz = frequencyHz,
                q = q,
                gainDb = gainDb
            )
        }
        if (parsed.size == 8) parsed else defaultEqBands()
    }.getOrDefault(defaultEqBands())
}

private fun saveEqBands(context: Context, bands: List<EqBandUi>) {
    val array = JSONArray()
    bands.forEach { band ->
        array.put(
            JSONObject()
                .put("frequencyHz", band.frequencyHz)
                .put("q", band.q)
                .put("gainDb", band.gainDb)
        )
    }

    context.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(EQ_BANDS_KEY, array.toString())
        .apply()
}

private fun formatMs(valueMs: Long): String {
    if (valueMs <= 0L) return "00:00"
    val totalSeconds = valueMs / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%02d:%02d".format(minutes, seconds)
}

private fun normalizeTrackUrl(raw: String): String {
    return raw.trim()
}
