package com.bsm.musicplayer

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.Image
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Queue
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
    val album: String = "Unknown album",
    val albumId: Long = -1L,
    val searchText: String = normalizeSearchText("$title $artist $album")
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
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.ALBUM
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        val cursor: Cursor? = contentResolver.query(collection, projection, selection, null, sortOrder)
        cursor?.use {
            val idCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val durationCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val albumIdCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val albumCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            while (it.moveToNext()) {
                val id = it.getLong(idCol)
                songs += Song(
                    id = id,
                    title = it.getString(titleCol) ?: "Unknown title",
                    artist = it.getString(artistCol)?.takeIf { name -> name != "<unknown>" } ?: "Unknown artist",
                    uri = ContentUris.withAppendedId(collection, id),
                    duration = it.getLong(durationCol),
                    album = it.getString(albumCol)?.takeIf { name -> name.isNotBlank() && name != "<unknown>" } ?: "Unknown album",
                    albumId = it.getLong(albumIdCol)
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
    var sleepTimerMinutes by remember { mutableIntStateOf(0) }
    var sleepTimerRemaining by remember { mutableLongStateOf(0L) }
    var showQueue by remember { mutableStateOf(false) }
    var queueTracks by remember { mutableStateOf(emptyList<Pair<Int, Song>>()) }
    var queueCurrentIndex by remember { mutableIntStateOf(-1) }
    var searchQuery by remember { mutableStateOf("") }
    var sortByArtist by remember { mutableStateOf(false) }
    var librarySection by remember { mutableStateOf("SONGS") }
    var selectedArtist by remember { mutableStateOf<String?>(null) }
    var selectedAlbum by remember { mutableStateOf<Long?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }
    LaunchedEffect(sleepTimerMinutes, player) {
        if (sleepTimerMinutes > 0) {
            val deadline = System.currentTimeMillis() + sleepTimerMinutes * 60_000L
            while (true) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0L) break
                sleepTimerRemaining = remaining
                kotlinx.coroutines.delay(1000)
            }
            player.pause()
            sleepTimerMinutes = 0
            sleepTimerRemaining = 0L
        } else {
            sleepTimerRemaining = 0L
        }
    }

    val context = LocalContext.current
    val favoritesPrefs = remember(context) { context.getSharedPreferences("bsm_music_preferences", android.content.Context.MODE_PRIVATE) }
    var favoriteIds by remember {
        mutableStateOf(favoritesPrefs.getStringSet("favorite_song_ids", emptySet())?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet())
    }
    var recentSongIds by remember {
        mutableStateOf(favoritesPrefs.getString("recent_song_ids", "").orEmpty()
            .split(",").mapNotNull { it.toLongOrNull() }.distinct().take(50))
    }
    var playCounts by remember {
        mutableStateOf<Map<Long, Int>>(
            favoritesPrefs.getStringSet("play_counts", emptySet<String>())
                ?.mapNotNull { entry ->
                    val parts = entry.split(":")
                    if (parts.size == 2) parts[0].toLongOrNull()?.let { id -> id to (parts[1].toIntOrNull() ?: 0) } else null
                }?.toMap() ?: emptyMap<Long, Int>()
        )
    }
    var showFavoritesOnly by remember { mutableStateOf(false) }
    var playlistNames by remember { mutableStateOf<List<String>>(favoritesPrefs.getStringSet("playlist_names", emptySet<String>())?.toList()?.sorted() ?: emptyList()) }
    var playlistSongCounts by remember(favoritesPrefs) {
        mutableStateOf(playlistNames.associateWith { name ->
            favoritesPrefs.getStringSet("playlist_$name", emptySet<String>())
                ?.count { it.toLongOrNull() != null } ?: 0
        })
    }
    var selectedPlaylist by remember { mutableStateOf<String?>(null) }
    var songForPlaylist by remember { mutableStateOf<Song?>(null) }
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var showManagePlaylists by remember { mutableStateOf(false) }
    var playlistToRename by remember { mutableStateOf<String?>(null) }
    var renamePlaylistName by remember { mutableStateOf("") }
    var newPlaylistName by remember { mutableStateOf("") }
    fun playlistSongIds(name: String): Set<Long> = favoritesPrefs.getStringSet("playlist_$name", emptySet<String>())?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
    fun savePlaylistSongIds(name: String, ids: Set<Long>) {
        favoritesPrefs.edit().putStringSet("playlist_$name", ids.map { it.toString() }.toSet()).apply()
        playlistSongCounts = playlistSongCounts + (name to ids.size)
    }
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
    LaunchedEffect(songs, searchQuery, sortByArtist, favoriteIds, showFavoritesOnly, selectedPlaylist, playlistNames, selectedArtist, selectedAlbum, recentSongIds, playCounts, librarySection) {
        val queryWords = normalizeSearchText(searchQuery).split(' ').filter { it.isNotBlank() }
        val activePlaylist = selectedPlaylist
        visibleSongs = withContext(Dispatchers.Default) {
            val searched = if (queryWords.isEmpty()) songs else {
                songs.filter { song -> queryWords.all { word -> word in song.searchText } }
            }
            val filtered = searched.filter { song ->
                (!showFavoritesOnly || song.id in favoriteIds) &&
                    (activePlaylist == null || song.id in playlistSongIds(activePlaylist)) &&
                    (selectedArtist == null || song.artist == selectedArtist) &&
                    (selectedAlbum == null || song.albumId == selectedAlbum)
            }
            when (librarySection) {
                "RECENT" -> {
                    val recentOrder = recentSongIds.withIndex().associate { it.value to it.index }
                    filtered.filter { it.id in recentOrder }.sortedBy { recentOrder[it.id] ?: Int.MAX_VALUE }
                }
                "TOP" -> filtered.filter { (playCounts[it.id] ?: 0) > 0 }
                    .sortedWith(compareByDescending<Song> { playCounts[it.id] ?: 0 }.thenBy { it.title.lowercase() })
                else -> if (sortByArtist) filtered.sortedBy { it.artist.lowercase() } else filtered
            }
        }
    }
    fun addSongToQueueNext(song: Song) {
        val insertAt = if (player.currentMediaItemIndex >= 0) player.currentMediaItemIndex + 1 else 0
        player.addMediaItem(insertAt.coerceAtMost(player.mediaItemCount), MediaItem.fromUri(song.uri))
    }
    fun addSongToQueueEnd(song: Song) {
        player.addMediaItem(MediaItem.fromUri(song.uri))
    }

    val onSongClick: (Song) -> Unit = remember(player, songs, visibleSongs) {
        { song ->
            val activeSongs = visibleSongs.ifEmpty { songs }
            val activeIndex = activeSongs.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
            player.setMediaItems(activeSongs.map { MediaItem.fromUri(it.uri) })
            player.shuffleModeEnabled = false
            player.seekTo(activeIndex, 0L)
            player.prepare()
            player.play()
            currentSong = song
            showPlayer = true
        }
    }

    fun playCollection(collection: List<Song>, shuffle: Boolean) {
        if (collection.isEmpty()) return
        player.shuffleModeEnabled = shuffle
        player.setMediaItems(collection.map { MediaItem.fromUri(it.uri) })
        player.prepare()
        player.play()
        currentSong = collection.first()
        showPlayer = true
    }

    LaunchedEffect(mediaItems) {
        player.setMediaItems(mediaItems)
    }

    DisposableEffect(player, songs, recentSongIds, playCounts) {
        val listener = object : Player.Listener {
            private fun updateQueue() {
                queueTracks = (0 until player.mediaItemCount).mapNotNull { index ->
                    val uri = player.getMediaItemAt(index).localConfiguration?.uri
                    val song = songs.firstOrNull { it.uri == uri }
                    song?.let { index to it }
                }
                queueCurrentIndex = player.currentMediaItemIndex
            }
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) { updateQueue() }
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                val uri = mediaItem?.localConfiguration?.uri
                val playedSong = songs.firstOrNull { it.uri == uri }
                currentSong = playedSong ?: currentSong
                if (playedSong != null) {
                    val updatedRecent = (listOf(playedSong.id) + recentSongIds.filterNot { it == playedSong.id }).take(50)
                    recentSongIds = updatedRecent
                    favoritesPrefs.edit().putString("recent_song_ids", updatedRecent.joinToString(",")).apply()
                    val updatedCounts = playCounts + (playedSong.id to ((playCounts[playedSong.id] ?: 0) + 1))
                    playCounts = updatedCounts
                    favoritesPrefs.edit().putStringSet(
                        "play_counts",
                        updatedCounts.map { (id, count) -> "$id:$count" }.toSet()
                    ).apply()
                }
                updateQueue()
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
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)
                .pointerInput(librarySection) {
                    var horizontalTravel = 0f
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, amount ->
                            horizontalTravel += amount
                            change.consume()
                        },
                        onDragEnd = {
                            val sections = listOf("SONGS", "ARTISTS", "ALBUMS", "RECENT", "TOP")
                            val current = sections.indexOf(librarySection).coerceAtLeast(0)
                            if (horizontalTravel < -65f && current < sections.lastIndex) {
                                librarySection = sections[current + 1]
                                selectedArtist = null
                                selectedAlbum = null
                            } else if (horizontalTravel > 65f && current > 0) {
                                librarySection = sections[current - 1]
                                selectedArtist = null
                                selectedAlbum = null
                            }
                            horizontalTravel = 0f
                        },
                        onDragCancel = { horizontalTravel = 0f }
                    )
                }
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 7.dp, bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("YOUR MUSIC. YOUR RULES.", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("BSM", color = Accent, fontSize = 25.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                        Text(" MUSIC", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                    }
                }
                IconButton(onClick = { refreshLibrary() }, enabled = !isRefreshing) {
                    if (isRefreshing) CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(19.dp))
                    else Icon(Icons.Default.Refresh, contentDescription = "Refresh library", tint = Accent)
                }
            }
            OutlinedTextField(
                value = searchQuery, onValueChange = { searchQuery = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                singleLine = true, shape = RoundedCornerShape(16.dp),
                placeholder = { Text("Search songs or artists", color = Muted, fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = Accent) },
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, unfocusedBorderColor = SurfaceDark, focusedContainerColor = SurfaceDark, unfocusedContainerColor = SurfaceDark, focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = Accent)
            )
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(19.dp))
                    .background(Brush.linearGradient(listOf(AccentDeep, SurfaceRaised)))
                    .padding(horizontal = 15.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("YOUR LIBRARY", color = Color.White.copy(alpha = 0.7f), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${songs.size}", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Black)
                        Spacer(Modifier.width(7.dp))
                        Text("TRACKS", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Button(onClick = {
                    if (songs.isNotEmpty()) {
                        currentSong = songs.first()
                        player.shuffleModeEnabled = false
                        player.seekTo(0, 0L)
                        if (player.playbackState == Player.STATE_IDLE) player.prepare()
                        player.play()
                        showPlayer = true
                    }
                }, enabled = songs.isNotEmpty(), shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 13.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Background, disabledContainerColor = SurfaceRaised, disabledContentColor = Muted)
                ) {
                    Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("PLAY ALL", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                }
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Default.MusicNote, null, tint = Accent, modifier = Modifier.size(27.dp))
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(selectedArtist ?: (selectedAlbum?.let { id -> songs.firstOrNull { it.albumId == id }?.album } ?: "YOUR LIBRARY"), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp, maxLines = 1)
                    Text(
                        when (librarySection) {
                            "ARTISTS" -> "${visibleSongs.map { it.artist }.distinct().size} ARTISTS"
                            "ALBUMS" -> "${visibleSongs.map { it.albumId }.distinct().size} ALBUMS"
                            "RECENT" -> "${visibleSongs.size} RECENT TRACKS"
                            "TOP" -> "${visibleSongs.size} MOST PLAYED TRACKS"
                            else -> "${visibleSongs.size} SONGS"
                        },
                        color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp
                    )
                }
                if (selectedArtist != null || selectedAlbum != null) {
                    TextButton(onClick = {
                        if (selectedAlbum != null && selectedArtist != null) {
                            selectedAlbum = null
                        } else {
                            selectedArtist = null
                            selectedAlbum = null
                            librarySection = "SONGS"
                        }
                    }) { Text("BACK", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                } else if (librarySection == "SONGS") {
                    TextButton(onClick = { sortByArtist = !sortByArtist }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                        Text(if (sortByArtist) "ARTIST A–Z" else "TITLE A–Z", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 5.dp),
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
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 5.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("SONGS", "ARTISTS", "ALBUMS", "RECENT", "TOP").forEach { section ->
                    FilterChip(
                        selected = librarySection == section,
                        onClick = { librarySection = section; selectedArtist = null; selectedAlbum = null },
                        label = { Text(section, fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AccentDeep,
                            selectedLabelColor = Color.White,
                            labelColor = Muted
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true, selected = librarySection == section,
                            borderColor = SurfaceRaised, selectedBorderColor = Accent
                        )
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("PLAYLISTS", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
                if (playlistNames.isNotEmpty()) {
                    TextButton(onClick = { showManagePlaylists = true }) {
                        Text("MANAGE", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
                TextButton(onClick = { newPlaylistName = ""; showCreatePlaylist = true }) {
                    Icon(Icons.Default.PlaylistAdd, null, tint = Accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("NEW", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (playlistNames.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 5.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    FilterChip(selected = selectedPlaylist == null, onClick = { selectedPlaylist = null }, label = { Text("ALL", fontSize = 10.sp) })
                    playlistNames.forEach { name ->
                        FilterChip(
                            selected = selectedPlaylist == name,
                            onClick = { selectedPlaylist = if (selectedPlaylist == name) null else name; showFavoritesOnly = false },
                            label = { Text("$name · ${playlistSongCounts[name] ?: 0}", fontSize = 10.sp, maxLines = 1) }
                        )
                    }
                }
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
                val activePlaylistName = selectedPlaylist
                val isEmptyPlaylist = activePlaylistName != null &&
                    playlistSongIds(activePlaylistName).isEmpty() && searchQuery.isBlank()
                Box(Modifier.fillMaxWidth().weight(1f).background(SurfaceDark, RoundedCornerShape(22.dp)), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                        Icon(
                            if (isEmptyPlaylist) Icons.Default.MusicNote else Icons.Default.Search,
                            null,
                            tint = if (isEmptyPlaylist) Accent else Muted,
                            modifier = Modifier.size(42.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            when {
                                isEmptyPlaylist -> "${activePlaylistName} is empty"
                                showFavoritesOnly -> "No favorites yet"
                                librarySection == "RECENT" -> "Nothing played yet"
                                librarySection == "TOP" -> "No play statistics yet"
                                else -> "No matching songs"
                            },
                            color = Color.White, fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(5.dp))
                        Text(
                            when {
                                isEmptyPlaylist -> "Add songs from your library using the playlist button."
                                showFavoritesOnly -> "Tap the heart on a song to save it here"
                                librarySection == "RECENT" -> "Your recently played tracks will appear here."
                                librarySection == "TOP" -> "Play some music and your most played tracks will appear here."
                                else -> "Try another song or artist name"
                            },
                            color = Muted, fontSize = 12.sp
                        )
                        if (isEmptyPlaylist) {
                            Spacer(Modifier.height(14.dp))
                            Button(
                                onClick = {
                                    selectedPlaylist = null
                                    showFavoritesOnly = false
                                    searchQuery = ""
                                },
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Background)
                            ) {
                                Text("BROWSE LIBRARY", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                            }
                        }
                    }
                }
            } else if (selectedArtist == null && selectedAlbum == null && librarySection == "ARTISTS") {
                val artists = visibleSongs.map { it.artist }.distinct().sortedBy { it.lowercase() }
                val artistRows = artists.chunked(2)
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 14.dp)) {
                    items(artistRows) { artistRow ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            artistRow.forEach { artist ->
                                val artistSongs = visibleSongs.filter { it.artist == artist }
                                Column(
                                    Modifier.weight(1f).background(SurfaceDark, RoundedCornerShape(18.dp))
                                        .clickable { selectedArtist = artist }.padding(11.dp)
                                ) {
                                    AlbumArtwork(artistSongs.first(), Modifier.fillMaxWidth().height(104.dp))
                                    Spacer(Modifier.height(10.dp))
                                    Text(artist, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
                                    Text("${artistSongs.size} tracks · ${artistSongs.map { it.albumId }.distinct().size} albums", color = Muted, fontSize = 10.sp, maxLines = 1)
                                }
                            }
                            if (artistRow.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            } else if (selectedArtist == null && selectedAlbum == null && librarySection == "ALBUMS") {
                val albums = visibleSongs.distinctBy { it.albumId }.sortedBy { it.album.lowercase() }
                val albumRows = albums.chunked(2)
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 14.dp)) {
                    items(albumRows) { albumRow ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            albumRow.forEach { albumSong ->
                                val albumSongs = visibleSongs.filter { it.albumId == albumSong.albumId }
                                Column(
                                    Modifier.weight(1f).background(SurfaceDark, RoundedCornerShape(18.dp))
                                        .clickable { selectedAlbum = albumSong.albumId }.padding(11.dp)
                                ) {
                                    AlbumArtwork(albumSong, Modifier.fillMaxWidth().height(126.dp))
                                    Spacer(Modifier.height(9.dp))
                                    Text(albumSong.album, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                                    Text(albumSong.artist, color = Muted, fontSize = 10.sp, maxLines = 1)
                                    Text("${albumSongs.size} tracks", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            if (albumRow.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            } else {
                val detailAlbumSong = selectedAlbum?.let { albumId -> visibleSongs.firstOrNull { it.albumId == albumId } }
                if (selectedArtist != null || detailAlbumSong != null) {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .padding(bottom = 12.dp)
                            .background(
                                Brush.linearGradient(listOf(AccentDeep, SurfaceDark)),
                                RoundedCornerShape(22.dp)
                            )
                            .padding(15.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (detailAlbumSong != null) {
                                AlbumArtwork(detailAlbumSong, Modifier.size(82.dp))
                            } else {
                                Box(
                                    Modifier.size(82.dp).background(SurfaceRaised, RoundedCornerShape(18.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Person, null, tint = Accent, modifier = Modifier.size(42.dp))
                                }
                            }
                            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                Text(
                                    if (detailAlbumSong != null) "ALBUM" else "ARTIST",
                                    color = Accent,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 1.7.sp
                                )
                                Spacer(Modifier.height(5.dp))
                                Text(
                                    detailAlbumSong?.album ?: selectedArtist.orEmpty(),
                                    color = Color.White,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    maxLines = 2
                                )
                                Text(
                                    if (detailAlbumSong != null) detailAlbumSong.artist else "${visibleSongs.size} songs · ${visibleSongs.map { it.albumId }.distinct().size} albums",
                                    color = Muted,
                                    fontSize = 11.sp,
                                    maxLines = 1
                                )
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            Button(
                                onClick = { playCollection(visibleSongs, false) },
                                enabled = visibleSongs.isNotEmpty(),
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(13.dp),
                                contentPadding = PaddingValues(vertical = 10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Background)
                            ) {
                                Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("PLAY ALL", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                            }
                            OutlinedButton(
                                onClick = { playCollection(visibleSongs, true) },
                                enabled = visibleSongs.isNotEmpty(),
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(13.dp),
                                contentPadding = PaddingValues(vertical = 10.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Accent.copy(alpha = 0.65f))
                            ) {
                                Icon(Icons.Default.Shuffle, null, tint = Accent, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("SHUFFLE", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                            }
                        }
                        if (selectedArtist != null && selectedAlbum == null) {
                            val artistAlbums = visibleSongs.distinctBy { it.albumId }.sortedBy { it.album.lowercase() }
                            if (artistAlbums.isNotEmpty()) {
                                Spacer(Modifier.height(15.dp))
                                Text("ALBUMS BY THIS ARTIST", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    artistAlbums.forEach { albumSong ->
                                        Column(
                                            Modifier.width(104.dp).clickable { selectedAlbum = albumSong.albumId }
                                        ) {
                                            AlbumArtwork(albumSong, Modifier.fillMaxWidth().height(88.dp))
                                            Spacer(Modifier.height(5.dp))
                                            Text(albumSong.album, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                            Text("${visibleSongs.count { it.albumId == albumSong.albumId }} tracks", color = Muted, fontSize = 9.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                val songListState = rememberLazyListState()
                LazyColumn(state = songListState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp), contentPadding = PaddingValues(bottom = 14.dp)) {
                    items(visibleSongs, key = { it.id }, contentType = { "song-row" }) { song ->
                        SongRow(
                            song = song,
                            isFavorite = song.id in favoriteIds,
                            onSongClick = onSongClick,
                            onToggleFavorite = { toggleFavorite(song) },
                            onAddToPlaylist = { songForPlaylist = song },
                            onAddToQueue = { addSongToQueueNext(song) },
                            onAddToQueueEnd = { addSongToQueueEnd(song) },
                            extraLabel = if (librarySection == "TOP") "${playCounts[song.id] ?: 0} PLAYS" else null
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 9.dp, bottom = 8.dp).background(SurfaceRaised, RoundedCornerShape(20.dp)).clickable { if (currentSong != null) showPlayer = true }.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (currentSong != null) AlbumArtwork(currentSong!!, Modifier.size(42.dp))
                else Box(Modifier.size(42.dp).background(AccentDeep, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.MusicNote, null, tint = Accent, modifier = Modifier.size(23.dp)) }
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
            PlayerScreen(song = currentSong!!, player = player, isFavorite = currentSong!!.id in favoriteIds, onToggleFavorite = { toggleFavorite(currentSong!!) }, onAddToPlaylist = { songForPlaylist = currentSong }, onClose = { showPlayer = false }, onPrevious = { player.seekToPreviousMediaItem() }, onNext = { player.seekToNextMediaItem() }, onShowQueue = { showQueue = true }, sleepTimerMinutes = sleepTimerMinutes, sleepTimerRemaining = sleepTimerRemaining, onSetSleepTimer = { sleepTimerMinutes = it })
        }
        if (showQueue) {
            AlertDialog(
                onDismissRequest = { showQueue = false },
                title = {
                    Column {
                        Text("PLAYING QUEUE", color = Color.White, fontWeight = FontWeight.ExtraBold)
                        Text("${queueTracks.size} tracks · Drag to reorder", color = Muted, fontSize = 11.sp)
                        if (queueTracks.size > queueCurrentIndex + 1 && queueCurrentIndex >= 0) {
                            TextButton(
                                onClick = { player.removeMediaItems(queueCurrentIndex + 1, player.mediaItemCount) },
                                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)
                            ) { Text("CLEAR UPCOMING", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        if (queueTracks.isEmpty()) {
                            Text("Your queue is empty", color = Muted, fontSize = 13.sp)
                        }
                        queueTracks.forEach { (mediaIndex, queuedSong) ->
                            val isCurrent = mediaIndex == queueCurrentIndex
                            var dragOffset by remember(mediaIndex) { mutableFloatStateOf(0f) }
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .graphicsLayer { translationY = dragOffset }
                                    .zIndex(if (dragOffset != 0f) 1f else 0f)
                                    .pointerInput(mediaIndex, queueCurrentIndex, queueTracks.size) {
                                        detectDragGesturesAfterLongPress(
                                            onDragEnd = {
                                                val steps = (dragOffset / (64.dp.toPx())).roundToInt()
                                                val target = (mediaIndex + steps).coerceIn((queueCurrentIndex + 1).coerceAtLeast(0), (player.mediaItemCount - 1).coerceAtLeast(0))
                                                if (!isCurrent && target != mediaIndex && mediaIndex > queueCurrentIndex) {
                                                    player.moveMediaItem(mediaIndex, target)
                                                }
                                                dragOffset = 0f
                                            },
                                            onDragCancel = { dragOffset = 0f },
                                            onDrag = { change, amount ->
                                                if (!isCurrent && mediaIndex > queueCurrentIndex) {
                                                    dragOffset += amount.y
                                                    change.consume()
                                                }
                                            }
                                        )
                                    }
                                    .background(if (isCurrent) AccentDeep else SurfaceRaised, RoundedCornerShape(12.dp))
                                    .clickable {
                                        player.seekTo(mediaIndex, 0L)
                                        player.play()
                                        currentSong = queuedSong
                                        showPlayer = true
                                        showQueue = false
                                    }
                                    .padding(start = 11.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.DragHandle, contentDescription = if (isCurrent) "Current track" else "Drag to reorder", tint = if (isCurrent) Accent else Muted, modifier = Modifier.size(18.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(queuedSong.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    Text(if (isCurrent) "NOW PLAYING · ${queuedSong.artist}" else queuedSong.artist, color = if (isCurrent) Accent else Muted, fontSize = 10.sp, maxLines = 1)
                                }
                                if (!isCurrent) {
                                    IconButton(onClick = { player.removeMediaItem(mediaIndex) }, modifier = Modifier.size(34.dp)) {
                                        Icon(Icons.Default.Close, contentDescription = "Remove from queue", tint = Muted, modifier = Modifier.size(17.dp))
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showQueue = false }) { Text("DONE", color = Accent) } },
                containerColor = SurfaceDark,
                titleContentColor = Color.White
            )
        }
        if (showCreatePlaylist) {
            AlertDialog(
                onDismissRequest = { showCreatePlaylist = false },
                title = { Text("Create playlist") },
                text = { OutlinedTextField(value = newPlaylistName, onValueChange = { newPlaylistName = it }, singleLine = true, label = { Text("Playlist name") }) },
                confirmButton = {
                    TextButton(onClick = {
                        val name = newPlaylistName.trim()
                        if (name.isNotEmpty() && name !in playlistNames) {
                            playlistNames = (playlistNames + name).sorted()
                            favoritesPrefs.edit().putStringSet("playlist_names", playlistNames.toSet()).apply()
                            songForPlaylist?.let { pendingSong ->
                                savePlaylistSongIds(name, setOf(pendingSong.id))
                                songForPlaylist = null
                            }
                            selectedPlaylist = name
                            showCreatePlaylist = false
                        } else if (name.isEmpty()) {
                            showCreatePlaylist = false
                        }
                    }) { Text("CREATE", color = Accent) }
                },
                dismissButton = { TextButton(onClick = { showCreatePlaylist = false }) { Text("CANCEL") } },
                containerColor = SurfaceDark,
                titleContentColor = Color.White,
                textContentColor = Muted
            )
        }
        if (showManagePlaylists) {
            AlertDialog(
                onDismissRequest = { showManagePlaylists = false },
                title = { Text("Manage playlists") },
                text = {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        playlistNames.forEach { name ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(SurfaceRaised, RoundedCornerShape(14.dp))
                                    .padding(start = 12.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        name,
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1
                                    )
                                    Text(
                                        "${playlistSongCounts[name] ?: 0} songs",
                                        color = Muted,
                                        fontSize = 10.sp
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        playlistToRename = name
                                        renamePlaylistName = name
                                        showManagePlaylists = false
                                    },
                                    contentPadding = PaddingValues(horizontal = 7.dp, vertical = 5.dp)
                                ) { Text("RENAME", color = Accent, fontSize = 9.sp) }
                                TextButton(
                                    onClick = {
                                        playlistNames = playlistNames - name
                                        favoritesPrefs.edit()
                                            .putStringSet("playlist_names", playlistNames.toSet())
                                            .remove("playlist_" + name)
                                            .apply()
                                        playlistSongCounts = playlistSongCounts - name
                                        if (selectedPlaylist == name) selectedPlaylist = null
                                    },
                                    contentPadding = PaddingValues(horizontal = 7.dp, vertical = 5.dp)
                                ) { Text("DELETE", color = Color(0xFFFF6B78), fontSize = 9.sp) }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showManagePlaylists = false }) { Text("DONE", color = Accent) } },
                containerColor = SurfaceDark,
                titleContentColor = Color.White
            )
        }
        playlistToRename?.let { oldName ->
            AlertDialog(
                onDismissRequest = { playlistToRename = null },
                title = { Text("Rename playlist") },
                text = {
                    OutlinedTextField(value = renamePlaylistName, onValueChange = { renamePlaylistName = it }, singleLine = true, label = { Text("New name") })
                },
                confirmButton = {
                    TextButton(onClick = {
                        val newName = renamePlaylistName.trim()
                        if (newName.isNotEmpty() && newName !in playlistNames) {
                            val ids = playlistSongIds(oldName)
                            playlistNames = (playlistNames - oldName + newName).sorted()
                            favoritesPrefs.edit()
                                .putStringSet("playlist_names", playlistNames.toSet())
                                .putStringSet("playlist_" + newName, ids.map { it.toString() }.toSet())
                                .remove("playlist_" + oldName)
                                .apply()
                            playlistSongCounts = (playlistSongCounts - oldName) + (newName to ids.size)
                            if (selectedPlaylist == oldName) selectedPlaylist = newName
                        }
                        playlistToRename = null
                    }) { Text("SAVE", color = Accent) }
                },
                dismissButton = { TextButton(onClick = { playlistToRename = null }) { Text("CANCEL") } },
                containerColor = SurfaceDark,
                titleContentColor = Color.White,
                textContentColor = Muted
            )
        }
        songForPlaylist?.let { song ->
            AlertDialog(
                onDismissRequest = { songForPlaylist = null },
                title = { Text("Add to playlist") },
                text = {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (playlistNames.isEmpty()) {
                            Text(
                                "No playlists yet. Create one to organize your music.",
                                color = Muted,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                        playlistNames.forEach { name ->
                            val alreadyAdded = song.id in playlistSongIds(name)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(SurfaceRaised, RoundedCornerShape(14.dp))
                                    .clickable {
                                        if (!alreadyAdded) {
                                            savePlaylistSongIds(name, playlistSongIds(name) + song.id)
                                        }
                                        songForPlaylist = null
                                        selectedPlaylist = name
                                        showFavoritesOnly = false
                                    }
                                    .padding(horizontal = 13.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        name,
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1
                                    )
                                    Text(
                                        "${playlistSongCounts[name] ?: 0} songs",
                                        color = Muted,
                                        fontSize = 10.sp
                                    )
                                }
                                Text(
                                    if (alreadyAdded) "ADDED" else "ADD",
                                    color = if (alreadyAdded) Muted else Accent,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.7.sp
                                )
                            }
                        }
                        TextButton(
                            onClick = { newPlaylistName = ""; showCreatePlaylist = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("+ CREATE NEW PLAYLIST", color = Accent, fontWeight = FontWeight.Bold)
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { songForPlaylist = null }) { Text("DONE", color = Accent) } },
                containerColor = SurfaceDark,
                titleContentColor = Color.White
            )
        }
    }
}


@Composable
private fun SongRow(
    song: Song,
    isFavorite: Boolean,
    onSongClick: (Song) -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToQueueEnd: () -> Unit,
    extraLabel: String? = null
) {
    val onClick = remember(song, onSongClick) { { onSongClick(song) } }
    var showActions by remember(song.id) { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth()
            .height(78.dp)
            .background(SurfaceDark, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = 5.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AlbumArtwork(song, Modifier.size(54.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 7.dp)) {
            Text(song.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
            Spacer(Modifier.height(3.dp))
            Text(song.artist, color = Muted, fontSize = 11.sp, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            Text(
                if (extraLabel == null) formatTime(song.duration) else "$extraLabel · ${formatTime(song.duration)}",
                color = Accent.copy(alpha = 0.9f), fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1
            )
        }
        IconButton(onClick = onToggleFavorite, modifier = Modifier.size(38.dp)) {
            Icon(
                if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                tint = if (isFavorite) Accent else Muted,
                modifier = Modifier.size(19.dp)
            )
        }
        Box {
            IconButton(onClick = { showActions = true }, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Default.MoreVert, contentDescription = "Song actions", tint = Muted, modifier = Modifier.size(21.dp))
            }
            DropdownMenu(expanded = showActions, onDismissRequest = { showActions = false }) {
                DropdownMenuItem(
                    text = { Text("Play next") },
                    leadingIcon = { Icon(Icons.Default.QueueMusic, null) },
                    onClick = { showActions = false; onAddToQueue() }
                )
                DropdownMenuItem(
                    text = { Text("Add to queue") },
                    leadingIcon = { Icon(Icons.Default.Queue, null) },
                    onClick = { showActions = false; onAddToQueueEnd() }
                )
                DropdownMenuItem(
                    text = { Text("Add to playlist") },
                    leadingIcon = { Icon(Icons.Default.PlaylistAdd, null) },
                    onClick = { showActions = false; onAddToPlaylist() }
                )
            }
        }
    }
}

@Composable
private fun PlayerScreen(
    song: Song,
    player: Player,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onClose: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onShowQueue: () -> Unit,
    sleepTimerMinutes: Int,
    sleepTimerRemaining: Long,
    onSetSleepTimer: (Int) -> Unit
) {
    var position by remember(song.id) { mutableFloatStateOf(0f) }
    var duration by remember(song.id) { mutableFloatStateOf(song.duration.toFloat().coerceAtLeast(1f)) }
    var playing by remember { mutableStateOf(player.isPlaying) }
    var isSeeking by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableFloatStateOf(player.playbackParameters.speed) }
    var showSleepDialog by remember { mutableStateOf(false) }

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

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF35121D), Color(0xFF151016), Background, Background)))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        val compactLayout = maxHeight < 760.dp || maxWidth < 370.dp
        val artworkSize = minOf(maxWidth - 60.dp, maxHeight * if (compactLayout) 0.28f else 0.36f).coerceAtLeast(150.dp)
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = if (compactLayout) 14.dp else 22.dp, vertical = if (compactLayout) 4.dp else 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(46.dp),
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
                    Text("BSM MUSIC PLAYER", color = Muted, fontSize = 9.sp, letterSpacing = 1.4.sp)
                }
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = { showSleepDialog = true },
                    modifier = Modifier.size(42.dp).background(SurfaceDark, CircleShape)
                ) {
                    Icon(Icons.Default.Alarm, contentDescription = "Sleep timer", tint = if (sleepTimerMinutes > 0) Accent else Muted)
                }
                Spacer(Modifier.width(6.dp))
                IconButton(
                    onClick = onShowQueue,
                    modifier = Modifier.size(42.dp).background(SurfaceDark, CircleShape)
                ) {
                    Icon(Icons.Default.QueueMusic, contentDescription = "Open playing queue", tint = Accent)
                }
            }

            Spacer(Modifier.weight(if (compactLayout) 0.12f else 0.42f))
            Box(
                modifier = Modifier.size(artworkSize)
                    .background(
                        Brush.linearGradient(listOf(Color(0xFF641C30), AccentDeep, SurfaceRaised)),
                        RoundedCornerShape(30.dp)
                    )
                    .padding(12.dp)
                    .background(Color.White.copy(alpha = 0.045f), RoundedCornerShape(23.dp)),
                contentAlignment = Alignment.Center
            ) {
                AlbumArtwork(song, Modifier.fillMaxSize().padding(14.dp).clip(RoundedCornerShape(18.dp)))
                Box(
                    Modifier.align(Alignment.BottomCenter)
                        .padding(bottom = 14.dp)
                        .background(Color.Black.copy(alpha = 0.48f), RoundedCornerShape(30.dp))
                        .padding(horizontal = 13.dp, vertical = 5.dp)
                ) {
                    Text("BSM • MUSIC", color = Color.White.copy(alpha = 0.82f), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
                }
            }

            Spacer(Modifier.height(if (compactLayout) 12.dp else 23.dp))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    song.title,
                    color = Color.White,
                    fontSize = if (compactLayout) 19.sp else 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1
                )
                Spacer(Modifier.height(6.dp))
                Text(song.artist, color = Color.White.copy(alpha = 0.78f), fontSize = 14.sp, maxLines = 1)
                Text(song.album, color = Muted, fontSize = 11.sp, maxLines = 1)
            }

            Spacer(Modifier.height(if (compactLayout) 4.dp else 9.dp))
            PlaybackVisualizer(isPlaying = playing)
            Spacer(Modifier.height(if (compactLayout) 4.dp else 8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                PlayerActionButton(
                    icon = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    label = if (isFavorite) "SAVED" else "FAVORITE",
                    tint = if (isFavorite) Accent else Muted,
                    onClick = onToggleFavorite
                )
                Spacer(Modifier.width(34.dp))
                PlayerActionButton(
                    icon = Icons.Default.PlaylistAdd,
                    label = "PLAYLIST",
                    tint = Muted,
                    onClick = onAddToPlaylist
                )
                Spacer(Modifier.width(34.dp))
                PlayerActionButton(
                    icon = Icons.Default.QueueMusic,
                    label = "QUEUE",
                    tint = Muted,
                    onClick = onShowQueue
                )
                Spacer(Modifier.width(18.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    TextButton(
                        onClick = { showSpeedDialog = true },
                        modifier = Modifier.height(42.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                    ) {
                        Text("${playbackSpeed}×", color = Accent, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                    Text("SPEED", color = Muted, fontSize = 8.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.Bold)
                }
            }

            if (sleepTimerMinutes > 0) {
                Text(
                    "SLEEP TIMER · ${formatTime(sleepTimerRemaining)}",
                    color = Accent,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
            Spacer(Modifier.height(if (compactLayout) 4.dp else 8.dp))
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
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    formatTime(position.toLong()),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "−10s",
                    color = Muted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(SurfaceDark, RoundedCornerShape(20.dp))
                        .clickable {
                            player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L))
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "+10s",
                    color = Muted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(SurfaceDark, RoundedCornerShape(20.dp))
                        .clickable {
                            val totalDuration = player.duration.takeIf { it > 0L }
                            val target = player.currentPosition + 10_000L
                            player.seekTo(totalDuration?.let { target.coerceAtMost(it) } ?: target)
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
                Spacer(Modifier.weight(1f))
                Text(formatTime(duration.toLong()), color = Muted, fontSize = 11.sp)
            }

            Spacer(Modifier.height(8.dp))
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
                    IconButton(onClick = onPrevious, modifier = Modifier.size(52.dp)) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = "Previous track", tint = Color.White, modifier = Modifier.size(31.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            if (player.isPlaying) player.pause() else player.play()
                            playing = player.isPlaying
                        },
                        modifier = Modifier.size(70.dp).background(
                            Brush.linearGradient(listOf(Color(0xFFFF6675), Accent)),
                            CircleShape
                        )
                    ) {
                        Icon(
                            if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (playing) "Pause" else "Play",
                            tint = Background,
                            modifier = Modifier.size(39.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = onNext, modifier = Modifier.size(52.dp)) {
                        Icon(Icons.Default.SkipNext, contentDescription = "Next track", tint = Color.White, modifier = Modifier.size(31.dp))
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
            Spacer(Modifier.weight(if (compactLayout) 0.12f else 0.55f))
        }
    }
    if (showSleepDialog) {
        AlertDialog(
            onDismissRequest = { showSleepDialog = false },
            containerColor = SurfaceDark,
            titleContentColor = Color.White,
            title = { Text("Sleep timer") },
            text = {
                Column {
                    Text("Pause music after", color = Muted, fontSize = 12.sp)
                    listOf(15, 30, 45, 60).forEach { minutes ->
                        TextButton(
                            onClick = {
                                onSetSleepTimer(minutes)
                                showSleepDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("$minutes minutes", color = if (sleepTimerMinutes == minutes) Accent else Color.White)
                        }
                    }
                    if (sleepTimerMinutes > 0) {
                        TextButton(
                            onClick = {
                                onSetSleepTimer(0)
                                showSleepDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("CANCEL TIMER", color = Accent, fontWeight = FontWeight.Bold) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSleepDialog = false }) { Text("CLOSE", color = Accent) }
            }
        )
    }
    if (showSpeedDialog) {
        AlertDialog(
            onDismissRequest = { showSpeedDialog = false },
            containerColor = SurfaceDark,
            titleContentColor = Color.White,
            title = { Text("Playback speed") },
            text = {
                Column {
                    listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { speed ->
                        TextButton(
                            onClick = {
                                player.setPlaybackSpeed(speed)
                                playbackSpeed = speed
                                showSpeedDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "${speed}×",
                                color = if (playbackSpeed == speed) Accent else Color.White,
                                fontWeight = if (playbackSpeed == speed) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSpeedDialog = false }) { Text("CLOSE", color = Accent) }
            }
        )
    }
}

@Composable
private fun PlaybackVisualizer(isPlaying: Boolean) {
    if (isPlaying) {
        val transition = rememberInfiniteTransition(label = "playback_visualizer")
        val levels = listOf(
            transition.animateFloat(0.18f, 0.95f, infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "bar_1").value,
            transition.animateFloat(0.25f, 0.82f, infiniteRepeatable(tween(680, delayMillis = 90), RepeatMode.Reverse), label = "bar_2").value,
            transition.animateFloat(0.12f, 1.00f, infiniteRepeatable(tween(460, delayMillis = 160), RepeatMode.Reverse), label = "bar_3").value,
            transition.animateFloat(0.30f, 0.88f, infiniteRepeatable(tween(610, delayMillis = 50), RepeatMode.Reverse), label = "bar_4").value,
            transition.animateFloat(0.16f, 0.96f, infiniteRepeatable(tween(560, delayMillis = 210), RepeatMode.Reverse), label = "bar_5").value,
            transition.animateFloat(0.22f, 0.78f, infiniteRepeatable(tween(730, delayMillis = 130), RepeatMode.Reverse), label = "bar_6").value,
            transition.animateFloat(0.14f, 0.90f, infiniteRepeatable(tween(490, delayMillis = 260), RepeatMode.Reverse), label = "bar_7").value
        )
        Row(
            modifier = Modifier.height(25.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            levels.forEach { level ->
                Box(
                    Modifier
                        .width(3.dp)
                        .height((6f + 18f * level).dp)
                        .background(Accent, CircleShape)
                )
            }
        }
    } else {
        Row(
            modifier = Modifier.height(25.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            repeat(7) {
                Box(
                    Modifier
                        .width(3.dp)
                        .height(5.dp)
                        .background(Muted.copy(alpha = 0.55f), CircleShape)
                )
            }
        }
    }
}

@Composable
private fun PlayerActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(42.dp).background(SurfaceDark, CircleShape)
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(19.dp))
        }
        Text(label, color = tint, fontSize = 8.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AlbumArtwork(song: Song, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(song.albumId) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(song.albumId) {
        bitmap = if (song.albumId > 0L) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val artworkUri = ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, song.albumId)
                    context.contentResolver.openInputStream(artworkUri)?.use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
                }.getOrNull()
            }
        } else null
    }
    Box(
        modifier = modifier.clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(listOf(AccentDeep, SurfaceRaised))),
        contentAlignment = Alignment.Center
    ) {
        val art = bitmap
        if (art != null) {
            Image(
                bitmap = art.asImageBitmap(),
                contentDescription = "Album artwork for ${song.album}",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(Icons.Default.MusicNote, contentDescription = "No album artwork", tint = Accent, modifier = Modifier.size(25.dp))
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
