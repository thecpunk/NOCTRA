package com.noctra.music

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
    val stream: String
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
                        catalogState.value = loadArchiveCatalog(query)
                    }
                },
                catalog = catalogState
            )
        }
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }

    companion object {
        val catalogState = mutableStateOf<List<Track>>(emptyList())
    }
}

private suspend fun loadArchiveCatalog(query: String): List<Track> =
    withContext(Dispatchers.IO) {

        try {
            val safeQuery = query.trim()
                .replace("\\", " ")
                .replace("\"", " ")

            val q = if (safeQuery.isBlank()) {
                "mediatype:audio AND licenseurl:*"
            } else {
                "mediatype:audio AND licenseurl:* AND (title:\"$safeQuery\" OR creator:\"$safeQuery\")"
            }

            val url =
                "https://archive.org/advancedsearch.php?q=${URLEncoder.encode(q, "UTF-8")}" +
                "&fl[]=identifier&fl[]=title&fl[]=creator&fl[]=licenseurl" +
                "&sort[]=downloads%20desc&rows=24&page=1&output=json"

            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 10000

            val root = JSONObject(
                conn.inputStream.bufferedReader().use { it.readText() }
            )

            conn.disconnect()

            val docs = root.getJSONObject("response").getJSONArray("docs")

            val candidates = mutableListOf<JSONObject>()

            for (i in 0 until docs.length()) {
                val d = docs.getJSONObject(i)
                val id = d.optString("identifier")
                val lic = d.optString("licenseurl")

                val allowed =
                    lic.contains("creativecommons.org/licenses/by/", true) ||
                    lic.contains("creativecommons.org/licenses/by-sa/", true) ||
                    lic.contains("creativecommons.org/publicdomain/zero/", true) ||
                    lic.contains("creativecommons.org/publicdomain/mark/", true)

                if (id.isNotBlank() && allowed) {
                    candidates.add(d)
                }

                if (candidates.size >= 12) break
            }

            candidates.mapNotNull { d ->
                try {
                    val id = d.optString("identifier")

                    val metaConn =
                        URL("https://archive.org/metadata/$id")
                            .openConnection() as HttpURLConnection

                    metaConn.connectTimeout = 5000
                    metaConn.readTimeout = 7000

                    val meta = JSONObject(
                        metaConn.inputStream.bufferedReader().use { it.readText() }
                    )

                    metaConn.disconnect()

                    val files = meta.optJSONArray("files") ?: return@mapNotNull null

                    var fileName: String? = null

                    for (j in 0 until files.length()) {
                        val f = files.optJSONObject(j) ?: continue
                        val name = f.optString("name")
                        val format = f.optString("format")

                        if (
                            (format.contains("MP3", true) ||
                             name.endsWith(".mp3", true)) &&
                            !name.contains("_spectrogram")
                        ) {
                            fileName = name
                            break
                        }
                    }

                    if (fileName == null) return@mapNotNull null

                    val encoded = fileName.split("/").joinToString("/") {
                        URLEncoder.encode(it, "UTF-8").replace("+", "%20")
                    }

                    Track(
                        id = id,
                        title = d.optString("title", "Без названия"),
                        artist = d.optString("creator", "Неизвестный исполнитель"),
                        license = d.optString("licenseurl"),
                        stream = "https://archive.org/download/$id/$encoded"
                    )

                } catch (_: Exception) {
                    null
                }
            }

        } catch (_: Exception) {
            emptyList()
        }
    }

