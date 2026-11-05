package com.noctra.music

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private val Bg = Color(0xFF090909)
private val Panel = Color(0xFF191919)
private val White = Color(0xFFF5F5F5)
private val Muted = Color(0xFF999999)
private val Accent = Color(0xFFD0D0D0)

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val license: String,
    val stream: String,
    val artwork: String
)

data class Album(
    val id: String,
    val title: String,
    val year: String,
    val type: String,
    val artwork: String,
    val artist: String
)

class MainActivity : ComponentActivity() {

    private lateinit var player: ExoPlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        player = ExoPlayer.Builder(this).build()

        setContent {
            NoctraApp(
                player = player,
                loadCatalog = { query ->
                    lifecycleScope.launch {
                        loadingState.value = true
                        catalogState.value = loadArchiveCatalog(query)
                        loadingState.value = false
                    }
                },
                loadDiscography = { query ->
                    lifecycleScope.launch {
                        albumLoadingState.value = true
                        albumState.value = loadDiscography(query)
                        albumLoadingState.value = false
                    }
                },
                catalog = catalogState,
                loading = loadingState,
                albums = albumState,
                albumLoading = albumLoadingState
            )
        }
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }

    companion object {
        val catalogState = mutableStateOf<List<Track>>(emptyList())
        val loadingState = mutableStateOf(false)
        val albumState = mutableStateOf<List<Album>>(emptyList())
        val albumLoadingState = mutableStateOf(false)
    }
}

private fun request(url: String): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 12000
    connection.readTimeout = 15000
    connection.setRequestProperty(
        "User-Agent",
        "NOCTRA/1.0 (https://github.com/thecpunk/NOCTRA)"
    )
    return try {
        connection.inputStream.bufferedReader().use { it.readText() }
    } finally {
        connection.disconnect()
    }
}

private suspend fun loadArchiveCatalog(query: String): List<Track> =
    withContext(Dispatchers.IO) {
        try {
            val artist = query.trim()
            if (artist.isBlank()) return@withContext emptyList()

            val safe = artist.replace("\"", " ")
                .replace(":", " ")
                .take(100)

            val q = "mediatype:audio AND (creator:\"$safe\" OR title:\"$safe\")"

            val searchUrl =
                "https://archive.org/advancedsearch.php?q=" +
                URLEncoder.encode(q, "UTF-8") +
                "&fl[]=identifier&fl[]=title&fl[]=creator&fl[]=licenseurl" +
                "&rows=80&page=1&output=json"

            val root = JSONObject(request(searchUrl))
            val docs = root.getJSONObject("response").getJSONArray("docs")
            val result = mutableListOf<Track>()

            for (i in 0 until docs.length()) {
                val item = docs.getJSONObject(i)
                val id = item.optString("identifier")
                val license = item.optString("licenseurl")

                val allowed =
                    license.contains("creativecommons.org/licenses/by/", true) ||
                    license.contains("creativecommons.org/licenses/by-sa/", true) ||
                    license.contains("creativecommons.org/publicdomain/zero/", true) ||
                    license.contains("creativecommons.org/publicdomain/mark/", true)

                if (id.isBlank() || !allowed) continue

                try {
                    val metadata = JSONObject(
                        request("https://archive.org/metadata/$id")
                    )

                    val files = metadata.optJSONArray("files")
                        ?: continue

                    var mp3: String? = null

                    for (j in 0 until files.length()) {
                        val file = files.optJSONObject(j) ?: continue
                        val name = file.optString("name")
                        val format = file.optString("format")

                        if (
                            format.contains("MP3", true) ||
                            name.endsWith(".mp3", true)
                        ) {
                            mp3 = name
                            break
                        }
                    }

                    if (mp3 != null) {
                        val encoded = mp3.split("/").joinToString("/") {
                            URLEncoder.encode(it, "UTF-8")
                                .replace("+", "%20")
                        }

                        result.add(
                            Track(
                                id = id,
                                title = item.optString("title", "Без названия"),
                                artist = item.optString("creator", artist),
                                license = license,
                                stream = "https://archive.org/download/$id/$encoded",
                                artwork = "https://archive.org/services/img/$id"
                            )
                        )
                    }
                } catch (_: Exception) {
                    continue
                }
            }

            result.distinctBy { it.id }.take(50)

        } catch (_: Exception) {
            emptyList()
        }
    }

