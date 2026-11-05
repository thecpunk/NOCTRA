package com.noctra.music

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private val Bg = Color(0xFF0B0B0F)
private val Panel = Color(0xFF17171F)
private val Muted = Color(0xFF9A9A9A)
private val Accent = Color(0xFFD0D0D0)
private val White = Color(0xFFF4F4F4)

data class Track(val id: String, val title: String, val artist: String, val license: String, val page: String, val stream: String)

class MainActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = ExoPlayer.Builder(this).build()
        setContent {
            NoctraApp(
                player = player,
                loadCatalog = { query -> lifecycleScope.launch { catalogState.value = loadArchiveCatalog(query) } },
                catalog = catalogState
            )
        }
    }
    override fun onDestroy() { player.release(); super.onDestroy() }
    companion object { val catalogState = mutableStateOf<List<Track>>(emptyList()) }
}

private suspend fun loadArchiveCatalog(query: String): List<Track> = withContext(Dispatchers.IO) {
    try {
        val safeQuery = query.trim().replace("\\", " ").replace("\"", " ")
        val q = if (safeQuery.isBlank()) "mediatype:audio AND licenseurl:*" else "mediatype:audio AND licenseurl:* AND (title:\"$safeQuery\" OR creator:\"$safeQuery\")"
        val url = "https://archive.org/advancedsearch.php?q=${URLEncoder.encode(q, "UTF-8")}&fl[]=identifier&fl[]=title&fl[]=creator&fl[]=licenseurl&sort[]=downloads%20desc&rows=100&page=1&output=json"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 12000; readTimeout = 15000; setRequestProperty("User-Agent", "NOCTRA/0.1 (Android music player)") }
        val root = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        val docs = root.getJSONObject("response").getJSONArray("docs")
        val out = mutableListOf<Track>()
        for (i in 0 until docs.length()) {
            val d = docs.getJSONObject(i)
            val id = d.optString("identifier")
            val lic = d.optString("licenseurl")
            if (id.isBlank() || lic.isBlank()) continue
            // Keep only clearly reusable Creative Commons BY/BY-SA and public-domain/CC0 works.
            val allowed = lic.contains("creativecommons.org/licenses/by/", true) ||
                lic.contains("creativecommons.org/licenses/by-sa/", true) ||
                lic.contains("creativecommons.org/publicdomain/zero/", true) ||
                lic.contains("creativecommons.org/publicdomain/mark/", true)
            if (!allowed) continue
            val metaConn = (URL("https://archive.org/metadata/$id").openConnection() as HttpURLConnection).apply { connectTimeout = 10000; readTimeout = 12000 }
            val meta = JSONObject(metaConn.inputStream.bufferedReader().use { it.readText() })
            val files = meta.optJSONArray("files") ?: continue
            var fileName: String? = null
            for (j in 0 until files.length()) {
                val f = files.optJSONObject(j) ?: continue
                val name = f.optString("name")
                val format = f.optString("format")
                if ((format.contains("MP3", true) || name.endsWith(".mp3", true)) && !name.contains("_spectrogram")) { fileName = name; break }
            }
            if (fileName != null) {
                val encoded = fileName!!.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
                out += Track(id, d.optString("title", "Без названия"), d.optString("creator", "Неизвестный исполнитель"), lic, "https://archive.org/details/$id", "https://archive.org/download/$id/$encoded")
            }
            if (out.size >= 30) break
        }
        conn.disconnect()
        out
    } catch (_: Exception) { emptyList() }
}

