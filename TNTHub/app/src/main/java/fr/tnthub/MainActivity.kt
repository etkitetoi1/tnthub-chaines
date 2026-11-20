package fr.tnthub

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Locale

private val Accent = Color(0xFF4FC3F7)
private val BgTop = Color(0xFF0B1118)
private val BgBottom = Color(0xFF111C27)
private val CardBg = Color(0xFF16212C)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = BgTop, surface = BgTop)) {
                Surface(Modifier.fillMaxSize(), color = BgTop) { App() }
            }
        }
    }
}

private val timeFmt get() = SimpleDateFormat("HH:mm", Locale.FRANCE)

/** Ouvre le direct : application officielle si installée, sinon navigateur. */
const val MOLOTOV_PKG = "tv.molotov.app"

const val FREE_HOME = "https://tv.free.fr/"

/** Application Free TV (ex-OQEE). Mobile confirmé ; la variante TV est à vérifier. */
val FREE_PKGS = listOf("net.oqee.androidmobile", "net.oqee.androidtv")

fun openLive(ctx: Context, ch: Channel, provider: String) {
    if (provider == "free" && ch.viaFree) {
        // Lien modifiable sans recompiler : "freeAppLink" dans config.json, ex. "https://tv.free.fr/home/included_channels/{id}/play"
        val template = EpgRepository.configString(ctx, "freeAppLink")
        val fid = EpgRepository.freeId(ch)
        val url = if (template != null && fid != null) template.replace("{id}", fid) else (ch.freeUrl ?: FREE_HOME)
        // Mode d'ouverture (modifiable sans recompiler : "freeOpenMode" dans config.json)
        //   "webview" (défaut) : lecteur web intégré à TNT Hub, "browser" : navigateur, "app" : application Free TV
        val mode = EpgRepository.configString(ctx, "freeOpenMode") ?: "webview"
        if (mode == "webview") {
            try {
                ctx.startActivity(Intent(ctx, WebPlayerActivity::class.java).putExtra("url", url))
                return
            } catch (_: Exception) { }
        }
        if (mode == "app") {
            // 1) Lien envoyé directement à l'application Free TV (si elle sait l'ouvrir).
            //    CLEAR_TASK : l'appli repart de zéro et traite le lien, au lieu de rester sur son accueil si elle était déjà ouverte.
            for (pkg in FREE_PKGS) {
                if (ctx.packageManager.getLaunchIntentForPackage(pkg) == null) continue
                try {
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    )
                    return
                } catch (_: ActivityNotFoundException) { }
            }
            // 2) L'appli est installée mais n'accepte pas le lien : on l'ouvre sur son accueil
            for (pkg in FREE_PKGS) {
                val launch = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: continue
                try {
                    ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    Toast.makeText(ctx, "Free TV : choisissez ${ch.name}", Toast.LENGTH_SHORT).show()
                    return
                } catch (_: ActivityNotFoundException) { }
            }
        }
        // 3) Pas d'appli Free TV : navigateur
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            if (ch.freeUrl == null) Toast.makeText(ctx, "Free TV : choisissez ${ch.name}", Toast.LENGTH_SHORT).show()
            return
        } catch (_: ActivityNotFoundException) { }
    }
    if (provider == "molotov" && ch.viaMolotov) {
        val launch = ctx.packageManager.getLaunchIntentForPackage(MOLOTOV_PKG)
        if (launch != null) {
            try {
                ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Toast.makeText(ctx, "Molotov : choisissez ${ch.name}", Toast.LENGTH_SHORT).show()
                return
            } catch (_: ActivityNotFoundException) { }
        }
    }
    val uri = Uri.parse(ch.liveUrl)
    val attempts = buildList {
        ch.appPackage?.let { add(Intent(Intent.ACTION_VIEW, uri).setPackage(it)) }
        add(Intent(Intent.ACTION_VIEW, uri))
    }
    for (i in attempts) {
        try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) { }
    }
    Toast.makeText(ctx, "Aucune application ni navigateur pour ouvrir ${ch.name}", Toast.LENGTH_LONG).show()
}

