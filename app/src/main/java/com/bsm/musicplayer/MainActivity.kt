package com.bsm.musicplayer

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

private val Background = Color(0xFF101311)
private val SurfaceDark = Color(0xFF1A201C)
private val Accent = Color(0xFFB8F36B)
private val Muted = Color(0xFF929A94)

data class Song(val id: Long, val title: String, val artist: String, val uri: Uri, val duration: Long)

class MainActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var playbackPlayer by mutableStateOf<MediaController?>(null)
    private lateinit var permissionLauncher: ActivityResultLauncher<String>
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>
    private var permissionCallback: ((Boolean) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionCallback?.invoke(granted)
            permissionCallback = null
        }
        notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                background = Background,
                surface = SurfaceDark,
                primary = Accent,
                onBackground = Color.White,
                onSurface = Color.White
            )) {
                val activePlayer = playbackPlayer
                if (activePlayer != null) {
                    BsmMusicApp(
                        hasPermission = { hasAudioPermission() },
                        requestPermission = { callback -> requestAudioPermission(callback) },
                        loadSongs = { loadDeviceSongs() },
                        player = activePlayer
                    )
                } else {
                    Box(Modifier.fillMaxSize().background(Background), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Accent)
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun audioPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, audioPermission()) == PackageManager.PERMISSION_GRANTED

    private fun requestAudioPermission(callback: (Boolean) -> Unit) {
        permissionCallback = callback
        permissionLauncher.launch(audioPermission())
    }

    private fun loadDeviceSongs(): List<Song> {
        if (!hasAudioPermission()) return emptyList()
        val songs = mutableListOf<Song>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        val cursor: Cursor? = contentResolver.query(collection, projection, selection, null, sortOrder)
        cursor?.use {
            val idCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val durationCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (it.moveToNext()) {
                val id = it.getLong(idCol)
                songs += Song(
                    id = id,
                    title = it.getString(titleCol) ?: "Unknown title",
                    artist = it.getString(artistCol)?.takeIf { name -> name != "<unknown>" } ?: "Unknown artist",
                    uri = ContentUris.withAppendedId(collection, id),
                    duration = it.getLong(durationCol)
                )
            }
        }
        return songs
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, MusicPlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            try {
                controller = future.get()
                playbackPlayer = controller
            } catch (_: Exception) {
                controller = null
                playbackPlayer = null
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        playbackPlayer = null
        super.onStop()
    }
}

@Composable
private fun BsmMusicApp(
    hasPermission: () -> Boolean,
    requestPermission: ((Boolean) -> Unit) -> Unit,
    loadSongs: () -> List<Song>,
    player: Player
) {
    var permissionGranted by remember { mutableStateOf(hasPermission()) }
    var songs by remember { mutableStateOf(emptyList<Song>()) }
    var currentSong by remember { mutableStateOf<Song?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var showPlayer by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var sortByArtist by remember { mutableStateOf(false) }
    val visibleSongs = remember(songs, searchQuery, sortByArtist) {
        songs.filter { it.title.contains(searchQuery, true) || it.artist.contains(searchQuery, true) }
            .let { list -> if (sortByArtist) list.sortedBy { it.artist.lowercase() } else list.sortedBy { it.title.lowercase() } }
    }

    DisposableEffect(player, songs) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                val uri = mediaItem?.localConfiguration?.uri
                currentSong = songs.firstOrNull { it.uri == uri } ?: currentSong
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(permissionGranted) {
        if (permissionGranted) songs = loadSongs()
    }

    Box(modifier = Modifier.fillMaxSize().background(Background)) {
        Column(
            modifier = Modifier.fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 22.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("BSM", color = Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                    Text("MUSIC", color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
                }
                IconButton(onClick = { }) {
                    Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.White)
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                Text("YOUR LIBRARY", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { sortByArtist = !sortByArtist }) {
                    Text(if (sortByArtist) "ARTIST A–Z" else "TITLE A–Z", color = Accent, fontSize = 10.sp)
                }
                IconButton(onClick = { songs = loadSongs() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh library", tint = Accent)
                }
            }
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                singleLine = true,
                placeholder = { Text("Search songs or artists", color = Muted) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = Muted) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = SurfaceDark,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = Accent
                )
            )

            if (!permissionGranted) {
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f).background(SurfaceDark, RoundedCornerShape(24.dp)).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.MusicNote, null, tint = Accent, modifier = Modifier.size(54.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("Allow music access", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("BSM Music needs permission to find audio files stored on your device.", color = Muted)
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = {
                        requestPermission { granted -> permissionGranted = granted }
                    }) { Text("Grant permission") }
                }
            } else if (songs.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f).background(SurfaceDark, RoundedCornerShape(24.dp)).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.MusicNote, null, tint = Accent, modifier = Modifier.size(54.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("Your sound. Your space.", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("No music files found on this device.", color = Muted)
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = { songs = loadSongs() }) { Text("Refresh library") }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                    contentPadding = PaddingValues(bottom = 14.dp)
                ) {
                    items(visibleSongs, key = { it.id }) { song ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .background(SurfaceDark, RoundedCornerShape(16.dp))
                                .clickable {
                                    currentSong = song
                                    player.setMediaItems(songs.map { MediaItem.fromUri(it.uri) })
                                    player.seekTo(songs.indexOf(song).coerceAtLeast(0), 0L)
                                    player.prepare()
                                    player.play()
                                    showPlayer = true
                                }.padding(13.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.size(46.dp).background(Color(0xFF29352A), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.MusicNote, null, tint = Accent)
                            }
                            Column(Modifier.weight(1f).padding(start = 13.dp)) {
                                Text(song.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text(song.artist, color = Muted, fontSize = 12.sp, maxLines = 1)
                            }
                            Text(formatTime(song.duration), color = Muted, fontSize = 11.sp)
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp)
                    .background(Color(0xFF263126), RoundedCornerShape(18.dp))
                    .clickable { if (currentSong != null) showPlayer = true }.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.MusicNote, null, tint = Accent, modifier = Modifier.size(28.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(currentSong?.title ?: "Nothing playing", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(currentSong?.artist ?: "Choose a song to get started", color = Muted, fontSize = 11.sp, maxLines = 1)
                }
                IconButton(onClick = {
                    if (player.isPlaying) player.pause()
                    else if (currentSong != null) player.play()
                }) {
                    Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Play or pause", tint = Accent, modifier = Modifier.size(29.dp))
                }
            }
        }

        if (showPlayer && currentSong != null) {
            PlayerScreen(
                song = currentSong!!,
                player = player,
                onClose = { showPlayer = false },
                onPrevious = { player.seekToPreviousMediaItem() },
                onNext = { player.seekToNextMediaItem() }
            )
        }
    }
}

@Composable
private fun PlayerScreen(song: Song, player: Player, onClose: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit) {
    var position by remember(song.id) { mutableFloatStateOf(0f) }
    var duration by remember(song.id) { mutableFloatStateOf(song.duration.toFloat().coerceAtLeast(1f)) }
    var playing by remember { mutableStateOf(player.isPlaying) }

    LaunchedEffect(song.id) {
        while (true) {
            position = player.currentPosition.toFloat()
            duration = player.duration.takeIf { it > 0 }?.toFloat() ?: song.duration.toFloat().coerceAtLeast(1f)
            playing = player.isPlaying
            kotlinx.coroutines.delay(500)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Background.copy(alpha = 0.98f))
            .statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            TextButton(onClick = onClose) { Text("CLOSE", color = Accent, fontWeight = FontWeight.Bold) }
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Color(0xFF29352A), RoundedCornerShape(28.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.MusicNote, null, tint = Accent, modifier = Modifier.size(110.dp))
        }
        Spacer(Modifier.height(34.dp))
        Text(song.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Spacer(Modifier.height(7.dp))
        Text(song.artist, color = Muted, fontSize = 15.sp)
        Spacer(Modifier.height(28.dp))
        Slider(
            value = position.coerceIn(0f, duration),
            onValueChange = { position = it },
            onValueChangeFinished = { player.seekTo(position.toLong()) },
            valueRange = 0f..duration.coerceAtLeast(1f),
            colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent)
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(position.toLong()), color = Muted, fontSize = 11.sp)
            Text(formatTime(duration.toLong()), color = Muted, fontSize = 11.sp)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { player.shuffleModeEnabled = !player.shuffleModeEnabled }) {
                Icon(Icons.Default.Shuffle, contentDescription = "Shuffle", tint = if (player.shuffleModeEnabled) Accent else Muted)
            }
            IconButton(onClick = {
                player.repeatMode = when (player.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
            }) {
                Icon(
                    if (player.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                    contentDescription = "Repeat",
                    tint = if (player.repeatMode == Player.REPEAT_MODE_OFF) Muted else Accent
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            IconButton(onClick = onPrevious, modifier = Modifier.size(58.dp)) {
                Icon(Icons.Default.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(34.dp))
            }
            IconButton(
                onClick = { if (player.isPlaying) player.pause() else player.play(); playing = player.isPlaying },
                modifier = Modifier.size(76.dp).background(Accent, CircleShape)
            ) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Background, modifier = Modifier.size(42.dp))
            }
            IconButton(onClick = onNext, modifier = Modifier.size(58.dp)) {
                Icon(Icons.Default.SkipNext, null, tint = Color.White, modifier = Modifier.size(34.dp))
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
