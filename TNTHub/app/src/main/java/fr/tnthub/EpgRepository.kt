package fr.tnthub

import android.content.Context
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * Guide des programmes : Free TV (API publique d'OQEE, prioritaire) + flux XMLTV public en complément
 * pour les chaînes absentes de Free TV (TF1, M6...) et pour les logos.
 * XMLTV :
 * Le fichier complet est volumineux (~80 Mo) : il est lu en flux, on ne garde que
 * les chaines de channels.json et les 36 prochaines heures, puis on met en cache.
 */
object EpgRepository {
    // A VERIFIER : adresse du flux XMLTV (projet racacax/XML-TV-Fr, site xmltvfr.fr).
    const val XMLTV_URL = "https://xmltvfr.fr/xmltv/xmltv.xml.gz"
    private const val HORIZON_MS = 36L * 3600 * 1000

    /** Guide de Free TV (ex-OQEE) : une requête = une heure, toutes chaînes confondues. Modifiable via config.json (freeEpgUrl). */
    const val FREE_EPG_BASE = "https://api.oqee.net/api/v1/epg/all/"
    private const val FREE_HOURS = 10

    private fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]"), "")

    private fun cacheFile(ctx: Context) = File(ctx.cacheDir, "epg_cache.json")

    suspend fun refresh(ctx: Context, channels: List<Channel>): EpgData = withContext(Dispatchers.IO) {
        coroutineScope {
            val free = async { runCatching { fetchFree(ctx, channels) } }
            val xml = async { runCatching { fetchXmltv(ctx, channels) } }
            val f = free.await()
            val x = xml.await()
            val freeMap = f.getOrNull().orEmpty()
            val xmlData = x.getOrNull()
            if (freeMap.isEmpty() && xmlData == null) {
                throw (f.exceptionOrNull() ?: x.exceptionOrNull() ?: Exception("aucune source de guide"))
            }
            val merged = HashMap<String, List<Programme>>()
            xmlData?.programmes?.let { merged.putAll(it) }
            merged.putAll(freeMap) // Free TV prioritaire
            val note = "Free TV : ${freeMap.size} chaînes" +
                (if (f.isFailure) " (indisponible)" else "") +
                " · XMLTV : ${xmlData?.programmes?.size ?: 0}" +
                (if (x.isFailure) " (indisponible)" else "")
            val data = EpgData(merged, xmlData?.icons ?: emptyMap(), System.currentTimeMillis(), note)
            save(ctx, data)
            data
        }
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) TNTHub")
        conn.setRequestProperty("Accept", "application/json")
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    fun configString(ctx: Context, key: String): String? = try {
        JSONObject(File(ctx.filesDir, "config.json").readText()).optString(key).ifEmpty { null }
    } catch (_: Exception) { null }

    /** Les adresses d'images Free TV contiennent la hauteur voulue (h%d ou h1) : on demande 320 px. */
    private fun pictureUrl(raw: String?): String? =
        raw?.takeIf { it.isNotBlank() }?.replace("%d", "320")?.replace(Regex("/h1$"), "/h320")

    /** Identifiant Free TV d'une chaîne, lu dans son lien .../included_channels/{id}/play. */
    fun freeId(ch: Channel): String? =
        ch.freeUrl?.let { Regex("included_channels/(\\d+)").find(it)?.groupValues?.get(1) }

    /** Programmes des FREE_HOURS prochaines heures pour les chaînes ayant un lien Free TV. */
    private fun fetchFree(ctx: Context, channels: List<Channel>): Map<String, List<Programme>> {
        val base = configString(ctx, "freeEpgUrl") ?: FREE_EPG_BASE
        val idToApp = HashMap<String, String>()
        channels.forEach { ch -> freeId(ch)?.let { idToApp[it] = ch.id } }
        if (idToApp.isEmpty()) return emptyMap()

        val now = System.currentTimeMillis()
        val first = now / 1000 / 3600 * 3600
        val byChannel = HashMap<String, HashMap<Long, Programme>>()
        for (h in 0 until FREE_HOURS) {
            val text = try { httpGet(base + (first + h * 3600L)) } catch (e: Exception) { if (h == 0) throw e else continue }
            val entries = JSONObject(text).optJSONObject("result")?.optJSONObject("entries") ?: continue
            for (key in entries.keys()) {
                val appId = idToApp[key] ?: continue
                val arr = entries.optJSONArray(key) ?: continue
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val live = item.optJSONObject("live") ?: continue
                    val pics = item.optJSONObject("pictures")
                    val image = pictureUrl(pics?.optString("preview")?.ifEmpty { null } ?: pics?.optString("main"))
                    val s = live.optLong("start") * 1000
                    val e = live.optLong("end") * 1000
                    if (s == 0L || e <= now) continue
                    val desc = live.optString("short_description").ifEmpty { live.optString("description") }.ifEmpty { null }
                    byChannel.getOrPut(appId) { HashMap() }[s] = Programme(live.optString("title"), s, e, desc, image)
                }
            }
        }
        return byChannel.mapValues { (_, m) -> m.values.sortedBy { it.start } }
    }

    private fun fetchXmltv(ctx: Context, channels: List<Channel>): EpgData {
        val conn = URL(ChannelRepository.epgUrl(ctx) ?: XMLTV_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        val bis = BufferedInputStream(conn.inputStream)
        bis.mark(2)
        val b1 = bis.read(); val b2 = bis.read()
        bis.reset()
        val stream = if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(bis) else bis
        return stream.use { parse(it, channels) }
    }

    private fun parseTime(f: SimpleDateFormat, s: String?): Long? =
        try { if (s == null) null else f.parse(s.trim())?.time } catch (e: Exception) { null }

    private fun parse(input: InputStream, channels: List<Channel>): EpgData {
        val byName = HashMap<String, String>()
        channels.forEach { ch -> (ch.epgNames + ch.name).forEach { byName[norm(it)] = ch.id } }

        val xmlToApp = HashMap<String, String>()
        val icons = HashMap<String, String>()
        val result = HashMap<String, MutableList<Programme>>()
        val now = System.currentTimeMillis()
        val f = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)

        val p = Xml.newPullParser()
        p.setInput(input, null)
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "channel" -> {
                        val xid = p.getAttributeValue(null, "id") ?: ""
                        var icon: String? = null
                        val names = mutableListOf<String>()
                        while (true) {
                            val t = p.next()
                            if (t == XmlPullParser.END_DOCUMENT || (t == XmlPullParser.END_TAG && p.name == "channel")) break
                            if (t == XmlPullParser.START_TAG) {
                                when (p.name) {
                                    "display-name" -> names += p.nextText()
                                    "icon" -> icon = p.getAttributeValue(null, "src")
                                }
                            }
                        }
                        val appId = names.firstNotNullOfOrNull { byName[norm(it)] }
                            ?: byName[norm(xid.substringBefore('.'))]
                        if (appId != null) {
                            xmlToApp[xid] = appId
                            if (icon != null) icons.putIfAbsent(appId, icon)
                        }
                    }
                    "programme" -> {
                        val ch = xmlToApp[p.getAttributeValue(null, "channel") ?: ""]
                        val s = parseTime(f, p.getAttributeValue(null, "start"))
                        val e = parseTime(f, p.getAttributeValue(null, "stop"))
                        if (ch != null && s != null && e != null && e > now && s < now + HORIZON_MS) {
                            var title = ""
                            var desc: String? = null
                            var img: String? = null
                            while (true) {
                                val t = p.next()
                                if (t == XmlPullParser.END_DOCUMENT || (t == XmlPullParser.END_TAG && p.name == "programme")) break
                                if (t == XmlPullParser.START_TAG) {
                                    when (p.name) {
                                        "title" -> if (title.isEmpty()) title = p.nextText()
                                        "desc" -> desc = p.nextText()
                                        "icon" -> if (img == null) img = p.getAttributeValue(null, "src")
                                    }
                                }
                            }
                            result.getOrPut(ch) { mutableListOf() } += Programme(title, s, e, desc, img)
                        }
                    }
                }
            }
            ev = p.next()
        }
        result.values.forEach { l -> l.sortBy { it.start } }
        return EpgData(result, icons, now)
    }

    private fun save(ctx: Context, d: EpgData) {
        val root = JSONObject()
        root.put("fetchedAt", d.fetchedAt)
        root.put("icons", JSONObject(d.icons as Map<*, *>))
        val prog = JSONObject()
        d.programmes.forEach { (id, list) ->
            val arr = JSONArray()
            list.forEach { pr ->
                arr.put(JSONObject().put("t", pr.title).put("s", pr.start).put("e", pr.stop).put("d", pr.desc ?: "").put("i", pr.image ?: ""))
            }
            prog.put(id, arr)
        }
        root.put("prog", prog)
        cacheFile(ctx).writeText(root.toString())
    }

    suspend fun loadCache(ctx: Context): EpgData? = withContext(Dispatchers.IO) {
        try {
            val f = cacheFile(ctx)
            if (!f.exists()) return@withContext null
            val root = JSONObject(f.readText())
            val icons = root.getJSONObject("icons").let { o -> o.keys().asSequence().associateWith { o.getString(it) } }
            val progObj = root.getJSONObject("prog")
            val prog = progObj.keys().asSequence().associateWith { id ->
                val a = progObj.getJSONArray(id)
                (0 until a.length()).map {
                    val o = a.getJSONObject(it)
                    Programme(o.getString("t"), o.getLong("s"), o.getLong("e"), o.optString("d").ifEmpty { null }, o.optString("i").ifEmpty { null })
                }
            }
            EpgData(prog, icons, root.getLong("fetchedAt"))
        } catch (e: Exception) { null }
    }
}