@Composable
fun App(vm: MainViewModel = viewModel()) {
    val ctx = LocalContext.current
    var dialogChannel by remember { mutableStateOf<Channel?>(null) }

    Column(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(BgTop, BgBottom)))
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        // Titre + choix du fournisseur
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("TNT Hub", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
            FilterChip(
                selected = true,
                onClick = { vm.cycleProvider() },
                label = { Text("Via : " + when (vm.provider) { "free" -> "Free TV"; "molotov" -> "Molotov"; else -> "App officielle" }) }
            )
        }
        Spacer(Modifier.height(8.dp))
        // Bouton Actualiser juste sous le titre + état du guide
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { vm.refresh() }, enabled = !vm.loading, shape = RoundedCornerShape(20.dp)) {
                Text("Actualiser le guide")
            }
            Spacer(Modifier.width(12.dp))
            Text(vm.status ?: "", fontSize = 13.sp, color = Color.Gray, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(14.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.categories) { cat ->
                FilterChip(
                    selected = vm.selected == cat,
                    onClick = { vm.selected = cat },
                    label = { Text("$cat  ${vm.countFor(cat)}") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Accent,
                        selectedLabelColor = Color.Black
                    )
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        val list = vm.visibleChannels()
        if (list.isEmpty()) {
            Text("Aucune chaîne ici. Appui long sur une chaîne pour l'ajouter aux favoris.", color = Color.Gray)
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(280.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(4.dp)
        ) {
            items(list, key = { it.id }) { ch ->
                ChannelCard(
                    ch = ch, epg = vm.epg, fav = ch.id in vm.favorites,
                    onOpen = { openLive(ctx, ch, vm.provider) },
                    onMenu = { dialogChannel = ch }
                )
            }
        }
    }

    dialogChannel?.let { ch ->
        val now = System.currentTimeMillis()
        val upcoming = vm.epg?.upcoming(ch.id, now)?.take(10).orEmpty()
        val isFav = ch.id in vm.favorites
        AlertDialog(
            onDismissRequest = { dialogChannel = null },
            title = { Text(ch.name) },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    upcoming.firstOrNull { it.start <= now }?.image?.let { img ->
                        AsyncImage(
                            model = img, contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    if (upcoming.isEmpty()) Text("Programme indisponible.", color = Color.Gray)
                    upcoming.forEach { p ->
                        Text("${timeFmt.format(p.start)}  ${p.title}", fontWeight = if (p.start <= now) FontWeight.Bold else FontWeight.Normal)
                        p.desc?.let { Text(it, fontSize = 12.sp, color = Color.Gray, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { openLive(ctx, ch, vm.provider); dialogChannel = null }) { Text("Ouvrir le direct") }
            },
            dismissButton = {
                TextButton(onClick = { vm.toggleFavorite(ch.id) }) { Text(if (isFav) "Retirer des favoris" else "Ajouter aux favoris") }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChannelCard(ch: Channel, epg: EpgData?, fav: Boolean, onOpen: () -> Unit, onMenu: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val cur = epg?.current(ch.id, now)
    val nxt = epg?.next(ch.id, now)
    val icon = epg?.icons?.get(ch.id)
    val shape = RoundedCornerShape(14.dp)
    val tint = remember(ch.name) { Color.hsv((ch.name.hashCode() and 0x7fffffff) % 360f, 0.45f, 0.55f) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .border(if (focused) 3.dp else 1.dp, if (focused) Accent else Color(0x22FFFFFF), shape)
            .combinedClickable(onClick = onOpen, onLongClick = onMenu),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = if (focused) Color(0xFF1E2F3F) else CardBg)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val thumb = cur?.image
            Box(
                Modifier.width(104.dp).height(58.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (icon != null || thumb != null) Color(0xFF263238) else tint),
                contentAlignment = Alignment.Center
            ) {
                if (thumb != null) {
                    // Vignette du programme en cours + petit logo de la chaîne dans le coin
                    AsyncImage(model = thumb, contentDescription = cur?.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    if (icon != null) {
                        AsyncImage(
                            model = icon, contentDescription = ch.name, contentScale = ContentScale.Fit,
                            modifier = Modifier.align(Alignment.BottomStart).padding(3.dp).size(24.dp)
                                .clip(RoundedCornerShape(5.dp)).background(Color(0xCC0B1118)).padding(2.dp)
                        )
                    }
                } else if (icon != null) {
                    AsyncImage(model = icon, contentDescription = ch.name, contentScale = ContentScale.Fit, modifier = Modifier.padding(5.dp).fillMaxSize())
                } else {
                    Text(ch.name.filter { it.isLetterOrDigit() }.take(3).uppercase(), fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ch.number?.let {
                        Text(
                            "$it", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black,
                            modifier = Modifier.clip(CircleShape).background(Accent).padding(horizontal = 7.dp, vertical = 1.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        ch.name + if (fav) "  ★" else "",
                        fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(3.dp))
                if (cur != null) {
                    Text("${timeFmt.format(cur.start)}  ${cur.title}", fontSize = 13.sp, color = Color(0xFFE0E6EB), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val prog = ((now - cur.start).toFloat() / (cur.stop - cur.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { prog },
                        color = Accent, trackColor = Color(0x33FFFFFF),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    )
                } else {
                    Text("Programme indisponible", fontSize = 13.sp, color = Color.Gray)
                }
                if (nxt != null) {
                    Text("${timeFmt.format(nxt.start)}  ${nxt.title}", fontSize = 12.sp, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
