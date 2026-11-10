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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer

private val Background = Color(0xFF08090C)
private val SurfaceDark = Color(0xFF15161B)
private val Accent = Color(0xFFFF3048)
private val Muted = Color(0xFF9698A2)
private val AccentDeep = Color(0xFF351018)
private val SurfaceRaised = Color(0xFF1D1E25)

@androidx.compose.runtime.Immutable
data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val uri: Uri,
    val duration: Long,
    val searchText: String = normalizeSearchText("$title $artist")
)

private fun normalizeSearchText(value: String): String {
    val normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace('ـ', ' ')
        .lowercase()
    return buildString(normalized.length) {
        normalized.forEach { character ->
            append(
                when (character) {
                    'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                    'ى' -> 'ي'
                    'ؤ' -> 'و'
                    'ئ' -> 'ي'
                    'ة' -> 'ه'
                    else -> character
                }
            )
        }
    }.replace(Regex("\\s+"), " ").trim()
}

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
        // Request the Galaxy A51's native 60 Hz display mode when available.
        window.attributes = window.attributes.apply { preferredRefreshRate = 60f }
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
        // Keep the MediaController connected while the activity is in the background.
        // Releasing it in onStop can disconnect playback when the user returns.
    }

    override fun onStop() {
        super.onStop()
    }

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        playbackPlayer = null
        super.onDestroy()
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
    var isRefreshing by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val favoritesPrefs = remember(context) { context.getSharedPreferences("bsm_music_preferences", android.content.Context.MODE_PRIVATE) }
    var favoriteIds by remember {
        mutableStateOf(favoritesPrefs.getStringSet("favorite_song_ids", emptySet())?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet())
    }
    var showFavoritesOnly by remember { mutableStateOf(false) }
    fun toggleFavorite(song: Song) {
        val updated = if (song.id in favoriteIds) favoriteIds - song.id else favoriteIds + song.id
        favoriteIds = updated
        favoritesPrefs.edit().putStringSet("favorite_song_ids", updated.map { it.toString() }.toSet()).apply()
    }
    val scope = rememberCoroutineScope()
    fun refreshLibrary() {
        scope.launch {
            isRefreshing = true
            try { songs = withContext(Dispatchers.IO) { loadSongs() } }
            finally { isRefreshing = false }
        }
    }
    // Build MediaItems once per library refresh instead of rebuilding the full playlist on every tap.
    val mediaItems = remember(songs) {
        songs.map { MediaItem.fromUri(it.uri) }
    }
    val songIndexById = remember(songs) { songs.mapIndexed { index, song -> song.id to index }.toMap() }
    var visibleSongs by remember { mutableStateOf(emptyList<Song>()) }
    LaunchedEffect(songs, searchQuery, sortByArtist, favoriteIds, showFavoritesOnly) {
        val queryWords = normalizeSearchText(searchQuery).split(' ').filter { it.isNotBlank() }
        visibleSongs = withContext(Dispatchers.Default) {
            val searched = if (queryWords.isEmpty()) songs else {
                songs.filter { song -> queryWords.all { word -> word in song.searchText } }
            }
            val filtered = searched.filter { song ->
                (!showFavoritesOnly || song.id in favoriteIds)
            }
            if (sortByArtist) filtered.sortedBy { it.artist.lowercase() } else filtered
        }
    }
    val onSongClick: (Song) -> Unit = remember(player, songIndexById) {
        { song ->
            currentSong = song
            player.seekTo(songIndexById[song.id] ?: 0, 0L)
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
            showPlayer = true
        }
    }

    LaunchedEffect(mediaItems) {
        player.setMediaItems(mediaItems)
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
        if (permissionGranted) {
            isRefreshing = true
            try { songs = withContext(Dispatchers.IO) { loadSongs() } }
            finally { isRefreshing = false }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("YOUR MUSIC. YOUR RULES.", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("BSM", color = Accent, fontSize = 29.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                        Text(" MUSIC", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                    }
                }
                Box(Modifier.size(46.dp).background(SurfaceDark, CircleShape), contentAlignment = Alignment.Center) {
                    IconButton(onClick = { refreshLibrary() }, enabled = !isRefreshing) {
                        if (isRefreshing) CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                        else Icon(Icons.Default.Refresh, contentDescription = "Refresh library", tint = Accent)
                    }
                }
            }
            OutlinedTextField(
                value = searchQuery, onValueChange = { searchQuery = it }, modifier = Modifier.fillMaxWidth(),
                singleLine = true, shape = RoundedCornerShape(18.dp),
                placeholder = { Text("Search songs or artists", color = Muted, fontSize = 14.sp) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = Accent) },
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, unfocusedBorderColor = SurfaceDark, focusedContainerColor = SurfaceDark, unfocusedContainerColor = SurfaceDark, focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = Accent)
            )
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Brush.linearGradient(listOf(AccentDeep, SurfaceRaised))).padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("YOUR SOUNDTRACK", color = Color.White.copy(alpha = 0.72f), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("${songs.size}", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black)
                        Text(if (songs.size == 1) "TRACK IN YOUR LIBRARY" else "TRACKS IN YOUR LIBRARY", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Spacer(Modifier.height(15.dp))
                        Button(onClick = {
                            if (songs.isNotEmpty()) {
                                currentSong = songs.first()
                                player.shuffleModeEnabled = false
                                player.seekTo(0, 0L)
                                if (player.playbackState == Player.STATE_IDLE) player.prepare()
                                player.play()
                                showPlayer = true
                            }
                        }, enabled = songs.isNotEmpty(), shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Background, disabledContainerColor = SurfaceRaised, disabledContentColor = Muted)) {
                            Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("PLAY ALL", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                        }
                    }
                    Box(Modifier.size(88.dp).background(Color.White.copy(alpha = 0.06f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.MusicNote, null, tint = Accent, modifier = Modifier.size(48.dp))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("YOUR LIBRARY", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                    Text("${visibleSongs.size} SONGS", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                }
                TextButton(onClick = { sortByArtist = !sortByArtist }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                    Text(if (sortByArtist) "ARTIST A–Z" else "TITLE A–Z", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = !showFavoritesOnly,
                    onClick = { showFavoritesOnly = false },
                    label = { Text("ALL SONGS", fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AccentDeep,
                        selectedLabelColor = Color.White,
                        labelColor = Muted
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true, selected = !showFavoritesOnly,
                        borderColor = SurfaceRaised, selectedBorderColor = Accent
                    )
                )
                FilterChip(
                    selected = showFavoritesOnly,
                    onClick = { showFavoritesOnly = true },
                    label = { Text("FAVORITES · ${favoriteIds.size}", fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                    leadingIcon = { Icon(Icons.Default.Favorite, null, modifier = Modifier.size(13.dp)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AccentDeep,
                        selectedLabelColor = Color.White,
                        labelColor = Muted,
                        selectedLeadingIconColor = Accent,
                        iconColor = Muted
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true, selected = showFavoritesOnly,
                        borderColor = SurfaceRaised, selectedBorderColor = Accent
                    )
                )
            }
            if (!permissionGranted) {
                Column(Modifier.fillMaxWidth().weight(1f).background(SurfaceDark, RoundedCornerShape(24.dp)).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Default.MusicNote, null, tint = Accent, modifier = Modifier.size(54.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("Allow music access", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("BSM Music needs permission to find audio files stored on your device.", color = Muted)
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = { requestPermission { granted -> permissionGranted = granted } }) { Text("Grant permission") }
                }
            } else if (songs.isEmpty()) {
                Column(Modifier.fillMaxWidth().weight(1f).background(SurfaceDark, RoundedCornerShape(24.dp)).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Default.MusicNote, null, tint = Accent, modifier = Modifier.size(54.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("Your sound. Your space.", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("No music files found on this device.", color = Muted)
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = { refreshLibrary() }, enabled = !isRefreshing) { Text(if (isRefreshing) "Refreshing…" else "Refresh library") }
                }
            } else if (visibleSongs.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f).background(SurfaceDark, RoundedCornerShape(22.dp)), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                        Icon(Icons.Default.Search, null, tint = Muted, modifier = Modifier.size(38.dp))
                        Spacer(Modifier.height(10.dp))
                        Text(
                            if (showFavoritesOnly) "No favorites yet" else "No matching songs",
                            color = Color.White, fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (showFavoritesOnly) "Tap the heart on a song to save it here" else "Try another song or artist name",
                            color = Muted, fontSize = 12.sp
                        )
                    }
                }
            } else {
                val songListState = rememberLazyListState()
                LazyColumn(state = songListState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp), contentPadding = PaddingValues(bottom = 14.dp)) {
                    items(visibleSongs, key = { it.id }, contentType = { "song-row" }) { song ->
                        SongRow(
                            song = song,
                            isFavorite = song.id in favoriteIds,
                            onSongClick = onSongClick,
                            onToggleFavorite = { toggleFavorite(song) }
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 9.dp, bottom = 8.dp).background(SurfaceRaised, RoundedCornerShape(20.dp)).clickable { if (currentSong != null) showPlayer = true }.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).background(AccentDeep, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.MusicNote, null, tint = Accent, modifier = Modifier.size(23.dp)) }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(currentSong?.title ?: "Nothing playing", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(currentSong?.artist ?: "Choose a song to get started", color = Muted, fontSize = 11.sp, maxLines = 1)
                }
                IconButton(onClick = { if (player.isPlaying) player.pause() else if (currentSong != null) player.play() }) {
                    Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Play or pause", tint = Accent, modifier = Modifier.size(29.dp))
                }
            }
        }
        if (showPlayer && currentSong != null) {
            PlayerScreen(song = currentSong!!, player = player, onClose = { showPlayer = false }, onPrevious = { player.seekToPreviousMediaItem() }, onNext = { player.seekToNextMediaItem() })
        }
    }
}


@Composable
private fun SongRow(
    song: Song,
    isFavorite: Boolean,
    onSongClick: (Song) -> Unit,
    onToggleFavorite: () -> Unit
) {
    val onClick = remember(song, onSongClick) { { onSongClick(song) } }
    Row(
        modifier = Modifier.fillMaxWidth()
            .height(72.dp)
            .background(SurfaceDark, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(start = 13.dp, end = 7.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(46.dp).background(AccentDeep, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.MusicNote, null, tint = Accent)
        }
        Column(Modifier.weight(1f).padding(start = 13.dp)) {
            Text(song.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(song.artist, color = Muted, fontSize = 12.sp, maxLines = 1)
        }
        Text(formatTime(song.duration), color = Muted, fontSize = 11.sp)
        IconButton(onClick = onToggleFavorite, modifier = Modifier.size(42.dp)) {
            Icon(
                if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                tint = if (isFavorite) Accent else Muted,
                modifier = Modifier.size(19.dp)
            )
        }
    }
}

@Composable
private fun PlayerScreen(song: Song, player: Player, onClose: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit) {
    var position by remember(song.id) { mutableFloatStateOf(0f) }
    var duration by remember(song.id) { mutableFloatStateOf(song.duration.toFloat().coerceAtLeast(1f)) }
    var playing by remember { mutableStateOf(player.isPlaying) }
    var isSeeking by remember { mutableStateOf(false) }

    LaunchedEffect(song.id, player) {
        while (true) {
            if (!isSeeking) position = player.currentPosition.toFloat()
            duration = player.duration.takeIf { it > 0 }?.toFloat()
                ?: song.duration.toFloat().coerceAtLeast(1f)
            playing = player.isPlaying
            kotlinx.coroutines.delay(350)
        }
    }

    val repeatLabel = when (player.repeatMode) {
        Player.REPEAT_MODE_ONE -> "Repeat one"
        Player.REPEAT_MODE_ALL -> "Repeat all"
        else -> "Repeat off"
    }

    Box(
        modifier = Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF241016), Background, Background)))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(42.dp).background(SurfaceDark, CircleShape)
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Minimize player", tint = Color.White)
                }
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("NOW PLAYING", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
                    Text("FROM YOUR LIBRARY", color = Muted, fontSize = 9.sp, letterSpacing = 1.2.sp)
                }
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(42.dp))
            }

            Spacer(Modifier.weight(0.65f))
            Box(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                    .aspectRatio(1f)
                    .background(
                        Brush.linearGradient(listOf(Color(0xFF4A1421), AccentDeep, SurfaceRaised)),
                        RoundedCornerShape(30.dp)
                    )
                    .padding(13.dp)
                    .background(Color.White.copy(alpha = 0.035f), RoundedCornerShape(23.dp)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier.fillMaxSize().padding(17.dp)
                        .background(Color.White.copy(alpha = 0.025f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.MusicNote,
                        contentDescription = "Album artwork",
                        tint = Accent,
                        modifier = Modifier.size(94.dp)
                    )
                }
                Text(
                    "BSM",
                    color = Color.White.copy(alpha = 0.28f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 3.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp)
                )
            }

            Spacer(Modifier.height(30.dp))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    song.title,
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1
                )
                Spacer(Modifier.height(7.dp))
                Text(song.artist, color = Muted, fontSize = 14.sp, maxLines = 1)
            }

            Spacer(Modifier.height(24.dp))
            Slider(
                value = position.coerceIn(0f, duration.coerceAtLeast(1f)),
                onValueChange = {
                    isSeeking = true
                    position = it
                },
                onValueChangeFinished = {
                    player.seekTo(position.toLong())
                    isSeeking = false
                },
                valueRange = 0f..duration.coerceAtLeast(1f),
                colors = SliderDefaults.colors(
                    thumbColor = Accent,
                    activeTrackColor = Accent,
                    inactiveTrackColor = Color.White.copy(alpha = 0.14f)
                )
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(position.toLong()), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                Text(formatTime(duration.toLong()), color = Muted, fontSize = 11.sp)
            }

            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(onClick = { player.shuffleModeEnabled = !player.shuffleModeEnabled }) {
                        Icon(
                            Icons.Default.Shuffle,
                            contentDescription = "Toggle shuffle",
                            tint = if (player.shuffleModeEnabled) Accent else Muted
                        )
                    }
                    Text("SHUFFLE", color = if (player.shuffleModeEnabled) Accent else Muted, fontSize = 8.sp, letterSpacing = 1.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onPrevious, modifier = Modifier.size(54.dp)) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = "Previous track", tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    IconButton(
                        onClick = {
                            if (player.isPlaying) player.pause() else player.play()
                            playing = player.isPlaying
                        },
                        modifier = Modifier.size(72.dp).background(
                            Brush.linearGradient(listOf(Color(0xFFFF5365), Accent)),
                            CircleShape
                        )
                    ) {
                        Icon(
                            if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (playing) "Pause" else "Play",
                            tint = Background,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    IconButton(onClick = onNext, modifier = Modifier.size(54.dp)) {
                        Icon(Icons.Default.SkipNext, contentDescription = "Next track", tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(onClick = {
                        player.repeatMode = when (player.repeatMode) {
                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                            else -> Player.REPEAT_MODE_OFF
                        }
                    }) {
                        Icon(
                            if (player.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                            contentDescription = repeatLabel,
                            tint = if (player.repeatMode == Player.REPEAT_MODE_OFF) Muted else Accent
                        )
                    }
                    Text(
                        when (player.repeatMode) {
                            Player.REPEAT_MODE_ONE -> "REPEAT ONE"
                            Player.REPEAT_MODE_ALL -> "REPEAT ALL"
                            else -> "REPEAT"
                        },
                        color = if (player.repeatMode == Player.REPEAT_MODE_OFF) Muted else Accent,
                        fontSize = 8.sp,
                        letterSpacing = 1.sp
                    )
                }
            }
            Spacer(Modifier.weight(0.8f))
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