@Composable
fun NoctraApp(player: ExoPlayer, loadCatalog: (String) -> Unit, catalog: State<List<Track>>) {
    var tab by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var current by remember { mutableStateOf<Track?>(null) }
    var playing by remember { mutableStateOf(false) }
    val favorites = remember { mutableStateListOf<String>() }
    val playlists = remember { mutableStateListOf<String>() }
    LaunchedEffect(Unit) { loadCatalog("") }
    DisposableEffect(player) {
        val listener = object : Player.Listener { override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying } }
        player.addListener(listener); onDispose { player.removeListener(listener) }
    }
    MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = Panel, primary = Accent, onBackground = White, onSurface = White)) {
        Scaffold(containerColor = Bg, bottomBar = {
            NavigationBar(containerColor = Panel, contentColor = White) {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Icon(Icons.Default.Home, null) }, label = { Text("Главная") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Icon(Icons.Default.Search, null) }, label = { Text("Поиск") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Icon(Icons.Default.LibraryMusic, null) }, label = { Text("Медиатека") })
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
                Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("NOCTRA", color = White, fontSize = 25.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
                        Text("YOUR MUSIC. YOUR NIGHT.", color = Muted, fontSize = 9.sp, letterSpacing = 2.sp)
                    }
                    Box(Modifier.size(40.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFFBDBDBD), Color(0xFF555555)))), contentAlignment = Alignment.Center) { Text("N", fontWeight = FontWeight.Bold, color = White, fontSize = 20.sp) }
                }
                when (tab) {
                    0 -> {
                        Text("ТВОЯ ВОЛНА", color = Muted, fontSize = 11.sp, letterSpacing = 2.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("Музыка для\nтвоего момента.", color = White, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(18.dp))
                        Box(Modifier.fillMaxWidth().height(145.dp).clip(RoundedCornerShape(22.dp)).background(Brush.linearGradient(listOf(Color(0xFF444444), Color(0xFF202020), Color(0xFF101010)))).padding(18.dp)) {
                            Column(Modifier.align(Alignment.CenterStart)) {
                                Text("OPEN SOUND", color = Color(0xFFE0E0E0), fontSize = 10.sp, letterSpacing = 2.sp)
                                Spacer(Modifier.height(8.dp)); Text("Музыка со\nвсего мира", color = White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                                Text("Открытые записи с проверяемыми лицензиями", color = Color(0xFFAAAAAA), fontSize = 10.sp)
                            }
                        }
                        Spacer(Modifier.height(22.dp))
                        SectionTitle("Мировой открытый каталог", "ОБНОВЛЯЕТСЯ")
                        TrackList(catalog.value, favorites, current) { track -> playTrack(player, track); current = track }
                    }
                    1 -> {
                        Text("ПОИСК", color = Muted, fontSize = 11.sp, letterSpacing = 2.sp)
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Трек или исполнитель") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, shape = RoundedCornerShape(16.dp), colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Panel, focusedBorderColor = Accent))
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = { loadCatalog(query) }, colors = ButtonDefaults.buttonColors(containerColor = Accent), shape = RoundedCornerShape(14.dp)) { Text("Найти музыку") }
                        Spacer(Modifier.height(18.dp)); TrackList(catalog.value, favorites, current) { track -> playTrack(player, track); current = track }
                    }
                    else -> {
                        Text("ТВОЯ МЕДИАТЕКА", color = Muted, fontSize = 11.sp, letterSpacing = 2.sp)
                        Spacer(Modifier.height(12.dp)); Text("Избранное", color = White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        val liked = catalog.value.filter { favorites.contains(it.id) }
                        if (liked.isEmpty()) Text("Отмечай треки сердцем — они появятся здесь.", color = Muted) else TrackList(liked, favorites, current) { track -> playTrack(player, track); current = track }
                        Spacer(Modifier.height(22.dp)); Text("Плейлист", color = White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("Сохранённые плейлисты появятся в следующем обновлении.", color = Muted, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(14.dp))
                current?.let { track ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(Brush.linearGradient(listOf(Color(0xFFBDBDBD), Color(0xFF282828)))), contentAlignment = Alignment.Center) { Text("N", color = White, fontWeight = FontWeight.Bold) }
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = White, fontWeight = FontWeight.SemiBold); Text(track.artist, maxLines = 1, color = Muted, fontSize = 11.sp) }
                        IconButton(onClick = { if (player.isPlaying) player.pause() else player.play() }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = White) }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

private fun playTrack(player: ExoPlayer, track: Track) { player.setMediaItem(MediaItem.fromUri(track.stream)); player.prepare(); player.play() }

@Composable private fun SectionTitle(title: String, trailing: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(title, color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Text(trailing, color = Accent, fontSize = 9.sp, letterSpacing = 1.sp) }
}

@Composable private fun TrackList(tracks: List<Track>, favorites: MutableList<String>, current: Track?, onPlay: (Track) -> Unit) {
    if (tracks.isEmpty()) {
        Box(Modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) { Text("Загружаем каталог…\nЕсли список пуст, попробуй поискать другой запрос.", color = Muted, fontSize = 13.sp) }
    } else LazyColumn(modifier = Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(tracks, key = { it.id }) { track ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onPlay(track) }.padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(Color(0xFF555555), Color(0xFF1C1C1C)))), contentAlignment = Alignment.Center) { Text("♪", color = Color(0xFFE2E2E2), fontSize = 22.sp) }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(track.title, color = White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                    Text(track.artist, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp)
                }
                IconButton(onClick = { if (favorites.contains(track.id)) favorites.remove(track.id) else favorites.add(track.id) }) { Icon(if (favorites.contains(track.id)) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = if (favorites.contains(track.id)) Accent else Muted) }
            }
        }
    }
}
