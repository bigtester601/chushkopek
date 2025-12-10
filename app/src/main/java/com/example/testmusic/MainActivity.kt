package com.example.testmusic
// Full advanced Jetpack Compose music player with:
// - File picker
// - ExoPlayer
// - Seek bar
// - Background playback + notification
// - Audio file listing
// This is a simplified full example that you can adapt into your project.

import android.Manifest
import android.R
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.app.NotificationChannel
import android.app.NotificationManager
import android.provider.MediaStore
import androidx.annotation.RequiresPermission

class MainActivity : ComponentActivity() {

    private lateinit var exoPlayer: ExoPlayer
    private val channelId = "music_player_channel"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        exoPlayer = ExoPlayer.Builder(this).build()
        createNotificationChannel()

        setContent {
            MusicPlayerApp(exoPlayer, this)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer.release()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Music Player",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    fun showNotification(title: String) {
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Playing: $title")
            .setSmallIcon(R.drawable.ic_media_play)
            .setOngoing(true)
            .build()

        NotificationManagerCompat.from(this).notify(1, notification)
    }
}

data class AudioFile(val uri: Uri, val title: String)

@Composable
fun MusicPlayerApp(exoPlayer: ExoPlayer, activity: MainActivity) {
    val context = activity
    var audioList by remember { mutableStateOf(listOf<AudioFile>()) }
    var selectedAudio by remember { mutableStateOf<AudioFile?>(null) }

    val scope = rememberCoroutineScope()
    var position by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }

    // ---------------- Permission Handling ----------------
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        Manifest.permission.READ_MEDIA_AUDIO
    else
        Manifest.permission.READ_EXTERNAL_STORAGE

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) audioList = loadAudioFiles(context)
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(permission)
    }

    // ---------------- File Picker ----------------
    val pickAudioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            selectedAudio = AudioFile(it, it.lastPathSegment ?: "Picked Audio")
            exoPlayer.setMediaItem(MediaItem.fromUri(it))
            exoPlayer.prepare()
            exoPlayer.play()
            activity.showNotification(selectedAudio!!.title)
        }
    }

    // ---------------- UI ----------------
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Music Player", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))

        // Pick audio manually
        Button(onClick = { pickAudioLauncher.launch(arrayOf("audio/*")) }) {
            Text("Pick Audio File")
        }

        Spacer(Modifier.height(12.dp))

        Text("Device Audio Files:")
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(audioList) { audio ->
                Text(
                    text = audio.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable @androidx.annotation.RequiresPermission(android.Manifest.permission.POST_NOTIFICATIONS) {
                            selectedAudio = audio
                            exoPlayer.setMediaItem(MediaItem.fromUri(audio.uri))
                            exoPlayer.prepare()
                            exoPlayer.play()
                            activity.showNotification(audio.title)
                        }
                        .padding(8.dp)
                )
            }
        }

        selectedAudio?.let { audio ->
            Spacer(Modifier.height(12.dp))
            Text("Now Playing: ${audio.title}")

            // SeekBar
            Slider(
                value = position.toFloat(),
                onValueChange = { exoPlayer.seekTo(it.toLong()) },
                valueRange = 0f..duration.toFloat()
            )

            Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { exoPlayer.play() }) { Text("Play") }
                Button(onClick = { exoPlayer.pause() }) { Text("Pause") }
                Button(onClick = {
                    exoPlayer.seekTo(0)
                    exoPlayer.pause()
                }) { Text("Stop") }
            }
        }
    }

    // ---------------- Update SeekBar ----------------
    LaunchedEffect(selectedAudio) {
        while (true) {
            if (selectedAudio != null) {
                position = exoPlayer.currentPosition
                duration = exoPlayer.duration.coerceAtLeast(1)
            }
            delay(500)
        }
    }
}

fun loadAudioFiles(context: Context): List<AudioFile> {
    val audioList = mutableListOf<AudioFile>()
    val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE)

    context.contentResolver.query(collection, projection, null, null, MediaStore.Audio.Media.TITLE + " ASC")?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)

        while (cursor.moveToNext()) {
            val id = cursor.getLong(idCol)
            val title = cursor.getString(titleCol)
            val uri = ContentUris.withAppendedId(collection, id)
            audioList.add(AudioFile(uri, title))
        }
    }
    return audioList
}
