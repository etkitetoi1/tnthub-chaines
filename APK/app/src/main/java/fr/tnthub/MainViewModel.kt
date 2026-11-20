package fr.tnthub

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("tnthub", 0)
    var channels by mutableStateOf(ChannelRepository.load(app))
        private set

    private val order = listOf("TNT", "Infos", "Locales", "Sport", "Cinéma", "Divertissement", "Documentaires", "Lifestyle", "Jeunesse", "Musique", "Culture", "Religion", "Étrangères", "UK", "US", "Espagnol", "Allemand")
    val categories: List<String>
        get() {
            val present = channels.flatMap { it.categories }.distinct()
            return listOf("Tous", "Favoris") + order.filter { it in present } + present.filter { it !in order }
        }

    var selected by mutableStateOf("Tous")
    /** "free" = Free TV, "molotov" = Molotov, "app" = application officielle de la chaîne */
    var provider by mutableStateOf(prefs.getString("provider", "free") ?: "free")
    var favorites by mutableStateOf(prefs.getStringSet("fav", emptySet())!!.toSet())
    var epg by mutableStateOf<EpgData?>(null)
    var loading by mutableStateOf(false)
    var status by mutableStateOf<String?>(null)

    init {
        viewModelScope.launch {
            if (ChannelRepository.refreshRemote(app)) channels = ChannelRepository.load(app)
            epg = EpgRepository.loadCache(app)
            val age = System.currentTimeMillis() - (epg?.fetchedAt ?: 0L)
            if (epg == null || age > 6 * 3600 * 1000L) refresh()
        }
    }

    fun refresh() {
        if (loading) return
        loading = true
        status = "Mise à jour du guide…"
        viewModelScope.launch {
            try {
                if (ChannelRepository.refreshRemote(getApplication())) channels = ChannelRepository.load(getApplication())
                epg = EpgRepository.refresh(getApplication(), channels)
                status = "Guide mis à jour à " + SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date()) + (epg?.note?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "")
            } catch (e: Exception) {
                status = "Guide indisponible : ${e.message ?: e.javaClass.simpleName}"
            } finally {
                loading = false
            }
        }
    }

    fun cycleProvider() {
        provider = when (provider) { "free" -> "molotov"; "molotov" -> "app"; else -> "free" }
        prefs.edit().putString("provider", provider).apply()
    }

    fun toggleFavorite(id: String) {
        favorites = if (id in favorites) favorites - id else favorites + id
        prefs.edit().putStringSet("fav", favorites).apply()
    }

    fun countFor(cat: String): Int = when (cat) {
        "Tous" -> channels.size
        "Favoris" -> channels.count { it.id in favorites }
        else -> channels.count { cat in it.categories }
    }

    fun visibleChannels(): List<Channel> = when (selected) {
        "Tous" -> channels
        "Favoris" -> channels.filter { it.id in favorites }
        else -> channels.filter { selected in it.categories }
    }
}