private suspend fun loadDiscography(query: String): List<Album> =
    withContext(Dispatchers.IO) {
        try {
            if (query.isBlank()) return@withContext emptyList()

            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val search = JSONObject(
                request(
                    "https://musicbrainz.org/ws/2/artist/?query=$encoded&fmt=json&limit=10"
                )
            )

            val artists = search.optJSONArray("artists")
                ?: return@withContext emptyList()

            if (artists.length() == 0) return@withContext emptyList()

            var artistObject = artists.getJSONObject(0)

            for (i in 0 until artists.length()) {
                val candidate = artists.getJSONObject(i)
                if (
                    candidate.optString("name")
                        .equals(query.trim(), true)
                ) {
                    artistObject = candidate
                    break
                }
            }

            val artistId = artistObject.optString("id")
            val artistName = artistObject.optString("name", query)

            delay(1100)

            val releases = JSONObject(
                request(
                    "https://musicbrainz.org/ws/2/release-group/" +
                    "?artist=$artistId&limit=100&fmt=json"
                )
            ).optJSONArray("release-groups")
                ?: return@withContext emptyList()

            val result = mutableListOf<Album>()

            for (i in 0 until releases.length()) {
                val release = releases.getJSONObject(i)
                val id = release.optString("id")
                val title = release.optString("title")

                if (id.isBlank() || title.isBlank()) continue

                val date = release.optString("first-release-date")
                val year = if (date.length >= 4) date.take(4) else "—"

                val secondary = release.optJSONArray("secondary-types")
                val extra = mutableListOf<String>()

                if (secondary != null) {
                    for (j in 0 until secondary.length()) {
                        extra.add(secondary.optString(j))
                    }
                }

                val type = buildList {
                    add(release.optString("primary-type", "Релиз"))
                    addAll(extra)
                }.joinToString(" • ")

                result.add(
                    Album(
                        id = id,
                        title = title,
                        year = year,
                        type = type,
                        artwork =
                            "https://coverartarchive.org/release-group/$id/front-250",
                        artist = artistName
                    )
                )
            }

            result.distinctBy { it.id }
                .sortedByDescending { it.year }

        } catch (_: Exception) {
            emptyList()
        }
    }

private suspend fun loadLyrics(track: Track): String =
    withContext(Dispatchers.IO) {
        try {
            val artist = URLEncoder.encode(track.artist, "UTF-8")
            val title = URLEncoder.encode(track.title, "UTF-8")

            val json = JSONObject(
                request("https://api.lyrics.ovh/v1/$artist/$title")
            )

            json.optString("lyrics")
                .ifBlank { "Текст песни пока не найден." }

        } catch (_: Exception) {
            "Не удалось загрузить текст песни."
        }
    }

@Composable
private fun Artwork(
    url: String,
    modifier: Modifier = Modifier
) {
    val bitmap by produceState<Bitmap?>(null, url) {
        value = withContext(Dispatchers.IO) {
            try {
                val connection = URL(url).openConnection()
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.getInputStream().use {
                    BitmapFactory.decodeStream(it)
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = "Обложка",
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF444444), Color(0xFF111111))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Text("N", color = White, fontSize = 28.sp)
        }
    }
}