@Composable
fun NoctraApp(
    player: ExoPlayer,
    loadCatalog: (String) -> Unit,
    catalog: State<List<Track>>
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var current by remember { mutableStateOf<Track?>(null) }
    var playing by remember { mutableStateOf(false) }

    val prefs = remember {
        context.getSharedPreferences("noctra", 0)
    }

    val favorites = remember {
        mutableStateListOf<String>().apply {
            addAll(prefs.getStringSet("favorites", emptySet()) ?: emptySet())
        }
    }

    fun toggleFavorite(track: Track) {
        if (favorites.contains(track.id)) {
            favorites.remove(track.id)
        } else {
            favorites.add(track.id)
        }

        prefs.edit()
            .putStringSet("favorites", favorites.toSet())
            .apply()
    }

    val waveTracks = remember(catalog.value, favorites.toList()) {
        val likedArtists = catalog.value
            .filter { favorites.contains(it.id) }
            .map { it.artist }
            .toSet()

        val recommended = catalog.value.filter {
            !favorites.contains(it.id) &&
            likedArtists.any { artist ->
                artist.equals(it.artist, ignoreCase = true)
            }
        }

        if (recommended.isNotEmpty()) recommended else
            catalog.value.filter { !favorites.contains(it.id) }
    }

    LaunchedEffect(Unit) {
        if (catalog.value.isEmpty()) loadCatalog("")
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }
        }

        player.addListener(listener)

        onDispose {
            player.removeListener(listener)
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
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        icon = { Icon(Icons.Default.Home, null) },
                        label = { Text("Главная") }
                    )

                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        icon = { Icon(Icons.Default.LibraryMusic, null) },
                        label = { Text("Моя волна") }
                    )

                    NavigationBarItem(
                        selected = tab == 2,
                        onClick = { tab = 2 },
                        icon = { Icon(Icons.Default.Search, null) },
                        label = { Text("Поиск") }
                    )

                    NavigationBarItem(
                        selected = tab == 3,
                        onClick = { tab = 3 },
                        icon = { Icon(Icons.Default.Favorite, null) },
                        label = { Text("Медиатека") }
                    )
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
                        .padding(vertical = 20.dp),
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

                    Box(
                        Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(Color.LightGray, Color.DarkGray)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("N", color = White, fontSize = 21.sp)
                    }
                }

                when (tab) {

                    0 -> {
                        Text(
                            "ТВОЙ МУЗЫКАЛЬНЫЙ МИР",
                            color = Muted,
                            fontSize = 10.sp,
                            letterSpacing = 2.sp
                        )

                        Spacer(Modifier.height(10.dp))

                        Text(
                            "Слушай то, что\nтебе нравится.",
                            color = White,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 36.sp
                        )

                        Spacer(Modifier.height(20.dp))

                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(150.dp)
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
                            Column(Modifier.align(Alignment.CenterStart)) {
                                Text(
                                    "NOCTRA RADIO",
                                    color = Accent,
                                    fontSize = 10.sp,
                                    letterSpacing = 2.sp
                                )

                                Spacer(Modifier.height(8.dp))

                                Text(
                                    "ТВОЯ ВОЛНА",
                                    color = White,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                Text(
                                    "Музыка, подобранная для тебя",
                                    color = Muted,
                                    fontSize = 11.sp
                                )
                            }
                        }

                        Spacer(Modifier.height(22.dp))

                        Text(
                            "ИСПОЛНИТЕЛИ",
                            color = White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(Modifier.height(12.dp))

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(
                                catalog.value.map { it.artist }
                                    .distinct()
                                    .take(10)
                            ) { artist ->
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Panel)
                                        .clickable {
                                            query = artist
                                            loadCatalog(artist)
                                            tab = 2
                                        }
                                        .padding(14.dp)
                                ) {
                                    Text(
                                        artist,
                                        color = White,
                                        maxLines = 1,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(20.dp))

                        Text(
                            "ПОПУЛЯРНЫЕ ТРЕКИ",
                            color = White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )

                        TrackList(
                            catalog.value,
                            favorites,
                            onFavorite = ::toggleFavorite,
                            onPlay = {
                                current = it
                                playTrack(player, it)
                            }
                        )
                    }

                    1 -> {
                        Text(
                            "ТВОЯ ВОЛНА",
                            color = Muted,
                            fontSize = 11.sp,
                            letterSpacing = 2.sp
                        )

                        Spacer(Modifier.height(10.dp))

                        Text(
                            "Для тебя.",
                            color = White,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(Modifier.height(8.dp))

                        Text(
                            "Рекомендации на основе твоих любимых треков.",
                            color = Muted,
                            fontSize = 12.sp
                        )

                        Spacer(Modifier.height(20.dp))

                        if (favorites.isEmpty()) {
                            Text(
                                "Ставь лайки понравившимся трекам. Так NOCTRA сможет подобрать похожую музыку.",
                                color = Muted,
                                fontSize = 13.sp
                            )
                        }

                        TrackList(
                            waveTracks,
                            favorites,
                            onFavorite = ::toggleFavorite,
                            onPlay = {
                                current = it
                                playTrack(player, it)
                            }
                        )
                    }

                    2 -> {
                        Text(
                            "ПОИСК",
                            color = Muted,
                            fontSize = 11.sp,
                            letterSpacing = 2.sp
                        )

                        Spacer(Modifier.height(12.dp))

                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = {
                                Text("Трек или исполнитель")
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Search, null)
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp)
                        )

                        Spacer(Modifier.height(10.dp))

                        Button(
                            onClick = { loadCatalog(query) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Accent
                            )
                        ) {
                            Text("Найти музыку", color = Bg)
                        }

                        Spacer(Modifier.height(16.dp))

                        TrackList(
                            catalog.value,
                            favorites,
                            onFavorite = ::toggleFavorite,
                            onPlay = {
                                current = it
                                playTrack(player, it)
                            }
                        )
                    }

                    3 -> {
                        Text(
                            "ТВОЯ МЕДИАТЕКА",
                            color = Muted,
                            fontSize = 11.sp,
                            letterSpacing = 2.sp
                        )

                        Spacer(Modifier.height(12.dp))

                        Text(
                            "Избранное",
                            color = White,
                            fontSize = 27.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(Modifier.height(14.dp))

                        val liked = catalog.value.filter {
                            favorites.contains(it.id)
                        }

                        if (liked.isEmpty()) {
                            Text(
                                "Здесь будут твои любимые треки.",
                                color = Muted
                            )
                        } else {
                            TrackList(
                                liked,
                                favorites,
                                onFavorite = ::toggleFavorite,
                                onPlay = {
                                    current = it
                                    playTrack(player, it)
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                current?.let { track ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Panel)
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                track.title,
                                color = White,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            Text(
                                track.artist,
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

                Spacer(Modifier.height(8.dp))
            }
        }
    }
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
        Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 25.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Пока нет треков.\nПопробуй другой запрос.",
                color = Muted,
                fontSize = 13.sp
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.heightIn(max = 380.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            items(tracks, key = { it.id }) { track ->

                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onPlay(track) }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(45.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(Color.DarkGray, Color.Black)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("♪", color = White, fontSize = 23.sp)
                    }

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
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 14.sp
                        )

                        Text(
                            track.artist,
                            color = Muted,
                            maxLines = 1,
                            fontSize = 11.sp
                        )
                    }

                    IconButton(
                        onClick = { onFavorite(track) }
                    ) {
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
