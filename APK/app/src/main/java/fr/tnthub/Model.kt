package fr.tnthub

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class Channel(
    val id: String,
    val name: String,
    val number: Int?,
    val categories: List<String>,
    val epgNames: List<String>,
    val appPackage: String?,
    val liveUrl: String,
    val viaMolotov: Boolean = false,
    val viaFree: Boolean = false,
    val freeUrl: String? = null
)

data class Programme(val title: String, val start: Long, val stop: Long, val desc: String?, val image: String? = null)

data class EpgData(
    val programmes: Map<String, List<Programme>>,
    val icons: Map<String, String>,
    val fetchedAt: Long,
    val note: String = ""
) {
    fun current(id: String, t: Long) = programmes[id]?.firstOrNull { it.start <= t && t < it.stop }
    fun next(id: String, t: Long) = programmes[id]?.firstOrNull { it.start >= t }
    fun upcoming(id: String, t: Long) = programmes[id].orEmpty().filter { it.stop > t }
}

object ChannelRepository {
    /** Adresse du fichier channels.json hébergé sur GitHub. Remplacer etkitetoi1 (et le nom du dépôt si différent). */
    const val REMOTE_URL = "https://raw.githubusercontent.com/etkitetoi1/tnthub-chaines/main/channels.json"

    private fun parse(text: String): List<Channel> {
        val arr = JSONArray(text)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            fun strings(key: String) = o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            Channel(
                id = o.getString("id"),
                name = o.getString("name"),
                number = if (o.has("number")) o.getInt("number") else null,
                categories = strings("categories"),
                epgNames = strings("epgNames"),
                appPackage = o.optString("appPackage").ifEmpty { null },
                liveUrl = o.getString("liveUrl"),
                viaMolotov = o.optBoolean("viaMolotov", false),
                viaFree = o.optBoolean("viaFree", false),
                freeUrl = o.optString("freeUrl").ifEmpty { null }
            )
        }
    }

    private fun bundled(ctx: Context) = ctx.assets.open("channels.json").bufferedReader().use { it.readText() }

    /** Copie téléchargée si elle existe et est valide, sinon liste embarquée dans l'APK. */
    fun load(ctx: Context): List<Channel> {
        val f = File(ctx.filesDir, "channels.json")
        if (f.exists()) {
            try { return parse(f.readText()) } catch (_: Exception) { }
        }
        return parse(bundled(ctx))
    }

    /** Télécharge la liste en ligne. Renvoie true si une nouvelle liste valide a été enregistrée. */
    suspend fun refreshRemote(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        if ("VOTRE_PSEUDO" in REMOTE_URL) return@withContext false
        try {
            val conn = URL(REMOTE_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            parse(text) // vérifie que le fichier est valide avant de l'enregistrer
            File(ctx.filesDir, "channels.json").writeText(text)
            fetchConfig(ctx)
            true
        } catch (e: Exception) { false }
    }

    /** Télécharge config.json (même dossier que channels.json). Un échec n'a aucune conséquence. */
    private fun fetchConfig(ctx: Context) {
        try {
            val conn = URL(REMOTE_URL.replace("channels.json", "config.json")).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            JSONObject(text) // vérifie que le fichier est valide
            File(ctx.filesDir, "config.json").writeText(text)
        } catch (_: Exception) { }
    }

    /** Adresse du guide XMLTV définie dans config.json, ou null pour utiliser celle du code. */
    fun epgUrl(ctx: Context): String? = try {
        JSONObject(File(ctx.filesDir, "config.json").readText()).optString("epgUrl").ifEmpty { null }
    } catch (_: Exception) { null }
}