@Composable
fun NoctraApp(
    player: ExoPlayer,
    loadCatalog: (String) -> Unit,
    loadDiscography: (String) -> Unit,
    catalog: State<List<Track>>,
    loading: State<Boolean>,
    albums: State<List<Album>>,
    albumLoading: State<Boolean>
) {
    val context = LocalContext.current

    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var current by remember { mutableStateOf<Track?>(null) }
    var playing by remember { mutableStateOf(false) }
    var showPlayer by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var lyrics by remember { mutableStateOf("Загружаем текст...") }

    val favorites = remember {
        mutableStateListOf<String>().apply {
            addAll(
                context.getSharedPreferences("noctra", 0)
                    .getStringSet("favorites", emptySet()) ?: emptySet()
            )
        }
    }

    fun toggleFavorite(track: Track) {
        if (favorites.contains(track.id)) {
            favorites.remove(track.id)
        } else {
            favorites.add(track.id)
        }

        context.getSharedPreferences("noctra", 0)
            .edit()
            .putStringSet("favorites", favorites.toSet())
            .apply()
    }

    LaunchedEffect(Unit) {
        if (catalog.value.isEmpty()) {
            // Не загружаем случайные треки без запроса.
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }
        }

        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(current?.id, showLyrics) {
        if (showLyrics && current != null) {
            lyrics = loadLyrics(current!!)
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Bg,
            surface = Panel,
            primary = Accent,
            onBackground = White,
            onSurface = White
        )
    ) {
        Scaffold(
            containerColor = Bg,
            bottomBar = {
                NavigationBar(
                    containerColor = Panel,
                    contentColor = White
                ) {
                    val labels = listOf(
                        "Главная", "Моя волна", "Поиск", "Медиатека"
                    )
                    val icons = listOf(
                        Icons.Default.Home,
                        Icons.Default.GraphicEq,
                        Icons.Default.Search,
                        Icons.Default.Favorite
                    )

                    labels.forEachIndexed { index, label ->
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = { tab = index },
                            icon = { Icon(icons[index], null) },
                            label = { Text(label) }
                        )
                    }
                }
            }
        ) { padding ->

            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 18.dp)
            ) {

                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "NOCTRA",
                            color = White,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 4.sp
                        )
                        Text(
                            "YOUR MUSIC. YOUR NIGHT.",
                            color = Muted,
                            fontSize = 9.sp,
                            letterSpacing = 2.sp
                        )
                    }

                    Text(
                        "N",
                        color = White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Black
                    )
                }

                if (showPlayer && current != null) {
                    FullPlayer(
                        track = current!!,
                        player = player,
                        playing = playing,
                        showLyrics = showLyrics,
                        lyrics = lyrics,
                        onBack = { showPlayer = false },
                        onPlayPause = {
                            if (player.isPlaying) player.pause()
                            else player.play()
                        },
                        onLyrics = { showLyrics = !showLyrics }
                    )
                } else {

                    when (tab) {
                        0 -> {
                            Text(
                                "ТВОЙ МУЗЫКАЛЬНЫЙ МИР",
                                color = Muted,
                                fontSize = 10.sp,
                                letterSpacing = 2.sp
                            )

                            Text(
                                "Слушай то, что\nтебе нравится.",
                                color = White,
                                fontSize = 30.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Spacer(Modifier.height(20.dp))

                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(145.dp)
                                    .clip(RoundedCornerShape(22.dp))
                                    .background(
                                        Brush.linearGradient(
                                            listOf(
                                                Color(0xFF555555),
                                                Color(0xFF242424),
                                                Color(0xFF101010)
                                            )
                                        )
                                    )
                                    .clickable { tab = 1 }
                                    .padding(20.dp)
                            ) {
                                Column(
                                    Modifier.align(Alignment.CenterStart)
                                ) {
                                    Text(
                                        "NOCTRA RADIO",
                                        color = Accent
                                    )
                                    Text(
                                        "ТВОЯ ВОЛНА",
                                        color = White,
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        "Музыка для твоего настроения",
                                        color = Muted
                                    )
                                }
                            }

                            Spacer(Modifier.height(20.dp))
                            SectionTitle("ПОПУЛЯРНЫЕ ТРЕКИ")

                            if (loading.value) LoadingLine()

                            TrackList(
                                catalog.value.take(10),
                                favorites,
                                ::toggleFavorite,
                                onPlay = {
                                    current = it
                                    playTrack(player, it)
                                    showPlayer = true
                                }
                            )
                        }

                        1 -> {
                            SectionTitle("ТВОЯ ВОЛНА")

                            Text(
                                "Лайкай музыку, чтобы формировать свою подборку.",
                                color = Muted
                            )

                            Spacer(Modifier.height(16.dp))

                            TrackList(
                                catalog.value.filter {
                                    !favorites.contains(it.id)
                                },
                                favorites,
                                ::toggleFavorite,
                                onPlay = {
                                    current = it
                                    playTrack(player, it)
                                    showPlayer = true
                                }
                            )
                        }

                        2 -> {
                            SectionTitle("ПОИСК")

                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = {
                                    Text("Исполнитель или название")
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(16.dp)
                            )

                            Spacer(Modifier.height(10.dp))

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        loadCatalog(query)
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Accent
                                    )
                                ) {
                                    Text("Треки", color = Bg)
                                }

                                Button(
                                    onClick = {
                                        loadDiscography(query)
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Panel
                                    )
                                ) {
                                    Text("Дискография", color = White)
                                }
                            }

                            if (loading.value || albumLoading.value) {
                                LoadingLine()
                            }

                            if (albums.value.isNotEmpty()) {
                                Text(
                                    "АЛЬБОМЫ И РЕЛИЗЫ",
                                    color = White,
                                    fontWeight = FontWeight.Bold
                                )

                                LazyColumn(
                                    modifier = Modifier.heightIn(max = 300.dp)
                                ) {
                                    items(albums.value, key = { it.id }) { album ->
                                        AlbumRow(album) {
                                            val url =
                                                "https://music.youtube.com/search?q=" +
                                                URLEncoder.encode(
                                                    "${album.artist} ${album.title}",
                                                    "UTF-8"
                                                )

                                            context.startActivity(
                                                Intent(
                                                    Intent.ACTION_VIEW,
                                                    Uri.parse(url)
                                                )
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height(10.dp))

                            TrackList(
                                catalog.value,
                                favorites,
                                ::toggleFavorite,
                                onPlay = {
                                    current = it
                                    playTrack(player, it)
                                    showPlayer = true
                                }
                            )
                        }

                        3 -> {
                            SectionTitle("ТВОЯ МЕДИАТЕКА")

                            Text(
                                "Избранное",
                                color = White,
                                fontSize = 25.sp,
                                fontWeight = FontWeight.Bold
                            )

                            TrackList(
                                catalog.value.filter {
                                    favorites.contains(it.id)
                                },
                                favorites,
                                ::toggleFavorite,
                                onPlay = {
                                    current = it
                                    playTrack(player, it)
                                    showPlayer = true
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                if (!showPlayer && current != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(15.dp))
                            .background(Panel)
                            .clickable { showPlayer = true }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Artwork(
                            current!!.artwork,
                            Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(10.dp))
                        )

                        Column(
                            Modifier
                                .weight(1f)
                                .padding(horizontal = 10.dp)
                        ) {
                            Text(
                                current!!.title,
                                color = White,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                current!!.artist,
                                color = Muted,
                                fontSize = 11.sp
                            )
                        }

                        IconButton(
                            onClick = {
                                if (player.isPlaying) player.pause()
                                else player.play()
                            }
                        ) {
                            Icon(
                                if (playing) Icons.Default.Pause
                                else Icons.Default.PlayArrow,
                                null,
                                tint = White
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FullPlayer(
    track: Track,
    player: ExoPlayer,
    playing: Boolean,
    showLyrics: Boolean,
    lyrics: String,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onLyrics: () -> Unit
) {
    var position by remember { mutableFloatStateOf(0f) }
    var duration by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(player, track.id) {
        while (true) {
            duration = player.duration
                .takeIf { it > 0 }?.toFloat() ?: 0f
            position = player.currentPosition.toFloat()
            delay(500)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.KeyboardArrowDown, null, tint = White)
            }
            Text(
                "СЕЙЧАС ИГРАЕТ",
                color = Muted,
                fontSize = 11.sp,
                letterSpacing = 2.sp
            )
        }

        Spacer(Modifier.height(18.dp))

        Artwork(
            track.artwork,
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(24.dp))
        )

        Spacer(Modifier.height(22.dp))

        Text(
            track.title,
            color = White,
            fontSize = 23.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Text(track.artist, color = Muted)

        Spacer(Modifier.height(20.dp))

        Slider(
            value = position.coerceIn(0f, duration.coerceAtLeast(1f)),
            onValueChange = { position = it },
            onValueChangeFinished = {
                player.seekTo(position.toLong())
            },
            valueRange = 0f..duration.coerceAtLeast(1f),
            colors = SliderDefaults.colors(
                thumbColor = White,
                activeTrackColor = White,
                inactiveTrackColor = Panel
            )
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            IconButton(onClick = {
                player.seekTo((player.currentPosition - 10000).coerceAtLeast(0))
            }) {
                Icon(Icons.Default.Replay10, null, tint = White)
            }

            IconButton(
                onClick = onPlayPause,
                modifier = Modifier
                    .size(65.dp)
                    .background(White, CircleShape)
            ) {
                Icon(
                    if (playing) Icons.Default.Pause
                    else Icons.Default.PlayArrow,
                    null,
                    tint = Bg,
                    modifier = Modifier.size(35.dp)
                )
            }

            IconButton(onClick = {
                player.seekTo(player.currentPosition + 10000)
            }) {
                Icon(Icons.Default.Forward10, null, tint = White)
            }
        }

        Spacer(Modifier.height(12.dp))

        OutlinedButton(onClick = onLyrics) {
            Icon(Icons.Default.Lyrics, null)
            Spacer(Modifier.width(8.dp))
            Text(if (showLyrics) "Скрыть текст" else "Текст песни")
        }

        if (showLyrics) {
            Spacer(Modifier.height(10.dp))
            Text(
                lyrics,
                color = White,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .background(Panel, RoundedCornerShape(15.dp))
                    .padding(16.dp)
            )
        }
    }
}

@Composable
private fun AlbumRow(album: Album, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Artwork(
            album.artwork,
            Modifier
                .size(58.dp)
                .clip(RoundedCornerShape(10.dp))
        )

        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            Text(
                album.title,
                color = White,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${album.year} • ${album.type}",
                color = Muted,
                fontSize = 11.sp
            )
        }

        Icon(Icons.Default.OpenInNew, null, tint = Muted)
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        color = White,
        fontSize = 19.sp,
        fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun LoadingLine() {
    LinearProgressIndicator(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        color = Accent,
        trackColor = Panel
    )
}

private fun playTrack(player: ExoPlayer, track: Track) {
    player.setMediaItem(MediaItem.fromUri(track.stream))
    player.prepare()
    player.play()
}

@Composable
private fun TrackList(
    tracks: List<Track>,
    favorites: List<String>,
    onFavorite: (Track) -> Unit,
    onPlay: (Track) -> Unit
) {
    if (tracks.isEmpty()) {
        Text(
            "Пока ничего нет. Попробуй поискать музыку.",
            color = Muted,
            modifier = Modifier.padding(vertical = 18.dp)
        )
    } else {
        LazyColumn(
            modifier = Modifier.heightIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            items(tracks, key = { it.id }) { track ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPlay(track) }
                        .padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Artwork(
                        track.artwork,
                        Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )

                    Column(
                        Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp)
                    ) {
                        Text(
                            track.title,
                            color = White,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            track.artist,
                            color = Muted,
                            fontSize = 11.sp
                        )
                    }

                    IconButton(onClick = { onFavorite(track) }) {
                        Icon(
                            if (favorites.contains(track.id))
                                Icons.Default.Favorite
                            else
                                Icons.Default.FavoriteBorder,
                            null,
                            tint = if (favorites.contains(track.id))
                                White else Muted
                        )
                    }
                }
            }
        }
    }
}
